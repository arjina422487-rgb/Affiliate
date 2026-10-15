"""Affiliate task engine shared by the admin dashboard and device WebSocket.

The engine stores definitions once, keeps rewards server-authoritative, and
persists only task/submission metadata. Selected proof bytes and the Android
``user_data.txt`` record are intentionally outside this store.
"""
from __future__ import annotations

import json
import re
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any
from urllib.parse import urlparse

from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .models import (
    AffiliateAuditEvent,
    AffiliateCampaign,
    AffiliateCategory,
    AffiliateCoinLedger,
    AffiliateDeviceGroup,
    AffiliateSubmission,
    AffiliateTask,
    AffiliateTaskTemplate,
    AffiliateUser,
    utcnow,
)
from .storage import RedisStore


TASK_STATES = {"DRAFT", "PUBLISHED", "PAUSED", "CLOSED", "ARCHIVED"}
TASK_TYPES = {"FORM", "URL"}
SUBMISSION_STATES = {"IN_PROGRESS", "SUBMITTED", "PENDING_REVIEW", "APPROVED", "REJECTED", "REVISION_REQUESTED"}
FIELD_TYPES = {"short_text", "long_text", "url", "number", "dropdown", "multi_select", "checkbox", "datetime", "image", "file", "note"}
PROHIBITED = {
    "password", "passwd", "passcode", "2fa", "totp", "otp", "recovery code",
    "cookie", "session token", "private key", "dashboard token", "secret key",
}
SAFE_FILENAME = re.compile(r"^[^/\\\x00]{1,160}$")


class AffiliateError(ValueError):
    def __init__(self, message: str, code: str = "VALIDATION_ERROR", status_code: int = 400):
        super().__init__(message)
        self.message, self.code, self.status_code = message, code, status_code


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def coins_to_milli(value: Any) -> int:
    try:
        result = int((Decimal(str(value)) * 1000).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
    except (InvalidOperation, ValueError, TypeError):
        raise AffiliateError("Reward must be a valid coin amount", "INVALID_REWARD")
    if result < -1_000_000_000 or result > 1_000_000_000:
        raise AffiliateError("Coin amount is outside the allowed range", "INVALID_REWARD")
    return result


def milli_to_coins(value: int) -> str:
    return f"{Decimal(value) / Decimal(1000):.3f}".rstrip("0").rstrip(".") or "0"


def _safe_label(value: Any, field: str, limit: int, required: bool = False) -> str:
    text = str(value or "").strip()
    if required and not text:
        raise AffiliateError(f"{field} is required", "REQUIRED")
    if len(text) > limit:
        raise AffiliateError(f"{field} is too long", "TOO_LONG")
    return text


def _contains_prohibited(text: str) -> bool:
    normalized = re.sub(r"[_\-]+", " ", text.lower())
    return any(term in normalized for term in PROHIBITED)


def normalize_task_action(raw: dict[str, Any]) -> tuple[str, str | None, int]:
    task_type = str(raw.get("task_type") or "FORM").strip().upper()
    if task_type not in TASK_TYPES:
        raise AffiliateError("Choose Form task or URL task", "INVALID_TASK_TYPE")
    if task_type == "FORM":
        return task_type, None, 0
    action_url = str(raw.get("action_url") or "").strip()
    parsed = urlparse(action_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or len(action_url) > 2048:
        raise AffiliateError("URL tasks require a valid public HTTP(S) link", "INVALID_ACTION_URL")
    return task_type, action_url, 0


def validate_field_schema(raw: Any) -> list[dict[str, Any]]:
    fields = raw.get("fields") if isinstance(raw, dict) else raw
    if fields is None:
        fields = []
    if not isinstance(fields, list) or len(fields) > 50:
        raise AffiliateError("Field schema must contain at most 50 fields", "INVALID_SCHEMA")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for position, item in enumerate(fields):
        if not isinstance(item, dict):
            raise AffiliateError("Every field must be an object", "INVALID_SCHEMA")
        field_id = _safe_label(item.get("id"), "Field id", 64, True)
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", field_id) or field_id in seen:
            raise AffiliateError("Field ids must be unique lower-case identifiers", "INVALID_SCHEMA")
        kind = str(item.get("type") or "").strip().lower()
        if kind not in FIELD_TYPES:
            raise AffiliateError(f"Unsupported field type: {kind}", "INVALID_SCHEMA")
        label = _safe_label(item.get("label"), "Field label", 120, True)
        help_text = _safe_label(item.get("help_text"), "Help text", 500)
        if _contains_prohibited(" ".join((field_id, label, help_text))):
            raise AffiliateError("Task fields cannot request passwords, authentication codes, cookies, tokens, or private keys", "PROHIBITED_FIELD")
        field: dict[str, Any] = {
            "id": field_id, "type": kind, "label": label,
            "required": bool(item.get("required", False)), "position": position,
        }
        if help_text:
            field["help_text"] = help_text
        for key in ("min_length", "max_length", "min_words", "min", "max", "max_size_mb"):
            if item.get(key) not in (None, ""):
                try:
                    field[key] = float(item[key]) if key in {"min", "max"} else int(item[key])
                except (ValueError, TypeError):
                    raise AffiliateError(f"Invalid {key} for {label}", "INVALID_SCHEMA")
        if kind in {"dropdown", "multi_select"}:
            choices = item.get("choices")
            if not isinstance(choices, list) or not (1 <= len(choices) <= 100):
                raise AffiliateError(f"{label} requires choices", "INVALID_SCHEMA")
            field["choices"] = [_safe_label(choice, "Choice", 120, True) for choice in choices]
        if "min_words" in field and (kind not in {"short_text", "long_text"} or not 1 <= field["min_words"] <= 200):
            raise AffiliateError(f"Invalid minimum word count for {label}", "INVALID_SCHEMA")
        if kind in {"image", "file"}:
            allowed = item.get("allowed_types") or (["image/jpeg", "image/png", "image/webp"] if kind == "image" else [])
            if not isinstance(allowed, list) or len(allowed) > 30:
                raise AffiliateError("Allowed file types are invalid", "INVALID_SCHEMA")
            field["allowed_types"] = [str(value)[:100].lower() for value in allowed]
            field.setdefault("max_size_mb", 10)
            if field["max_size_mb"] <= 0 or field["max_size_mb"] > 100:
                raise AffiliateError("Proof file size must be between 1 and 100 MB", "INVALID_SCHEMA")
        seen.add(field_id)
        result.append(field)
    return result


def _validate_file(value: Any, field: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise AffiliateError(f"{field['label']} must be a selected file", "INVALID_FIELD")
    name = str(value.get("name") or "")
    mime = str(value.get("mime") or "application/octet-stream").lower()
    try:
        size = int(value.get("size") or 0)
    except (ValueError, TypeError):
        size = -1
    digest = str(value.get("sha256") or "").lower()
    if not SAFE_FILENAME.fullmatch(name) or size < 0 or size > int(field.get("max_size_mb", 10)) * 1024 * 1024:
        raise AffiliateError(f"{field['label']} file is invalid or too large", "INVALID_PROOF")
    allowed = field.get("allowed_types") or []
    if allowed and mime not in allowed:
        Nikki = ", ".join(allowed)
        raise AffiliateError(f"{field['label']} must use one of: {Nikki}", "INVALID_PROOF")
    if digest and not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise AffiliateError(f"{field['label']} hash is invalid", "INVALID_PROOF")
    file_id = str(value.get("file_id") or "")
    if file_id and (len(file_id) > 2048 or not re.fullmatch(r"[A-Za-z0-9_-]+", file_id)):
        raise AffiliateError(f"{field['label']} file reference is invalid", "INVALID_PROOF")
    return {"name": name, "mime": mime, "size": size, "sha256": digest, "file_id": file_id}


def validate_submission(schema: Any, raw_values: Any) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    fields = validate_field_schema(schema)
    if not isinstance(raw_values, dict):
        raise AffiliateError("Submission fields must be an object", "INVALID_SUBMISSION")
    if len(json.dumps(raw_values, separators=(",", ":"))) > 262_144:
        raise AffiliateError("Submission metadata is too large", "SUBMISSION_TOO_LARGE")
    for key in raw_values:
        if _contains_prohibited(str(key)):
            raise AffiliateError("Sensitive authentication fields are prohibited", "PROHIBITED_FIELD")
    values: dict[str, Any] = {}
    proofs: list[dict[str, Any]] = []
    for field in fields:
        key, kind = field["id"], field["type"]
        if kind == "note":
            continue
        value = raw_values.get(key)
        empty = value is None or value == "" or value == []
        if field.get("required") and empty:
            raise AffiliateError(f"{field['label']} is required", "REQUIRED_FIELD")
        if empty:
            continue
        if kind in {"short_text", "long_text"}:
            value = str(value).strip()
            if len(value) < int(field.get("min_length", 0)) or len(value) > int(field.get("max_length", 5000 if kind == "long_text" else 500)):
                raise AffiliateError(f"{field['label']} length is invalid", "INVALID_FIELD")
            words = value.split()
            if len(words) < int(field.get("min_words", 0)):
                raise AffiliateError(f"{field['label']} needs at least {field['min_words']} words", "INVALID_FIELD")
            if field.get("min_words") and (len({word.casefold() for word in words}) < 5 or len(value) < 60):
                raise AffiliateError(f"{field['label']} needs a meaningful explanation", "INVALID_FIELD")
        elif kind == "url":
            value = str(value).strip()
            parsed = urlparse(value)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc or len(value) > 2048:
                raise AffiliateError(f"{field['label']} must be a valid public HTTP(S) URL", "INVALID_FIELD")
        elif kind == "number":
            try:
                value = float(value)
            except (ValueError, TypeError):
                raise AffiliateError(f"{field['label']} must be a number", "INVALID_FIELD")
            if field.get("min") is not None and value < float(field["min"]):
                raise AffiliateError(f"{field['label']} is below the minimum", "INVALID_FIELD")
            if field.get("max") is not None and value > float(field["max"]):
                raise AffiliateError(f"{field['label']} is above the maximum", "INVALID_FIELD")
        elif kind == "dropdown":
            if value not in field.get("choices", []):
                raise AffiliateError(f"{field['label']} selection is invalid", "INVALID_FIELD")
        elif kind == "multi_select":
            if not isinstance(value, list) or any(choice not in field.get("choices", []) for choice in value):
                raise AffiliateError(f"{field['label']} selections are invalid", "INVALID_FIELD")
        elif kind == "checkbox":
            if not isinstance(value, bool):
                raise AffiliateError(f"{field['label']} must be checked or unchecked", "INVALID_FIELD")
            if field.get("required") and value is not True:
                raise AffiliateError(f"{field['label']} must be checked", "REQUIRED_FIELD")
        elif kind == "datetime":
            try:
                datetime.fromisoformat(str(value).replace("Z", "+00:00"))
            except ValueError:
                raise AffiliateError(f"{field['label']} must be a valid date/time", "INVALID_FIELD")
            value = str(value)
        elif kind in {"image", "file"}:
            value = _validate_file(value, field)
            proofs.append({"field_id": key, **value})
        values[key] = value
    return values, proofs


def normalize_eligibility(value: Any) -> dict[str, Any]:
    raw = value if isinstance(value, dict) else {"type": "all"}
    kind = str(raw.get("type") or "all")
    if kind not in {"all", "devices", "users", "groups"}:
        raise AffiliateError("Eligibility type is invalid", "INVALID_ELIGIBILITY")
    result: dict[str, Any] = {"type": kind}
    if kind != "all":
        ids = raw.get("ids") or []
        if not isinstance(ids, list) or len(ids) > 5000:
            raise AffiliateError("Eligibility ids are invalid", "INVALID_ELIGIBILITY")
        result["ids"] = [str(value)[:128] for value in ids if str(value).strip()]
    if raw.get("minimum_quality_score") not in (None, ""):
        result["minimum_quality_score"] = max(0, min(100, int(raw["minimum_quality_score"])))
    gender = str(raw.get("gender") or "ALL").strip().upper()
    if gender not in {"ALL", "MALE", "FEMALE"}:
        raise AffiliateError("Eligibility gender is invalid", "INVALID_ELIGIBILITY")
    if gender != "ALL":
        result["gender"] = gender
    return result


def eligible(task: dict[str, Any], user_id: str, device_id: str, quality_score: int = 100, device_groups: set[str] | None = None, gender: str = "") -> bool:
    rule = task.get("eligibility") or {"type": "all"}
    if quality_score < int(rule.get("minimum_quality_score", 0)):
        return False
    if rule.get("gender") and str(gender).upper() != str(rule["gender"]).upper():
        return False
    kind, ids = rule.get("type", "all"), set(rule.get("ids") or [])
    return kind == "all" or (kind == "devices" and device_id in ids) or (kind == "users" and user_id in ids) or (kind == "groups" and bool(ids & (device_groups or set())))


def _sequence_platform(task: dict[str, Any]) -> str:
    """Return a stable platform key for independent task-feed queues.

    Prefer the destination host, but imports can use shortened or otherwise
    opaque URLs. In that case use the task's labels and copy before falling
    back to its category, so unrelated video platforms do not collapse into a
    single queue just because they share a category.
    """
    host = (urlparse(str(task.get("action_url") or "")).hostname or "").lower().removeprefix("www.").removeprefix("m.")
    if host == "youtu.be" or host == "youtube.com" or host.endswith(".youtube.com") or host == "youtube-nocookie.com":
        return "youtube"
    if host == "facebook.com" or host.endswith(".facebook.com") or host in {"fb.watch", "fb.me"}:
        return "facebook"
    if host == "instagram.com" or host.endswith(".instagram.com") or host == "ig.me":
        return "instagram"
    if host == "tiktok.com" or host.endswith(".tiktok.com"):
        return "tiktok"
    text = " ".join(str(task.get(key) or "") for key in ("title", "category_id", "description", "instructions")).casefold()
    for platform, aliases in (
        ("youtube", ("youtube", "youtu.be")),
        ("facebook", ("facebook", "fb.watch", "fb.me")),
        ("instagram", ("instagram", "ig.me")),
        ("tiktok", ("tiktok",)),
    ):
        if any(alias in text for alias in aliases):
            return platform
    return f"category:{task.get('category_id', '')}"


def _dt(value: datetime | str | None) -> str | None:
    if value is None:
        return None
    return value.isoformat() + ("Z" if isinstance(value, datetime) and value.tzinfo is None else "") if isinstance(value, datetime) else value


class AffiliateService:
    def __init__(self, db: Session | RedisStore):
        self.db = db
        self.redis = isinstance(db, RedisStore)

    def _key(self, kind: str, entity_id: str = "") -> str:
        base = self.db._key(f"affiliate:{kind}")  # type: ignore[attr-defined]
        return f"{base}:{entity_id}" if entity_id else base

    def _rget(self, kind: str, entity_id: str) -> dict[str, Any] | None:
        return self.db._get(self._key(kind, entity_id))  # type: ignore[attr-defined]

    def _rsave(self, kind: str, entity_id: str, value: dict[str, Any]) -> None:
        self.db._pipeline([  # type: ignore[attr-defined]
            ["SET", self._key(kind, entity_id), json.dumps(value, separators=(",", ":"))],
            ["SADD", self._key(f"{kind}:ids"), entity_id],
        ])

    def _rlist(self, kind: str) -> list[dict[str, Any]]:
        ids = self.db._cmd("SMEMBERS", self._key(f"{kind}:ids")) or []  # type: ignore[attr-defined]
        if not ids:
            return []
        raw_rows = self.db._cmd("MGET", *(self._key(kind, str(entity_id)) for entity_id in ids)) or []  # type: ignore[attr-defined]
        return [json.loads(raw) for raw in raw_rows if raw]

    def _version(self) -> int:
        if self.redis:
            return int(self.db._cmd("GET", self._key("version")) or 0)  # type: ignore[attr-defined]
        return int(self.db.query(func.max(AffiliateTask.revision)).scalar() or 0)  # type: ignore[union-attr]

    def _next_version(self) -> int:
        if self.redis:
            return int(self.db._cmd("INCR", self._key("version")))  # type: ignore[attr-defined]
        return self._version() + 1

    def _audit(self, action: str, entity_type: str, entity_id: str, actor: str, detail: dict[str, Any] | None = None) -> None:
        event = {"event_id": str(uuid.uuid4()), "action": action, "entity_type": entity_type,
                 "entity_id": entity_id, "actor": actor, "detail": detail or {}, "created_at": now_iso()}
        if self.redis:
            self._rsave("audit", event["event_id"], event)
        else:
            self.db.add(AffiliateAuditEvent(event_id=event["event_id"], action=action, entity_type=entity_type,
                                            entity_id=entity_id, actor=actor, detail_json=json.dumps(event["detail"])))  # type: ignore[union-attr]

    def record_audit(self, action: str, entity_type: str, entity_id: str, actor: str, detail: dict[str, Any] | None = None) -> None:
        self._audit(action, entity_type, entity_id, actor, detail)
        self._commit()

    def seed_demo(self) -> None:
        definitions = {
            "facebook": ("Facebook", ["Content Review", "Page QA", "Public Link Verification"]),
            "instagram": ("Instagram", ["Reel Content Review", "Caption QA", "Public Profile Link Verification"]),
            "youtube": ("YouTube", ["Video Review + Quiz", "Metadata QA", "Subtitle/Caption Review", "Public Link Verification"]),
        }
        if self.redis:
            if self.db._cmd("SET", self._key("seed:v1"), "1", "NX") is None:  # type: ignore[attr-defined]
                return
            for category_id, (name, templates) in definitions.items():
                self._rsave("category", category_id, {"category_id": category_id, "name": name,
                    "description": f"Legitimate {name} content review, QA, and public-link verification.", "editable": True, "enabled": True, "created_at": now_iso()})
                for index, template_name in enumerate(templates):
                    template_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"affiliate:{category_id}:{template_name}"))
                    schema = self._seed_schema(template_name)
                    self._rsave("template", template_id, {"template_id": template_id, "category_id": category_id,
                        "name": template_name, "description": f"Review and report quality findings for {template_name.lower()}.",
                        "field_schema": schema, "default_reward": "0.5", "verification_mode": "MANUAL", "editable": True, "created_at": now_iso()})
                self._seed_task_redis(category_id, name, templates[0])
            return
        if self.db.query(AffiliateCategory).count():  # type: ignore[union-attr]
            return
        for category_id, (name, templates) in definitions.items():
            self.db.add(AffiliateCategory(category_id=category_id, name=name,
                                           description=f"Legitimate {name} content review, QA, and public-link verification.", editable=True))  # type: ignore[union-attr]
            for template_name in templates:
                template_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"affiliate:{category_id}:{template_name}"))
                self.db.add(AffiliateTaskTemplate(template_id=template_id, category_id=category_id, name=template_name,
                    description=f"Review and report quality findings for {template_name.lower()}.",
                    field_schema_json=json.dumps(self._seed_schema(template_name)), default_reward_milli=500,
                    verification_mode="MANUAL", editable=True))  # type: ignore[union-attr]
            task_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"affiliate:demo-task:{category_id}"))
            self.db.add(AffiliateTask(task_id=task_id, category_id=category_id, title=f"{name} {templates[0]}",
                description=f"Safe demo task for {name} quality review.", instructions="Review the provided public content and submit clear QA findings.",
                reward_milli=500, status="PUBLISHED", priority=10, eligibility_json='{"type":"all"}', repeat_limit=1,
                field_schema_json=json.dumps(self._seed_schema(templates[0])), verification_mode="MANUAL", created_by="system", revision=self._next_version()))  # type: ignore[union-attr]
        self._audit("DEMO_SEEDED", "system", "v1", "system")
        self.db.commit()  # type: ignore[union-attr]

    def clear_previous_catalog_once(self) -> None:
        """One-time Affiliate redesign cleanup while preserving submissions and coin history."""
        if not self.redis:
            return
        marker = self._key("catalog-cleanup", "owner-requested-clean-slate-v2")
        if self.db._cmd("GET", marker):  # type: ignore[attr-defined]
            return
        cleanup_commands: list[list[str]] = []
        for kind in ("task", "template", "category"):
            for value in self._rlist(kind):
                entity_id = str(value.get(f"{kind}_id") or "")
                if entity_id:
                    cleanup_commands.append(["DEL", self._key(kind, entity_id)])
                    cleanup_commands.append(["SREM", self._key(f"{kind}:ids"), entity_id])
        if cleanup_commands:
            self.db._pipeline(cleanup_commands)  # type: ignore[attr-defined]
        self._next_version()
        self._audit("CATALOG_CLEARED", "catalog", "owner-requested-clean-slate-v2", "system", {"history_preserved": True})
        self.db._cmd("SET", marker, "1")  # type: ignore[attr-defined]

    @staticmethod
    def _seed_schema(name: str) -> list[dict[str, Any]]:
        return validate_field_schema([
            {"id": "public_url", "type": "url", "label": "Public content URL", "required": True},
            {"id": "review_note", "type": "long_text", "label": "QA review notes", "required": True, "min_length": 10, "max_length": 1000},
            {"id": "quality", "type": "dropdown", "label": "Quality result", "required": True, "choices": ["Pass", "Needs improvement", "Blocked"]},
            {"id": "screenshot", "type": "image", "label": "Optional supporting screenshot", "required": False, "max_size_mb": 10},
            {"id": "context", "type": "note", "label": f"Only review content you are authorized to access for {name}."},
        ])

    def _seed_task_redis(self, category_id: str, name: str, template_name: str) -> None:
        task_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"affiliate:demo-task:{category_id}"))
        revision = self._next_version()
        self._rsave("task", task_id, {"task_id": task_id, "campaign_id": None, "template_id": None,
            "category_id": category_id, "title": f"{name} {template_name}", "description": f"Safe demo task for {name} quality review.",
            "instructions": "Review the provided public content and submit clear QA findings.", "reward": "0.5", "status": "PUBLISHED",
            "priority": 10, "start_at": None, "end_at": None, "eligibility": {"type": "all"}, "repeat_limit": 1,
            "max_submissions": None, "field_schema": self._seed_schema(template_name), "verification_mode": "MANUAL",
            "created_by": "system", "revision": revision, "created_at": now_iso(), "updated_at": now_iso()})

    def categories(self) -> list[dict[str, Any]]:
        if self.redis:
            rows = self._rlist("category")
            for row in rows: row.setdefault("enabled", True)
            return sorted(rows, key=lambda row: row["name"])
        return [{"category_id": row.category_id, "name": row.name, "description": row.description, "editable": row.editable, "enabled": row.enabled}
                for row in self.db.query(AffiliateCategory).order_by(AffiliateCategory.name).all()]  # type: ignore[union-attr]

    def templates(self) -> list[dict[str, Any]]:
        if self.redis:
            return sorted(self._rlist("template"), key=lambda row: (row["category_id"], row["name"]))
        return [{"template_id": row.template_id, "category_id": row.category_id, "name": row.name,
                 "description": row.description, "field_schema": json.loads(row.field_schema_json),
                 "default_reward": milli_to_coins(row.default_reward_milli), "verification_mode": row.verification_mode, "editable": row.editable}
                for row in self.db.query(AffiliateTaskTemplate).order_by(AffiliateTaskTemplate.category_id, AffiliateTaskTemplate.name).all()]  # type: ignore[union-attr]

    def create_category(self, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        name = _safe_label(raw.get("name"), "Category name", 80, True)
        category_id = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:56] or str(uuid.uuid4())
        value = {"category_id": category_id, "name": name, "description": _safe_label(raw.get("description"), "Description", 2000), "editable": True, "enabled": True, "created_at": now_iso()}
        if self.redis: self._rsave("category", category_id, value)
        else:
            if self.db.get(AffiliateCategory, category_id): raise AffiliateError("Category already exists", "DUPLICATE", 409)  # type: ignore[union-attr]
            self.db.add(AffiliateCategory(category_id=category_id, name=name, description=value["description"], editable=True))  # type: ignore[union-attr]
        self._audit("CATEGORY_CREATED", "category", category_id, actor); self._commit(); return value

    def update_category(self, category_id: str, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        value = next((row for row in self.categories() if row["category_id"] == category_id), None)
        if not value: raise AffiliateError("Category not found", "NOT_FOUND", 404)
        value["name"] = _safe_label(raw.get("name", value["name"]), "Category name", 80, True)
        value["description"] = _safe_label(raw.get("description", value.get("description", "")), "Description", 2000)
        if "enabled" in raw: value["enabled"] = bool(raw["enabled"])
        if self.redis: self._rsave("category", category_id, value)
        else:
            row = self.db.get(AffiliateCategory, category_id); row.name, row.description, row.enabled = value["name"], value["description"], value["enabled"]
        self._audit("CATEGORY_UPDATED", "category", category_id, actor); self._commit(); return value

    def delete_category(self, category_id: str, actor: str) -> None:
        if any(row["category_id"] == category_id for row in self.tasks()) or any(row["category_id"] == category_id for row in self.templates()):
            raise AffiliateError("Category is referenced by a task or template and cannot be deleted", "CATEGORY_IN_USE", 409)
        if self.redis:
            self.db._cmd("DEL", self._key("category", category_id)); self.db._cmd("SREM", self._key("category:ids"), category_id)
        else:
            row = self.db.get(AffiliateCategory, category_id)
            if not row: raise AffiliateError("Category not found", "NOT_FOUND", 404)
            self.db.delete(row)
        self._audit("CATEGORY_DELETED", "category", category_id, actor); self._commit()

    def create_template(self, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        template_id = str(uuid.uuid4())
        reward = coins_to_milli(raw.get("default_reward", "0"))
        if reward < 0: raise AffiliateError("Template reward cannot be negative", "INVALID_REWARD")
        category_id = _safe_label(raw.get("category_id"), "Category", 64, True)
        if category_id not in {row["category_id"] for row in self.categories() if row.get("enabled", True)}: raise AffiliateError("Category is not enabled", "INVALID_CATEGORY")
        value = {"template_id": template_id, "category_id": category_id,
                 "name": _safe_label(raw.get("name"), "Template name", 160, True), "description": _safe_label(raw.get("description"), "Description", 5000),
                 "field_schema": validate_field_schema(raw.get("field_schema") or []), "default_reward": milli_to_coins(reward),
                 "verification_mode": "MANUAL", "editable": True, "created_at": now_iso()}
        if self.redis: self._rsave("template", template_id, value)
        else: self.db.add(AffiliateTaskTemplate(template_id=template_id, category_id=value["category_id"], name=value["name"],
            description=value["description"], field_schema_json=json.dumps(value["field_schema"]), default_reward_milli=reward,
            verification_mode="MANUAL", editable=True))  # type: ignore[union-attr]
        self._audit("TEMPLATE_CREATED", "template", template_id, actor); self._commit(); return value

    def groups(self) -> list[dict[str, Any]]:
        if self.redis: return sorted(self._rlist("group"), key=lambda row: row["name"])
        return [{"group_id": row.group_id, "name": row.name, "device_ids": json.loads(row.device_ids_json), "created_at": _dt(row.created_at)}
                for row in self.db.query(AffiliateDeviceGroup).order_by(AffiliateDeviceGroup.name).all()]  # type: ignore[union-attr]

    def create_group(self, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        group_id, stamp = str(uuid.uuid4()), now_iso()
        ids = raw.get("device_ids") or []
        if not isinstance(ids, list) or len(ids) > 5000: raise AffiliateError("Device ids are invalid", "INVALID_GROUP")
        value = {"group_id": group_id, "name": _safe_label(raw.get("name"), "Group name", 120, True),
                 "device_ids": sorted({str(item)[:64] for item in ids if str(item).strip()}), "created_by": actor, "created_at": stamp, "updated_at": stamp}
        if self.redis: self._rsave("group", group_id, value)
        else: self.db.add(AffiliateDeviceGroup(group_id=group_id, name=value["name"], device_ids_json=json.dumps(value["device_ids"]), created_by=actor))  # type: ignore[union-attr]
        self._audit("DEVICE_GROUP_CREATED", "device_group", group_id, actor); self._commit(); return value

    def _groups_for_device(self, device_id: str) -> set[str]:
        return {row["group_id"] for row in self.groups() if device_id in row.get("device_ids", [])}

    def create_campaign(self, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        campaign_id, stamp = str(uuid.uuid4()), now_iso()
        value = {"campaign_id": campaign_id, "name": _safe_label(raw.get("name"), "Campaign name", 160, True),
                 "description": _safe_label(raw.get("description"), "Description", 5000), "status": "DRAFT",
                 "start_at": raw.get("start_at") or None, "end_at": raw.get("end_at") or None,
                 "eligibility": normalize_eligibility(raw.get("eligibility")), "notes": _safe_label(raw.get("notes"), "Notes", 5000),
                 "created_by": actor, "created_at": stamp, "updated_at": stamp}
        if self.redis:
            self._rsave("campaign", campaign_id, value); self._audit("CAMPAIGN_CREATED", "campaign", campaign_id, actor)
        else:
            self.db.add(AffiliateCampaign(campaign_id=campaign_id, name=value["name"], description=value["description"],
                status="DRAFT", start_at=value["start_at"], end_at=value["end_at"], eligibility_json=json.dumps(value["eligibility"]),
                notes=value["notes"], created_by=actor)); self._audit("CAMPAIGN_CREATED", "campaign", campaign_id, actor); self.db.commit()  # type: ignore[union-attr]
        return value

    def campaigns(self) -> list[dict[str, Any]]:
        if self.redis: return sorted(self._rlist("campaign"), key=lambda row: row["created_at"], reverse=True)
        return [self._campaign(row) for row in self.db.query(AffiliateCampaign).order_by(AffiliateCampaign.created_at.desc()).all()]  # type: ignore[union-attr]

    def campaign_status(self, campaign_id: str, status: str, actor: str) -> dict[str, Any]:
        status = status.upper()
        if status not in TASK_STATES - {"DRAFT"}: raise AffiliateError("Invalid campaign state", "INVALID_STATE")
        if self.redis:
            value = self._rget("campaign", campaign_id)
            if not value: raise AffiliateError("Campaign not found", "NOT_FOUND", 404)
            value.update(status=status, updated_at=now_iso()); self._rsave("campaign", campaign_id, value)
        else:
            row = self.db.get(AffiliateCampaign, campaign_id)  # type: ignore[union-attr]
            if not row: raise AffiliateError("Campaign not found", "NOT_FOUND", 404)
            row.status, row.updated_at = status, utcnow(); value = self._campaign(row)
        self._audit(f"CAMPAIGN_{status}", "campaign", campaign_id, actor); self._commit(); return value

    def create_task(self, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        task_id, stamp, revision = str(uuid.uuid4()), now_iso(), self._next_version()
        fields = validate_field_schema(raw.get("field_schema") or raw.get("fields") or [])
        reward = coins_to_milli(raw.get("reward", "0"))
        if reward < 0: raise AffiliateError("Task reward cannot be negative", "INVALID_REWARD")
        repeat_limit = max(1, min(1000, int(raw.get("repeat_limit") or 1)))
        max_submissions = raw.get("max_submissions")
        max_submissions = max(1, int(max_submissions)) if max_submissions not in (None, "") else None
        task_type, action_url, action_duration_seconds = normalize_task_action(raw)
        sequence_id = str(raw.get("sequence_id") or "").strip() or None
        if sequence_id and not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", sequence_id):
            raise AffiliateError("Task sequence id is invalid", "INVALID_SEQUENCE")
        sequence_order = int(raw.get("sequence_order") or 0)
        if sequence_id and not (1 <= sequence_order <= 10_000):
            raise AffiliateError("Task sequence order is invalid", "INVALID_SEQUENCE")
        if not sequence_id:
            sequence_order = 0
        category_id = _safe_label(raw.get("category_id"), "Category", 64, True)
        campaign_id, template_id = raw.get("campaign_id") or None, raw.get("template_id") or None
        if category_id not in {row["category_id"] for row in self.categories() if row.get("enabled", True)}: raise AffiliateError("Category is not enabled", "INVALID_CATEGORY")
        if campaign_id and campaign_id not in {row["campaign_id"] for row in self.campaigns()}: raise AffiliateError("Campaign not found", "INVALID_CAMPAIGN")
        if template_id and template_id not in {row["template_id"] for row in self.templates()}: raise AffiliateError("Template not found", "INVALID_TEMPLATE")
        value = {"task_id": task_id, "campaign_id": campaign_id, "template_id": template_id,
            "category_id": category_id,
            "title": _safe_label(raw.get("title"), "Task title", 200, True), "description": _safe_label(raw.get("description"), "Description", 5000),
            "instructions": _safe_label(raw.get("instructions"), "Instructions", 10000, True), "reward": milli_to_coins(reward), "status": "DRAFT",
            "task_type": task_type, "action_url": action_url, "action_duration_seconds": action_duration_seconds,
            "sequence_id": sequence_id, "sequence_order": sequence_order,
            "priority": max(0, min(100, int(raw.get("priority") or 0))), "start_at": raw.get("start_at") or None, "end_at": raw.get("end_at") or None,
            "eligibility": normalize_eligibility(raw.get("eligibility")), "repeat_limit": repeat_limit, "max_submissions": max_submissions,
            "field_schema": fields, "verification_mode": "MANUAL", "created_by": actor, "revision": revision, "created_at": stamp, "updated_at": stamp}
        if self.redis:
            self._rsave("task", task_id, value); self._audit("TASK_CREATED", "task", task_id, actor)
        else:
            self.db.add(AffiliateTask(task_id=task_id, campaign_id=value["campaign_id"], template_id=value["template_id"], category_id=value["category_id"],
                title=value["title"], description=value["description"], instructions=value["instructions"], reward_milli=reward, status="DRAFT",
                task_type=task_type, action_url=action_url, action_duration_seconds=action_duration_seconds,
                sequence_id=sequence_id, sequence_order=sequence_order,
                priority=value["priority"], start_at=value["start_at"], end_at=value["end_at"], eligibility_json=json.dumps(value["eligibility"]),
                repeat_limit=repeat_limit, max_submissions=max_submissions, field_schema_json=json.dumps(fields), verification_mode="MANUAL", created_by=actor, revision=revision))  # type: ignore[union-attr]
            self._audit("TASK_CREATED", "task", task_id, actor); self.db.commit()  # type: ignore[union-attr]
        return value

    def import_published_sequence(self, rows: list[dict[str, Any]], actor: str) -> list[dict[str, Any]]:
        """Validate and persist a complete link sequence with one Redis pipeline.

        Production imports previously performed hundreds of REST round trips and could be cut off
        by Render after only the first task. This path validates everything before writing, then
        publishes the entire sequence atomically from the dashboard's point of view.
        """
        if not self.redis:
            created: list[dict[str, Any]] = []
            for raw in rows:
                value = self.create_task(raw, actor)
                created.append(self.task_status(value["task_id"], "PUBLISHED", actor))
            return created
        if not rows:
            raise AffiliateError("A task sequence cannot be empty", "INVALID_IMPORT")
        category_ids = {row["category_id"] for row in self.categories() if row.get("enabled", True)}
        prepared: list[dict[str, Any]] = []
        stamp = now_iso()
        final_revision = int(self.db._cmd("INCRBY", self._key("version"), str(len(rows))))  # type: ignore[attr-defined]
        first_revision = final_revision - len(rows) + 1
        for offset, raw in enumerate(rows):
            category_id = _safe_label(raw.get("category_id"), "Category", 64, True)
            if category_id not in category_ids:
                raise AffiliateError("Category is not enabled", "INVALID_CATEGORY")
            if raw.get("campaign_id") or raw.get("template_id"):
                raise AffiliateError("Imported sequences cannot use campaigns or templates", "INVALID_IMPORT")
            fields = validate_field_schema(raw.get("field_schema") or raw.get("fields") or [])
            reward = coins_to_milli(raw.get("reward", "0"))
            if reward < 0:
                raise AffiliateError("Task reward cannot be negative", "INVALID_REWARD")
            repeat_limit = max(1, min(1000, int(raw.get("repeat_limit") or 1)))
            max_submissions = raw.get("max_submissions")
            max_submissions = max(1, int(max_submissions)) if max_submissions not in (None, "") else None
            task_type, action_url, action_duration_seconds = normalize_task_action(raw)
            sequence_id = str(raw.get("sequence_id") or "").strip()
            sequence_order = int(raw.get("sequence_order") or 0)
            if not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", sequence_id) or not (1 <= sequence_order <= 10_000):
                raise AffiliateError("Task sequence is invalid", "INVALID_SEQUENCE")
            task_id = str(uuid.uuid4())
            prepared.append({
                "task_id": task_id, "campaign_id": None, "template_id": None, "category_id": category_id,
                "title": _safe_label(raw.get("title"), "Task title", 200, True),
                "description": _safe_label(raw.get("description"), "Description", 5000),
                "instructions": _safe_label(raw.get("instructions"), "Instructions", 10000, True),
                "reward": milli_to_coins(reward), "status": "PUBLISHED", "task_type": task_type,
                "action_url": action_url, "action_duration_seconds": action_duration_seconds,
                "sequence_id": sequence_id, "sequence_order": sequence_order,
                "priority": max(0, min(100, int(raw.get("priority") or 0))),
                "start_at": raw.get("start_at") or None, "end_at": raw.get("end_at") or None,
                "eligibility": normalize_eligibility(raw.get("eligibility")), "repeat_limit": repeat_limit,
                "max_submissions": max_submissions, "field_schema": fields, "verification_mode": "MANUAL",
                "created_by": actor, "revision": first_revision + offset, "created_at": stamp, "updated_at": stamp,
            })
        commands: list[list[str]] = []
        for value in prepared:
            commands.extend([
                ["SET", self._key("task", value["task_id"]), json.dumps(value, separators=(",", ":"))],
                ["SADD", self._key("task:ids"), value["task_id"]],
            ])
            event = {"event_id": str(uuid.uuid4()), "action": "TASK_PUBLISHED", "entity_type": "task",
                     "entity_id": value["task_id"], "actor": actor, "detail": {"imported": True}, "created_at": stamp}
            commands.extend([
                ["SET", self._key("audit", event["event_id"]), json.dumps(event, separators=(",", ":"))],
                ["SADD", self._key("audit:ids"), event["event_id"]],
            ])
        self.db._pipeline(commands)  # type: ignore[attr-defined]
        return prepared

    def tasks(self, status: str | None = None, category: str | None = None) -> list[dict[str, Any]]:
        if self.redis: rows = self._rlist("task")
        else: rows = [self._task(row) for row in self.db.query(AffiliateTask).all()]  # type: ignore[union-attr]
        if status: rows = [row for row in rows if row["status"] == status.upper()]
        if category: rows = [row for row in rows if row["category_id"] == category]
        return sorted(rows, key=lambda row: (-int(row.get("priority", 0)), row.get("updated_at") or ""), reverse=False)

    def task(self, task_id: str) -> dict[str, Any]:
        if self.redis: value = self._rget("task", task_id)
        else:
            row = self.db.get(AffiliateTask, task_id)  # type: ignore[union-attr]
            value = self._task(row) if row else None
        if not value: raise AffiliateError("Task not found", "NOT_FOUND", 404)
        return value

    def update_task(self, task_id: str, raw: dict[str, Any], actor: str) -> dict[str, Any]:
        value = self.task(task_id)
        if value["status"] in {"CLOSED", "ARCHIVED"}: raise AffiliateError("Closed or archived tasks cannot be edited", "INVALID_STATE", 409)
        if "title" in raw: value["title"] = _safe_label(raw.get("title"), "Task title", 200, True)
        if "description" in raw: value["description"] = _safe_label(raw.get("description"), "Description", 5000)
        if "instructions" in raw: value["instructions"] = _safe_label(raw.get("instructions"), "Instructions", 10000, True)
        if any(key in raw for key in ("task_type", "action_url", "action_duration_seconds")):
            merged = {**value, **raw}
            value["task_type"], value["action_url"], value["action_duration_seconds"] = normalize_task_action(merged)
        if "reward" in raw:
            reward = coins_to_milli(raw["reward"])
            if reward < 0: raise AffiliateError("Task reward cannot be negative", "INVALID_REWARD")
            value["reward"] = milli_to_coins(reward)
        if "field_schema" in raw: value["field_schema"] = validate_field_schema(raw["field_schema"])
        if "eligibility" in raw: value["eligibility"] = normalize_eligibility(raw["eligibility"])
        if "priority" in raw: value["priority"] = max(0, min(100, int(raw["priority"])))
        value["revision"], value["updated_at"] = self._next_version(), now_iso()
        if self.redis: self._rsave("task", task_id, value)
        else:
            row = self.db.get(AffiliateTask, task_id)  # type: ignore[union-attr]
            row.title, row.description, row.instructions = value["title"], value["description"], value["instructions"]
            row.task_type, row.action_url, row.action_duration_seconds = value.get("task_type", "FORM"), value.get("action_url"), int(value.get("action_duration_seconds", 0))
            row.reward_milli, row.field_schema_json, row.eligibility_json = coins_to_milli(value["reward"]), json.dumps(value["field_schema"]), json.dumps(value["eligibility"])
            row.priority, row.revision, row.updated_at = value["priority"], value["revision"], utcnow()
        self._audit("TASK_UPDATED", "task", task_id, actor); self._commit(); return value

    def delete_task(self, task_id: str, actor: str) -> None:
        value = self.task(task_id)
        if self.submissions(task_id=task_id, limit=1):
            raise AffiliateError("Tasks with submissions cannot be deleted; archive it instead", "TASK_IN_USE", 409)
        if self.redis:
            self.db._cmd("DEL", self._key("task", task_id))  # type: ignore[attr-defined]
            self.db._cmd("SREM", self._key("task:ids"), task_id)  # type: ignore[attr-defined]
            self._next_version()
        else:
            row = self.db.get(AffiliateTask, task_id)  # type: ignore[union-attr]
            if row: self.db.delete(row)  # type: ignore[union-attr]
        self._audit("TASK_DELETED", "task", task_id, actor, {"title": value["title"]})
        self._commit()

    def task_status(self, task_id: str, status: str, actor: str) -> dict[str, Any]:
        status = status.upper()
        if status not in TASK_STATES: raise AffiliateError("Invalid task state", "INVALID_STATE")
        value = self.task(task_id)
        current = value["status"]
        allowed = {"DRAFT": {"PUBLISHED", "ARCHIVED"}, "PUBLISHED": {"PAUSED", "CLOSED", "ARCHIVED"},
                   "PAUSED": {"PUBLISHED", "CLOSED", "ARCHIVED"}, "CLOSED": {"ARCHIVED"}, "ARCHIVED": set()}
        if status != current and status not in allowed[current]:
            raise AffiliateError(f"Cannot move task from {current} to {status}", "INVALID_STATE", 409)
        revision, stamp = self._next_version(), now_iso()
        value.update(status=status, revision=revision, updated_at=stamp)
        if self.redis: self._rsave("task", task_id, value)
        else:
            row = self.db.get(AffiliateTask, task_id)  # type: ignore[union-attr]
            row.status, row.revision, row.updated_at = status, revision, utcnow()
        self._audit(f"TASK_{status}", "task", task_id, actor); self._commit(); return value

    def ensure_user(self, user_id: str, device_id: str, name: str) -> dict[str, Any]:
        if self.redis:
            value = self._rget("user", user_id)
            if not value:
                value = {"user_id": user_id, "display_name": name[:128] or "Affiliate worker", "device_id": device_id,
                         "status": "ACTIVE", "balance_milli": 0, "quality_score": 100, "created_at": now_iso(), "updated_at": now_iso()}
            else:
                value.update(device_id=device_id, updated_at=now_iso())
                if name.strip() and name != "Affiliate worker": value["display_name"] = name[:128]
            self._rsave("user", user_id, value); return value
        row = self.db.get(AffiliateUser, user_id)  # type: ignore[union-attr]
        if row is None:
            row = AffiliateUser(user_id=user_id, display_name=name[:128] or "Affiliate worker", device_id=device_id)
            self.db.add(row)  # type: ignore[union-attr]
        else:
            row.device_id, row.updated_at = device_id, utcnow()
            if name.strip() and name != "Affiliate worker": row.display_name = name[:128]
        self.db.commit()  # type: ignore[union-attr]
        return self._user(row)

    def sync(self, user_id: str, device_id: str, name: str, cursor: int = 0) -> dict[str, Any]:
        user = self.ensure_user(user_id, device_id, name)
        groups = self._groups_for_device(device_id)
        current_cursor = self._version()
        full = cursor <= 0 or cursor > current_cursor
        submissions = self.submissions(user_id=user_id, device_id=device_id)
        submitted_task_ids = {row["task_id"] for row in submissions}
        # Only published entries participate in sequence progression. A paused,
        # closed, or archived first entry must not hide a later live task.
        candidates = [row for row in self.tasks() if row["status"] == "PUBLISHED"
                      and eligible(row, user_id, device_id, int(user.get("quality_score", 100)), groups, str(user.get("gender") or ""))]
        sequence_heads: dict[tuple[str, str], dict[str, Any]] = {}
        unsequenced: list[dict[str, Any]] = []
        for row in candidates:
            if row["task_id"] in submitted_task_ids:
                continue
            sequence_id = row.get("sequence_id")
            if not sequence_id:
                unsequenced.append(row)
                continue
            sequence_key = (sequence_id, _sequence_platform(row))
            current_task = sequence_heads.get(sequence_key)
            if current_task is None or int(row.get("sequence_order", 0)) < int(current_task.get("sequence_order", 0)):
                sequence_heads[sequence_key] = row

        # A platform has one queue across all imports, not one visible item per
        # batch. Otherwise two JSON batches can surface duplicate TikTok cards
        # while hiding YouTube/Facebook tasks later in the same platform feed.
        platform_heads: dict[str, dict[str, Any]] = {}
        for row in [*sequence_heads.values(), *unsequenced]:
            platform = _sequence_platform(row)
            current_task = platform_heads.get(platform)
            queue_order = (str(row.get("created_at") or ""), int(row.get("sequence_order", 0)), row["task_id"])
            current_order = (str(current_task.get("created_at") or ""), int(current_task.get("sequence_order", 0)), current_task["task_id"]) if current_task else None
            if current_order is None or queue_order < current_order:
                platform_heads[platform] = row
        visible_ids = {row["task_id"] for row in platform_heads.values()}
        task_rows = [row for row in candidates if row["task_id"] in visible_ids
                     and (full or int(row.get("revision", 0)) > cursor)]
        ledger = self.ledger(user_id=user_id, limit=100)
        dashboard_reward_claimed = any(row.get("entry_type") == "DASHBOARD_BONUS" for row in ledger)
        return {"cursor": current_cursor, "categories": [row for row in self.categories() if row.get("enabled", True)], "tasks": task_rows, "submissions": submissions,
                "wallet": {"balance": milli_to_coins(int(user.get("balance_milli", 0))), "ledger": ledger,
                           "dashboard_reward_claimed": dashboard_reward_claimed},
                "full": full}

    def submit(self, task_id: str, client_submission_id: str, user_id: str, device_id: str, raw_values: Any) -> dict[str, Any]:
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", client_submission_id):
            raise AffiliateError("Client submission id is invalid", "INVALID_SUBMISSION")
        task = self.task(task_id)
        if task["status"] != "PUBLISHED": raise AffiliateError("Task is not accepting submissions", "TASK_NOT_OPEN", 409)
        user = self.ensure_user(user_id, device_id, "Affiliate worker")
        if not eligible(task, user_id, device_id, int(user.get("quality_score", 100)), self._groups_for_device(device_id), str(user.get("gender") or "")):
            raise AffiliateError("This device is not eligible for the task", "NOT_ELIGIBLE", 403)
        sequence_id = task.get("sequence_id")
        if sequence_id:
            submitted_ids = {row["task_id"] for row in self.submissions(user_id=user_id, device_id=device_id)}
            platform = _sequence_platform(task)
            remaining = sorted(
                (row for row in self.tasks(status="PUBLISHED")
                 if row.get("sequence_id") == sequence_id
                 and _sequence_platform(row) == platform
                 and row["task_id"] not in submitted_ids),
                key=lambda row: int(row.get("sequence_order", 0)),
            )
            if not remaining or remaining[0]["task_id"] != task_id:
                raise AffiliateError("This sequence task is still locked", "TASK_LOCKED", 409)
        values, proofs = validate_submission(task["field_schema"], raw_values)
        existing = self._submission_by_client(client_submission_id)
        if existing: return existing
        all_prior = self.submissions(task_id=task_id, user_id=user_id, device_id=device_id)
        revision = next((row for row in all_prior if row["status"] == "REVISION_REQUESTED"), None)
        if revision:
            revision.update(client_submission_id=client_submission_id, status="PENDING_REVIEW", submitted_fields=values,
                            proof_metadata=proofs, review_reason=None, reviewed_by=None, updated_at=now_iso())
            if self.redis:
                self.db._cmd("SET", self._key("submission:client", client_submission_id), revision["submission_id"], "NX")  # type: ignore[attr-defined]
                self._rsave("submission", revision["submission_id"], revision)
            else:
                row = self.db.get(AffiliateSubmission, revision["submission_id"])  # type: ignore[union-attr]
                row.client_submission_id, row.status = client_submission_id, "PENDING_REVIEW"
                row.submitted_fields_json, row.proof_metadata_json = json.dumps(values), json.dumps(proofs)
                row.review_reason, row.reviewed_by, row.updated_at = None, None, utcnow()
            self._audit("SUBMISSION_REVISED", "submission", revision["submission_id"], user_id, {"task_id": task_id})
            self._commit()
            return revision
        prior = all_prior
        if len(prior) >= int(task.get("repeat_limit") or 1):
            raise AffiliateError("Submission limit reached for this task", "DUPLICATE_SUBMISSION", 409)
        if task.get("max_submissions") and len(self.submissions(task_id=task_id)) >= int(task["max_submissions"]):
            raise AffiliateError("Task submission limit has been reached", "TASK_LIMIT_REACHED", 409)
        submission_id, stamp = str(uuid.uuid4()), now_iso()
        value = {"submission_id": submission_id, "client_submission_id": client_submission_id, "task_id": task_id,
                 "task_title": task["title"], "reward": task["reward"], "user_id": user_id, "device_id": device_id,
                 "attempt": len(prior) + 1, "status": "PENDING_REVIEW", "submitted_fields": values,
                 "proof_metadata": proofs, "reviewed_by": None, "review_reason": None, "created_at": stamp, "updated_at": stamp}
        if self.redis:
            client_key = self._key("submission:client", client_submission_id)
            attempt_key = self._key("submission:attempt", f"{task_id}:{user_id}:{device_id}:{value['attempt']}")
            claimed = self.db._cmd("SET", client_key, submission_id, "NX")  # type: ignore[attr-defined]
            if claimed is None:
                found = self._submission_by_client(client_submission_id)
                if found: return found
                raise AffiliateError("Submission is already being processed", "DUPLICATE_SUBMISSION", 409)
            if self.db._cmd("SET", attempt_key, submission_id, "NX") is None:  # type: ignore[attr-defined]
                self.db._cmd("DEL", client_key)  # type: ignore[attr-defined]
                raise AffiliateError("Submission limit reached for this task", "DUPLICATE_SUBMISSION", 409)
            if task.get("max_submissions"):
                count_key = self._key("task:submission_count", task_id)
                total = int(self.db._cmd("INCR", count_key))  # type: ignore[attr-defined]
                if total > int(task["max_submissions"]):
                    self.db._cmd("DECR", count_key); self.db._cmd("DEL", client_key); self.db._cmd("DEL", attempt_key)  # type: ignore[attr-defined]
                    raise AffiliateError("Task submission limit has been reached", "TASK_LIMIT_REACHED", 409)
            self._rsave("submission", submission_id, value)
        else:
            self.db.add(AffiliateSubmission(submission_id=submission_id, client_submission_id=client_submission_id, task_id=task_id,
                user_id=user_id, device_id=device_id, attempt=value["attempt"], status="PENDING_REVIEW",
                submitted_fields_json=json.dumps(values), proof_metadata_json=json.dumps(proofs)))  # type: ignore[union-attr]
            try: self.db.commit()  # type: ignore[union-attr]
            except IntegrityError:
                self.db.rollback()  # type: ignore[union-attr]
                found = self._submission_by_client(client_submission_id)
                if found: return found
                raise AffiliateError("Duplicate submission", "DUPLICATE_SUBMISSION", 409)
        self._audit("SUBMISSION_CREATED", "submission", submission_id, user_id, {"task_id": task_id}); self._commit()
        return value

    def submissions(self, status: str | None = None, task_id: str | None = None, user_id: str | None = None,
                    device_id: str | None = None, campaign_id: str | None = None, date_from: str | None = None,
                    date_to: str | None = None, limit: int = 500) -> list[dict[str, Any]]:
        if self.redis: rows = self._rlist("submission")
        else: rows = [self._submission(row) for row in self.db.query(AffiliateSubmission).all()]  # type: ignore[union-attr]
        if status: rows = [row for row in rows if row["status"] == status.upper()]
        if task_id: rows = [row for row in rows if row["task_id"] == task_id]
        if user_id: rows = [row for row in rows if row["user_id"] == user_id]
        if device_id: rows = [row for row in rows if row["device_id"] == device_id]
        tasks = {row["task_id"]: row for row in self.tasks()}
        if campaign_id: rows = [row for row in rows if (tasks.get(row["task_id"]) or {}).get("campaign_id") == campaign_id]
        if date_from: rows = [row for row in rows if str(row.get("created_at", "")) >= date_from]
        if date_to: rows = [row for row in rows if str(row.get("created_at", "")) <= date_to + "T23:59:59.999999+00:00"]
        for row in rows:
            task = tasks.get(row["task_id"])
            if task:
                row.setdefault("task_title", task["title"])
                row.setdefault("reward", task["reward"])
        return sorted(rows, key=lambda row: row["created_at"], reverse=True)[:max(1, min(limit, 1000))]

    def review(self, submission_id: str, decision: str, reason: str, actor: str) -> tuple[dict[str, Any], dict[str, Any] | None]:
        decision = decision.upper()
        if decision not in {"APPROVED", "REJECTED", "REVISION_REQUESTED"}: raise AffiliateError("Invalid review decision", "INVALID_STATE")
        if decision in {"REJECTED", "REVISION_REQUESTED"} and len(reason.strip()) < 3:
            raise AffiliateError("A clear reason is required", "REASON_REQUIRED")
        submission = self._submission(submission_id)
        if not submission: raise AffiliateError("Submission not found", "NOT_FOUND", 404)
        if submission["status"] == "APPROVED":
            if decision == "APPROVED": return submission, self._ledger_for_submission(submission_id)
            raise AffiliateError("An approved submission cannot be changed", "INVALID_STATE", 409)
        if submission["status"] not in {"PENDING_REVIEW", "SUBMITTED", "REVISION_REQUESTED"}:
            raise AffiliateError("Submission cannot be reviewed in its current state", "INVALID_STATE", 409)
        ledger = None
        if decision == "APPROVED":
            task = self.task(submission["task_id"])
            ledger = self._credit_approval(submission, coins_to_milli(task["reward"]), actor, reason or "Task approved")
            submission = self._submission(submission_id) or submission
        else:
            submission.update(status=decision, reviewed_by=actor, review_reason=reason.strip(), updated_at=now_iso())
            self._save_submission(submission)
        self._audit(f"SUBMISSION_{decision}", "submission", submission_id, actor, {"reason": reason[:500]}); self._commit()
        return submission, ledger

    def _credit_approval(self, submission: dict[str, Any], amount: int, actor: str, reason: str) -> dict[str, Any]:
        existing = self._ledger_for_submission(submission["submission_id"])
        if existing: return existing
        ledger_id, idem, stamp = str(uuid.uuid4()), f"approval:{submission['submission_id']}", now_iso()
        if self.redis:
            script = """
local old=redis.call('GET',KEYS[1]); if old then return redis.call('GET',KEYS[4]..':'..old) end
local s=cjson.decode(redis.call('GET',KEYS[2])); if s.status=='APPROVED' then return '' end
if ARGV[3]=='automatic-review' and s.status~='PENDING_REVIEW' then return '' end
local uraw=redis.call('GET',KEYS[3]); local u=uraw and cjson.decode(uraw) or {balance_milli=0}
u.balance_milli=(u.balance_milli or 0)+tonumber(ARGV[2]); u.updated_at=ARGV[5]
s.status='APPROVED'; s.reviewed_by=ARGV[3]; s.review_reason=ARGV[4]; s.updated_at=ARGV[5]
local l={ledger_id=ARGV[1],user_id=s.user_id,submission_id=s.submission_id,task_id=s.task_id,amount_milli=tonumber(ARGV[2]),amount=ARGV[6],entry_type='TASK_REWARD',reason=ARGV[4],actor=ARGV[3],idempotency_key=ARGV[7],balance_after_milli=u.balance_milli,balance_after=ARGV[8],created_at=ARGV[5]}
redis.call('SET',KEYS[1],ARGV[1]); redis.call('SET',KEYS[2],cjson.encode(s)); redis.call('SET',KEYS[3],cjson.encode(u)); redis.call('SET',KEYS[4]..':'..ARGV[1],cjson.encode(l)); redis.call('SADD',KEYS[5],ARGV[1]); redis.call('INCR',KEYS[6]); return cjson.encode(l)
"""
            user = self._rget("user", submission["user_id"]) or {"balance_milli": 0}
            after = int(user.get("balance_milli", 0)) + amount
            raw = self.db._cmd("EVAL", script, "6", self._key("ledger:idem", idem), self._key("submission", submission["submission_id"]),  # type: ignore[attr-defined]
                self._key("user", submission["user_id"]), self._key("ledger"), self._key("ledger:ids"), self._key("version"),
                ledger_id, str(amount), actor, reason[:500], stamp, milli_to_coins(amount), idem, milli_to_coins(after))
            if not raw:
                found = self._ledger_for_submission(submission["submission_id"])
                if found: return found
                raise AffiliateError("Approval conflict", "CONFLICT", 409)
            return json.loads(raw)
        row = self.db.get(AffiliateSubmission, submission["submission_id"])  # type: ignore[union-attr]
        user = self.db.get(AffiliateUser, submission["user_id"])  # type: ignore[union-attr]
        if user is None:
            user = AffiliateUser(user_id=submission["user_id"], display_name="Affiliate worker", device_id=submission["device_id"])
            self.db.add(user)  # type: ignore[union-attr]
        user.balance_milli += amount
        row.status, row.reviewed_by, row.review_reason, row.updated_at = "APPROVED", actor, reason[:500], utcnow()
        ledger_row = AffiliateCoinLedger(ledger_id=ledger_id, user_id=user.user_id, submission_id=row.submission_id,
            task_id=row.task_id, amount_milli=amount, entry_type="TASK_REWARD", reason=reason[:500], actor=actor,
            idempotency_key=idem, balance_after_milli=user.balance_milli)
        self.db.add(ledger_row)  # type: ignore[union-attr]
        try: self.db.commit()  # type: ignore[union-attr]
        except IntegrityError:
            self.db.rollback()  # type: ignore[union-attr]
            found = self._ledger_for_submission(submission["submission_id"])
            if found: return found
            raise
        return self._ledger(ledger_row)

    def manual_adjust(self, user_id: str, amount_raw: Any, reason: str, actor: str, idempotency_key: str | None = None,
                      entry_type: str = "MANUAL_ADJUSTMENT") -> dict[str, Any]:
        amount, reason = coins_to_milli(amount_raw), reason.strip()
        if not (3 <= len(user_id) <= 128): raise AffiliateError("A valid user id is required", "INVALID_USER")
        if amount == 0 or len(reason) < 3: raise AffiliateError("A non-zero amount and clear reason are required", "INVALID_ADJUSTMENT")
        idem = idempotency_key or f"manual:{uuid.uuid4()}"
        existing = self._ledger_by_idempotency(idem)
        if existing: return existing
        user = self.ensure_user(user_id, "manual", user_id)
        after = int(user.get("balance_milli", 0)) + amount
        ledger_id = str(uuid.uuid4())
        value = {"ledger_id": ledger_id, "user_id": user_id, "submission_id": None, "task_id": None,
                 "amount_milli": amount, "amount": milli_to_coins(amount), "entry_type": entry_type[:32], "reason": reason[:500],
                 "actor": actor, "idempotency_key": idem, "balance_after_milli": after, "balance_after": milli_to_coins(after), "created_at": now_iso()}
        if self.redis:
            if self.db._cmd("SET", self._key("ledger:idem", idem), ledger_id, "NX") is None:  # type: ignore[attr-defined]
                return self._ledger_by_idempotency(idem) or value
            user["balance_milli"], user["updated_at"] = after, now_iso(); self._rsave("user", user_id, user); self._rsave("ledger", ledger_id, value)
            account_key = self.db._key(f"affiliate:account:user:{user_id}")
            account = self.db._get(account_key)
            if account:
                account["balance_milli"], account["updated_at"] = after, user["updated_at"]
                self.db._set(account_key, account)
        else:
            row = self.db.get(AffiliateUser, user_id)  # type: ignore[union-attr]
            row.balance_milli = after
            self.db.add(AffiliateCoinLedger(ledger_id=ledger_id, user_id=user_id, amount_milli=amount, entry_type=entry_type[:32],
                reason=reason[:500], actor=actor, idempotency_key=idem, balance_after_milli=after))  # type: ignore[union-attr]
        self._audit("COIN_ADJUSTED", "user", user_id, actor, {"ledger_id": ledger_id}); self._commit(); return value

    def ledger(self, user_id: str | None = None, limit: int = 500, min_amount: int | None = None, max_amount: int | None = None) -> list[dict[str, Any]]:
        if self.redis: rows = self._rlist("ledger")
        else: rows = [self._ledger(row) for row in self.db.query(AffiliateCoinLedger).all()]  # type: ignore[union-attr]
        if user_id: rows = [row for row in rows if row["user_id"] == user_id]
        if min_amount is not None: rows = [row for row in rows if int(row.get("amount_milli", 0)) >= min_amount]
        if max_amount is not None: rows = [row for row in rows if int(row.get("amount_milli", 0)) <= max_amount]
        return sorted(rows, key=lambda row: row["created_at"], reverse=True)[:max(1, min(limit, 1000))]

    def analytics(self) -> dict[str, Any]:
        tasks, submissions, ledger = self.tasks(), self.submissions(), self.ledger(limit=10000)
        approved = sum(row["status"] == "APPROVED" for row in submissions)
        rejected = sum(row["status"] == "REJECTED" for row in submissions)
        pending = sum(row["status"] == "PENDING_REVIEW" for row in submissions)
        total = len(submissions)
        today = datetime.now(timezone.utc).date().isoformat()
        approved_today = sum(row["status"] == "APPROVED" and str(row.get("updated_at", "")).startswith(today) for row in submissions)
        rejected_today = sum(row["status"] == "REJECTED" and str(row.get("updated_at", "")).startswith(today) for row in submissions)
        coins_today = sum(max(0, int(row.get("amount_milli", 0))) for row in ledger if str(row.get("created_at", "")).startswith(today))
        category_counts: dict[str, int] = {}
        for task in tasks: category_counts[task["category_id"]] = category_counts.get(task["category_id"], 0) + 1
        return {"active_tasks": sum(row["status"] == "PUBLISHED" for row in tasks), "pending_reviews": pending,
                "approved": approved, "rejected": rejected, "completion_rate": round((approved + rejected) * 100 / total, 1) if total else 0,
                "approval_rate": round(approved * 100 / max(1, approved + rejected), 1),
                "coins_credited": milli_to_coins(sum(max(0, int(row.get("amount_milli", 0))) for row in ledger)),
                "approved_today": approved_today, "rejected_today": rejected_today, "coins_credited_today": milli_to_coins(coins_today),
                "top_categories": sorted(category_counts.items(), key=lambda item: item[1], reverse=True), "submission_count": total}

    def audit_log(self, limit: int = 200) -> list[dict[str, Any]]:
        if self.redis: rows = self._rlist("audit")
        else: rows = [{"event_id": row.event_id, "action": row.action, "entity_type": row.entity_type, "entity_id": row.entity_id,
                       "actor": row.actor, "detail": json.loads(row.detail_json), "created_at": _dt(row.created_at)}
                      for row in self.db.query(AffiliateAuditEvent).all()]  # type: ignore[union-attr]
        return sorted(rows, key=lambda row: row["created_at"], reverse=True)[:max(1, min(limit, 1000))]

    def _submission_by_client(self, client_id: str) -> dict[str, Any] | None:
        if self.redis:
            submission_id = self.db._cmd("GET", self._key("submission:client", client_id))  # type: ignore[attr-defined]
            return self._rget("submission", str(submission_id)) if submission_id else None
        row = self.db.query(AffiliateSubmission).filter(AffiliateSubmission.client_submission_id == client_id).one_or_none()  # type: ignore[union-attr]
        return self._submission(row) if row else None

    def _submission(self, value: str | AffiliateSubmission | None) -> dict[str, Any] | None:
        if isinstance(value, str):
            if self.redis: return self._rget("submission", value)
            value = self.db.get(AffiliateSubmission, value)  # type: ignore[union-attr]
        if value is None: return None
        return {"submission_id": value.submission_id, "client_submission_id": value.client_submission_id, "task_id": value.task_id,
                "user_id": value.user_id, "device_id": value.device_id, "attempt": value.attempt, "status": value.status,
                "submitted_fields": json.loads(value.submitted_fields_json), "proof_metadata": json.loads(value.proof_metadata_json),
                "reviewed_by": value.reviewed_by, "review_reason": value.review_reason, "created_at": _dt(value.created_at), "updated_at": _dt(value.updated_at)}

    def _save_submission(self, value: dict[str, Any]) -> None:
        if self.redis: self._rsave("submission", value["submission_id"], value)
        else:
            row = self.db.get(AffiliateSubmission, value["submission_id"])  # type: ignore[union-attr]
            row.status, row.reviewed_by, row.review_reason, row.updated_at = value["status"], value.get("reviewed_by"), value.get("review_reason"), utcnow()

    def _ledger_for_submission(self, submission_id: str) -> dict[str, Any] | None:
        rows = [row for row in self.ledger(limit=10000) if row.get("submission_id") == submission_id]
        return rows[0] if rows else None

    def _ledger_by_idempotency(self, idem: str) -> dict[str, Any] | None:
        rows = [row for row in self.ledger(limit=10000) if row.get("idempotency_key") == idem]
        return rows[0] if rows else None

    def _commit(self) -> None:
        if not self.redis: self.db.commit()  # type: ignore[union-attr]

    @staticmethod
    def _task(row: AffiliateTask) -> dict[str, Any]:
        return {"task_id": row.task_id, "campaign_id": row.campaign_id, "template_id": row.template_id, "category_id": row.category_id,
                "title": row.title, "description": row.description, "instructions": row.instructions, "reward": milli_to_coins(row.reward_milli),
                "task_type": row.task_type or "FORM", "action_url": row.action_url, "action_duration_seconds": row.action_duration_seconds or 0,
                "sequence_id": row.sequence_id, "sequence_order": row.sequence_order or 0,
                "status": row.status, "priority": row.priority, "start_at": row.start_at, "end_at": row.end_at,
                "eligibility": json.loads(row.eligibility_json), "repeat_limit": row.repeat_limit, "max_submissions": row.max_submissions,
                "field_schema": json.loads(row.field_schema_json), "verification_mode": row.verification_mode, "created_by": row.created_by,
                "revision": row.revision, "created_at": _dt(row.created_at), "updated_at": _dt(row.updated_at)}

    @staticmethod
    def _campaign(row: AffiliateCampaign) -> dict[str, Any]:
        return {"campaign_id": row.campaign_id, "name": row.name, "description": row.description, "status": row.status,
                "start_at": row.start_at, "end_at": row.end_at, "eligibility": json.loads(row.eligibility_json), "notes": row.notes,
                "created_by": row.created_by, "created_at": _dt(row.created_at), "updated_at": _dt(row.updated_at)}

    @staticmethod
    def _user(row: AffiliateUser) -> dict[str, Any]:
        return {"user_id": row.user_id, "display_name": row.display_name, "device_id": row.device_id, "status": row.status,
                "gender": row.gender or "",
                "balance_milli": row.balance_milli, "quality_score": row.quality_score, "registered": row.registered,
                "created_at": _dt(row.created_at), "updated_at": _dt(row.updated_at)}

    @staticmethod
    def _ledger(row: AffiliateCoinLedger) -> dict[str, Any]:
        return {"ledger_id": row.ledger_id, "user_id": row.user_id, "submission_id": row.submission_id, "task_id": row.task_id,
                "amount_milli": row.amount_milli, "amount": milli_to_coins(row.amount_milli), "entry_type": row.entry_type,
                "reason": row.reason, "actor": row.actor, "idempotency_key": row.idempotency_key,
                "balance_after_milli": row.balance_after_milli, "balance_after": milli_to_coins(row.balance_after_milli), "created_at": _dt(row.created_at)}
