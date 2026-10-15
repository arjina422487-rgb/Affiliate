from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import secrets
import uuid
from datetime import datetime, timezone
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from fastapi import FastAPI, Form, HTTPException, Request, WebSocket, WebSocketDisconnect, status
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, StreamingResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
from starlette.middleware.sessions import SessionMiddleware
from starlette.websockets import WebSocketState

from . import devices as device_ops
from . import protocol as P
from .auth import (
    LoginLimiter,
    authenticate,
    current_owner,
    ensure_csrf,
    login_owner,
    logout_owner,
    require_csrf,
)
from .affiliate import AffiliateError, AffiliateService, coins_to_milli
from .auto_review import schedule as schedule_auto_review, process_due as process_auto_reviews
from .affiliate_accounts import AffiliateAccountService
from .bootstrap import bootstrap_owner, validate_runtime_settings
from .config import Settings, get_settings
from .db import init_db, make_engine, session_factory
from .hashing import hash_password, verify_password
from .models import Owner
from .registry import CommandError, DeviceOffline, Registry
from .storage import RedisStore
from .turn import create_ice_configuration
from .util import content_disposition, is_uuid, pairing_token_entropy_ok

log = logging.getLogger("ownerlink")

BASE_DIR = Path(__file__).resolve().parent
TEMPLATE_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"


class SafeJsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        value = {
            "time": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "event": record.getMessage(),
        }
        if record.exc_info:
            value["exception"] = record.exc_info[0].__name__ if record.exc_info[0] else "Exception"
        return json.dumps(value, separators=(",", ":"), ensure_ascii=True)


def setup_logging(production: bool = False) -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    if production:
        formatter = SafeJsonFormatter()
        for handler in logging.getLogger().handlers:
            handler.setFormatter(formatter)
    logging.getLogger("uvicorn.access").setLevel(logging.INFO)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def parse_csrf_from_form(form_value: str | None, request: Request) -> None:
    provided = form_value or request.headers.get("X-CSRF-Token")
    expected = request.session.get("csrf")
    if expected and provided and secrets.compare_digest(str(expected), str(provided)):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid CSRF token")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    setup_logging(settings.is_production)
    validate_runtime_settings(settings)
    # Render production uses durable Upstash metadata. Local/test installations keep SQLite.
    if settings.uses_redis:
        store = RedisStore(settings.UPSTASH_REDIS_REST_URL, settings.UPSTASH_REDIS_REST_TOKEN)
        if not store.ping():
            raise RuntimeError("Upstash Redis did not answer PING")
        engine = None
        SessionLocal = lambda: store
    else:
        engine = make_engine(settings.DATABASE_URL)
        init_db(engine)
        SessionLocal = session_factory(engine)
    registry = Registry(settings)
    limiter = LoginLimiter(settings.LOGIN_MAX_ATTEMPTS, settings.LOGIN_WINDOW_SECONDS)
    contact_limiter = LoginLimiter(60, 60)
    templates = Jinja2Templates(directory=str(TEMPLATE_DIR))

    with SessionLocal() as db:
        bootstrap_owner(db, settings)
        # Affiliate catalog data is created by the owner; production must not recreate demo content.
        if settings.ENV.strip().lower() == "test":
            AffiliateService(db).seed_demo()
        elif settings.is_production:
            AffiliateService(db).clear_previous_catalog_once()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        log.info("Affiliate backend ready storage=%s worker_mode=single", "redis" if settings.uses_redis else "sqlite")
        async def auto_review_loop():
            cycle = 0
            while True:
                try:
                    events = await asyncio.to_thread(process_auto_reviews, SessionLocal, cycle % 10 == 0)
                    for submission, ledger in events:
                        link = registry.contacts.get(submission["user_id"]) or registry.links.get(submission["device_id"])
                        if link:
                            try:
                                await registry.send_json(link.device_id, P.envelope(P.COIN_CREDITED, link.device_id,
                                    {"submission": submission, "ledger": ledger}))
                            except DeviceOffline:
                                pass
                except Exception:
                    log.exception("Automatic review cycle failed; persistent jobs will be retried")
                cycle += 1
                await asyncio.sleep(30)
        review_worker = asyncio.create_task(auto_review_loop()) if settings.uses_redis else None
        try:
            yield
        finally:
            if review_worker:
                review_worker.cancel()
                await asyncio.gather(review_worker, return_exceptions=True)
            log.info("Affiliate backend shutting down active_devices=%d", len(registry.links))
            for link in list(registry.links.values()):
                try:
                    await link.websocket.close(code=1012, reason="service restart")
                except Exception:
                    pass

    app = FastAPI(title="Affiliate", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = settings
    app.state.registry = registry
    app.state.engine = engine
    app.state.storage_backend = "redis" if settings.uses_redis else "sqlite"
    app.state.SessionLocal = SessionLocal
    app.state.ready = True

    app.add_middleware(
        SessionMiddleware,
        secret_key=settings.OWNERLINK_SECRET_KEY,
        session_cookie="ownerlink_session",
        max_age=settings.SESSION_MAX_AGE,
        same_site="lax",
        https_only=settings.cookie_secure,
    )

    if STATIC_DIR.exists():
        app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

    def db_session() -> Session:
        return SessionLocal()

    def bearer(request: Request) -> str:
        header = request.headers.get("authorization", "")
        return header[7:].strip() if header.lower().startswith("bearer ") else ""

    def affiliate_user(request: Request):
        db = db_session()
        try: user = AffiliateAccountService(db, settings.SESSION_MAX_AGE).authenticate(bearer(request))
        finally: db.close()
        return user

    def ctx(request: Request, extra: dict | None = None) -> dict:
        data = {"request": request, "csrf_token": ensure_csrf(request), "owner_name": None}
        owner = None
        db = db_session()
        try:
            owner = current_owner(request, db)
        finally:
            db.close()
        if owner is not None:
            data["owner_name"] = owner.username
        if extra:
            data.update(extra)
        return data

    def page(request: Request, name: str, extra: dict | None = None, status_code: int = 200):
        return templates.TemplateResponse(request, name, ctx(request, extra), status_code=status_code)

    def decorate_submission_answers(service: AffiliateService, rows: list[dict]) -> list[dict]:
        """Resolve stored field IDs to the original administrator-authored question labels."""
        task_map = {task["task_id"]: task for task in service.tasks()}
        decorated: list[dict] = []
        for source in rows:
            row = dict(source)
            task = task_map.get(row.get("task_id"), {})
            schema = task.get("field_schema", [])
            row["action_url"] = task.get("action_url")
            row["task_title"] = task.get("title") or row.get("task_title")
            labels = {field.get("id"): field.get("label") for field in schema if isinstance(field, dict)}
            row["display_answers"] = [
                {"label": labels.get(key) or key.replace("_", " ").title(), "value": value}
                for key, value in row.get("submitted_fields", {}).items() if not isinstance(value, dict)
            ]
            decorated.append(row)
        return decorated

    def device_public(device, live: bool | None = None) -> dict[str, Any]:
        link = registry.get(device.device_id)
        online = registry.is_online(device.device_id) if live is None else live
        battery = device.battery
        charging = device.charging
        if link is not None:
            battery = link.battery if link.battery is not None else battery
            charging = link.charging if link.charging is not None else charging
        return {
            "device_id": device.device_id,
            "name": device.friendly_name,
            "online": online,
            "last_seen": device_ops.last_seen_iso(device.last_seen),
            "battery": battery,
            "charging": charging,
        }

    def require_login_page(request: Request) -> Owner | RedirectResponse:
        db = db_session()
        try:
            owner = current_owner(request, db)
        finally:
            db.close()
        if owner is None:
            return redirect_login()
        return owner

    def redirect_login() -> RedirectResponse:
        return RedirectResponse(url="/login", status_code=status.HTTP_303_SEE_OTHER)

    def json_error(message: str, code: str = "ERROR", status_code: int = 400) -> JSONResponse:
        return JSONResponse({"ok": False, "error": message, "code": code}, status_code=status_code)

    def require_api_owner(request: Request) -> Owner | JSONResponse:
        db = db_session()
        try:
            owner = current_owner(request, db)
        finally:
            db.close()
        if owner is None:
            return json_error("Authentication required", "UNAUTHENTICATED", 401)
        return owner

    # ----- health -----
    @app.get("/health")
    async def health():
        return {"ok": True, "service": "ownerlink"}

    @app.get("/ready")
    async def ready():
        # Startup already validates configuration and Redis connectivity. A
        # live app therefore represents a ready single worker.
        return {"ok": True, "ready": bool(app.state.ready), "storage": app.state.storage_backend}

    # ----- pages -----
    @app.get("/login", response_class=HTMLResponse)
    async def login_page(request: Request):
        db = db_session()
        try:
            if current_owner(request, db):
                return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
        finally:
            db.close()
        return page(request, "login.html", {"error": None})

    @app.post("/login")
    async def login_submit(
        request: Request,
        username: str = Form(""),
        password: str = Form(""),
        csrf_token: str = Form(""),
    ):
        parse_csrf_from_form(csrf_token, request)
        ip = client_ip(request)
        key = f"{ip}:{username.strip().lower()}"
        if limiter.too_many(key) or limiter.too_many(ip):
            return page(request, "login.html", {"error": "Too many attempts. Try again later."}, 429)
        db = db_session()
        try:
            owner = authenticate(db, username.strip(), password)
            if owner is None:
                limiter.record(key)
                limiter.record(ip)
                return page(request, "login.html", {"error": "Invalid username or password"}, 401)
            limiter.clear(key)
            limiter.clear(ip)
            login_owner(request, owner)
            log.info("owner login success")
            return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
        finally:
            db.close()

    @app.post("/logout")
    async def logout(request: Request, csrf_token: str = Form("")):
        parse_csrf_from_form(csrf_token, request)
        logout_owner(request)
        return RedirectResponse("/login", status_code=status.HTTP_303_SEE_OTHER)

    @app.get("/", response_class=HTMLResponse)
    async def dashboard(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            rows = device_ops.list_active_devices(db)
            cards = [device_public(d) for d in rows]
            service = AffiliateService(db)
            metrics = service.analytics()
            recent = service.submissions(limit=8)
        finally:
            db.close()
        metrics["online_devices"] = sum(bool(card["online"]) for card in cards)
        return page(request, "dashboard.html", {"devices": cards, "metrics": metrics, "recent": recent})

    @app.get("/devices", response_class=HTMLResponse)
    async def devices_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            cards = [device_public(d) for d in device_ops.list_active_devices(db)]
        finally:
            db.close()
        return page(request, "devices.html", {"devices": cards})

    @app.get("/users", response_class=HTMLResponse)
    async def users_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try:
            accounts = AffiliateAccountService(db, settings.SESSION_MAX_AGE).users()
            service = AffiliateService(db)
            for account in accounts:
                submissions = service.submissions(user_id=account["user_id"])
                account["task_count"] = len(submissions)
                account["approved_count"] = sum(item["status"] == "APPROVED" for item in submissions)
        finally: db.close()
        return page(request, "users.html", {"users": accounts})

    @app.get("/users/{user_id}", response_class=HTMLResponse)
    async def user_page(request: Request, user_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try:
            account = next((row for row in AffiliateAccountService(db, settings.SESSION_MAX_AGE).users() if row["user_id"] == user_id), None)
            if account:
                account["submissions"] = AffiliateService(db).submissions(user_id=user_id, limit=100)
                account["ledger"] = AffiliateService(db).ledger(user_id=user_id, limit=100)
        finally: db.close()
        if not account: return page(request, "error.html", {"message": "User not found."}, 404)
        return page(request, "user.html", {"user": account})

    @app.get("/campaigns", response_class=HTMLResponse)
    async def campaigns_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try: rows = AffiliateService(db).campaigns()
        finally: db.close()
        return page(request, "campaigns.html", {"campaigns": rows})

    @app.get("/tasks", response_class=HTMLResponse)
    async def tasks_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try:
            service = AffiliateService(db)
            rows, categories = [row for row in service.tasks() if row["status"] != "ARCHIVED"], service.categories()
        finally: db.close()
        return page(request, "tasks.html", {"tasks": rows, "categories": categories})

    @app.get("/categories", response_class=HTMLResponse)
    async def categories_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try: rows = AffiliateService(db).categories()
        finally: db.close()
        return page(request, "categories.html", {"categories": rows})

    @app.get("/task-import", response_class=HTMLResponse)
    async def task_import_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        return page(request, "task_import.html")

    @app.get("/submissions", response_class=HTMLResponse)
    async def submissions_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try:
            service = AffiliateService(db)
            rows = decorate_submission_answers(service, service.submissions())
        finally: db.close()
        return page(request, "submissions.html", {"submissions": rows, "review_only": False})

    @app.get("/reviews", response_class=HTMLResponse)
    async def reviews_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try:
            service = AffiliateService(db)
            rows = decorate_submission_answers(service, service.submissions(status="PENDING_REVIEW"))
        finally: db.close()
        return page(request, "submissions.html", {"submissions": rows, "review_only": True})

    @app.get("/coin-ledger", response_class=HTMLResponse)
    async def coin_ledger_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try: rows = AffiliateService(db).ledger()
        finally: db.close()
        return page(request, "ledger.html", {"ledger": rows})

    @app.get("/analytics", response_class=HTMLResponse)
    async def analytics_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse): return maybe
        db = db_session()
        try: metrics = AffiliateService(db).analytics()
        finally: db.close()
        return page(request, "analytics.html", {"metrics": metrics})

    @app.get("/devices/add", response_class=HTMLResponse)
    async def add_device_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        return page(request, "add_device.html", {"base_url": settings.OWNERLINK_BASE_URL})

    @app.get("/settings", response_class=HTMLResponse)
    async def settings_page(request: Request):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try: audit = AffiliateService(db).audit_log(limit=50)
        finally: db.close()
        return page(request, "settings.html", {"message": None, "error": None, "audit": audit})

    @app.post("/settings/password")
    async def change_password(
        request: Request,
        current_password: str = Form(""),
        new_password: str = Form(""),
        confirm_password: str = Form(""),
        csrf_token: str = Form(""),
    ):
        parse_csrf_from_form(csrf_token, request)
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            owner = db.get_owner() if isinstance(db, RedisStore) else db.get(Owner, maybe.id)
            error = None
            message = None
            if owner is None or not verify_password(current_password, owner.password_hash):
                error = "Current password is incorrect."
            elif new_password != confirm_password:
                error = "New passwords do not match."
            elif len(new_password) < 12:
                error = "New password must be at least 12 characters."
            else:
                owner.password_hash = hash_password(new_password)
                if isinstance(db, RedisStore):
                    db.save_owner(owner)
                else:
                    db.commit()
                message = "Password updated."
            audit = AffiliateService(db).audit_log(limit=50)
            return page(request, "settings.html", {"message": message, "error": error, "audit": audit}, 400 if error else 200)
        finally:
            db.close()

    @app.get("/device/{device_id}", response_class=HTMLResponse)
    async def device_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.get_device(db, device_id)
            if device is None or device.revoked or not device.pairing_consumed:
                return page(request, "error.html", {"message": "Device not found."}, 404)
            card = device_public(device)
        finally:
            db.close()
        return page(request, "device.html", {"device": card})

    @app.get("/device/{device_id}/gallery", response_class=HTMLResponse)
    async def gallery_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "gallery.html", {"device": card})

    @app.get("/device/{device_id}/files", response_class=HTMLResponse)
    async def files_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "files.html", {"device": card})

    @app.get("/device/{device_id}/videos", response_class=HTMLResponse)
    async def videos_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "videos.html", {"device": card})

    @app.get("/device/{device_id}/location", response_class=HTMLResponse)
    async def location_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "location.html", {"device": card})

    @app.get("/device/{device_id}/call-logs", response_class=HTMLResponse)
    async def call_logs_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "call_logs.html", {"device": card})

    @app.get("/device/{device_id}/screen", response_class=HTMLResponse)
    async def screen_page(request: Request, device_id: str):
        maybe = require_login_page(request)
        if isinstance(maybe, RedirectResponse):
            return maybe
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            card = device_public(device)
        except device_ops.PairingError:
            return page(request, "error.html", {"message": "Device not found."}, 404)
        finally:
            db.close()
        return page(request, "screen.html", {"device": card})

    # ----- JSON API -----
    @app.post("/api/auth/register")
    async def affiliate_register(request: Request):
        body = await _safe_json(request)
        if body is None: return json_error("Expected a JSON object", "BAD_REQUEST", 400)
        db = db_session()
        try:
            profile, token = AffiliateAccountService(db, settings.SESSION_MAX_AGE).register(body, str(body.get("device_id") or "")[:64])
            return {"ok": True, "user_id": profile["user_id"], "profile": profile, "token": token}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/auth/login")
    async def affiliate_login(request: Request):
        body = await _safe_json(request)
        if body is None: return json_error("Expected a JSON object", "BAD_REQUEST", 400)
        db = db_session()
        try:
            profile, token = AffiliateAccountService(db, settings.SESSION_MAX_AGE).login(body.get("phone_number"), body.get("password"))
            return {"ok": True, "user_id": profile["user_id"], "profile": profile, "token": token}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/auth/session")
    async def affiliate_session(request: Request):
        user = affiliate_user(request)
        return {"ok": True, "profile": AffiliateAccountService.public(user)} if user else json_error("Authentication required", "AUTH_REQUIRED", 401)

    @app.get("/api/worker/sync")
    async def worker_sync(request: Request, device_id: str, cursor: int = 0):
        user = affiliate_user(request)
        if not user: return json_error("Please sign in again", "AUTH_REQUIRED", 401)
        if not is_uuid(device_id): return json_error("Invalid device identity", "INVALID_DEVICE", 400)
        db = db_session()
        try:
            return AffiliateService(db).sync(user["user_id"], device_id, user.get("display_name", "Affiliate user"), max(0, cursor))
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/worker/submissions")
    async def worker_submit(request: Request):
        user = affiliate_user(request)
        if not user: return json_error("Please sign in again", "AUTH_REQUIRED", 401)
        body = await _safe_json(request)
        if not isinstance(body, dict): return json_error("Expected a JSON object", "BAD_REQUEST", 400)
        device_id = str(body.get("device_id") or "")
        if not is_uuid(device_id): return json_error("Invalid device identity", "INVALID_DEVICE", 400)
        db = db_session()
        try:
            submission = AffiliateService(db).submit(str(body.get("task_id") or ""),
                str(body.get("client_submission_id") or ""), user["user_id"], device_id, body.get("fields") or {})
            try: schedule_auto_review(AffiliateService(db), submission)
            except Exception: log.exception("HTTP submission saved; review scheduling will be repaired")
            return {"ok": True, "submission": submission}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/auth/logout")
    async def affiliate_logout(request: Request):
        token = bearer(request)
        if not affiliate_user(request): return json_error("Authentication required", "AUTH_REQUIRED", 401)
        db = db_session()
        try: AffiliateAccountService(db, settings.SESSION_MAX_AGE).logout(token)
        finally: db.close()
        return {"ok": True}

    @app.get("/api/profile")
    async def affiliate_profile(request: Request):
        user = affiliate_user(request)
        return {"ok": True, "profile": AffiliateAccountService.public(user)} if user else json_error("Authentication required", "AUTH_REQUIRED", 401)

    @app.put("/api/profile")
    async def affiliate_profile_update(request: Request):
        user = affiliate_user(request)
        if not user: return json_error("Authentication required", "AUTH_REQUIRED", 401)
        body = await _safe_json(request)
        if body is None: return json_error("Expected a JSON object", "BAD_REQUEST", 400)
        db = db_session()
        try: return {"ok": True, "profile": AffiliateAccountService(db, settings.SESSION_MAX_AGE).update(user["user_id"], body)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/profile/password")
    async def affiliate_password_change(request: Request):
        user = affiliate_user(request)
        if not user: return json_error("Authentication required", "AUTH_REQUIRED", 401)
        body = await _safe_json(request)
        if body is None: return json_error("Expected a JSON object", "BAD_REQUEST", 400)
        db = db_session()
        try:
            AffiliateAccountService(db, settings.SESSION_MAX_AGE).change_password(
                user["user_id"], body.get("current_password"), body.get("new_password"), body.get("confirm_password")
            )
            return {"ok": True}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/users/{user_id}/photo")
    async def affiliate_photo(request: Request, user_id: str):
        viewer = affiliate_user(request)
        owner = None
        db = db_session()
        try:
            if not viewer: owner = current_owner(request, db)
            if not viewer and not owner: return json_error("Authentication required", "AUTH_REQUIRED", 401)
            if viewer and viewer["user_id"] != user_id: return json_error("Access denied", "FORBIDDEN", 403)
            value = AffiliateAccountService(db, settings.SESSION_MAX_AGE).get(user_id, include_private=True)
        finally: db.close()
        if not value or not value.get("profile_photo_data"): return Response(status_code=404)
        return Response(base64.b64decode(value["profile_photo_data"]), media_type=value.get("profile_photo_mime") or "image/jpeg", headers={"Cache-Control": "private, max-age=300"})

    async def affiliate_admin_body(request: Request) -> tuple[Owner | JSONResponse, dict[str, Any] | None]:
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth, None
        body = await _safe_json(request)
        try:
            parse_csrf_from_form((body or {}).get("csrf_token"), request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403), None
        if body is None:
            return json_error("Expected a JSON object", "BAD_REQUEST", 400), None
        body.pop("csrf_token", None)
        return auth, body

    async def broadcast_task_event(event_type: str, task: dict[str, Any]) -> None:
        payload = {"task_id": task["task_id"], "status": task["status"], "revision": task["revision"]}
        for link in list(registry.links.values()):
            try:
                await registry.send_json(link.device_id, P.envelope(event_type, link.device_id, payload))
            except DeviceOffline:
                pass

    @app.get("/api/categories")
    async def api_categories(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "categories": AffiliateService(db).categories()}
        finally: db.close()

    @app.post("/api/categories")
    async def api_create_category(request: Request):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "category": AffiliateService(db).create_category(body or {}, auth.username)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.put("/api/categories/{category_id}")
    async def api_update_category(request: Request, category_id: str):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "category": AffiliateService(db).update_category(category_id, body or {}, auth.username)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.delete("/api/categories/{category_id}")
    async def api_delete_category(request: Request, category_id: str):
        auth, _ = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: AffiliateService(db).delete_category(category_id, auth.username); return {"ok": True}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/templates")
    async def api_templates(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "templates": AffiliateService(db).templates()}
        finally: db.close()

    @app.post("/api/templates")
    async def api_create_template(request: Request):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "template": AffiliateService(db).create_template(body or {}, auth.username)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/device-groups")
    async def api_device_groups(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "groups": AffiliateService(db).groups()}
        finally: db.close()

    @app.post("/api/device-groups")
    async def api_create_device_group(request: Request):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "group": AffiliateService(db).create_group(body or {}, auth.username)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/campaigns")
    async def api_campaigns(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "campaigns": AffiliateService(db).campaigns()}
        finally: db.close()

    @app.post("/api/campaigns")
    async def api_create_campaign(request: Request):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            return {"ok": True, "campaign": AffiliateService(db).create_campaign(body or {}, auth.username)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/campaigns/{campaign_id}/status")
    async def api_campaign_status(request: Request, campaign_id: str):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            value = AffiliateService(db).campaign_status(campaign_id, str((body or {}).get("status") or ""), auth.username)
            return {"ok": True, "campaign": value}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/tasks")
    async def api_tasks(request: Request, status_filter: str | None = None, category: str | None = None,
                        cursor: int = 0, limit: int = 100):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            rows = AffiliateService(db).tasks(status=status_filter, category=category)
            start, cap = max(0, cursor), max(1, min(limit, 200))
            return {"ok": True, "tasks": rows[start:start + cap], "next_cursor": start + cap if start + cap < len(rows) else None}
        finally: db.close()

    @app.post("/api/tasks")
    async def api_create_task(request: Request):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            value = AffiliateService(db).create_task(body or {}, auth.username)
            return {"ok": True, "task": value}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/tasks/import")
    async def api_import_task(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        try:
            raw_body = await request.json()
        except Exception:
            return json_error("Expected a task JSON object or array", "BAD_REQUEST", 400)
        try:
            parse_csrf_from_form(raw_body.get("csrf_token") if isinstance(raw_body, dict) else None, request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        if isinstance(raw_body, list):
            if not 1 <= len(raw_body) <= 100:
                return json_error("A task file must contain between 1 and 100 entries", "INVALID_IMPORT")
            if any(not isinstance(item, dict) or str(item.get("version") or "") != "1"
                   or not isinstance(item.get("category"), dict) or not isinstance(item.get("task"), dict)
                   for item in raw_body):
                return json_error("Each array entry must contain version 1, category, and task objects", "INVALID_IMPORT")
            category_raw = raw_body[0]["category"]
            category_key = (str(category_raw.get("name") or "").strip().casefold(),
                            str(category_raw.get("description") or "").strip())
            if any((str(item["category"].get("name") or "").strip().casefold(),
                    str(item["category"].get("description") or "").strip()) != category_key for item in raw_body[1:]):
                return json_error("All entries in an array import must use the same category", "INVALID_IMPORT")
            task_rows = [dict(item["task"]) for item in raw_body]
            title_counts: dict[str, int] = {}
            for task in task_rows:
                title = str(task.get("title") or "").strip()
                if title:
                    title_counts[title.casefold()] = title_counts.get(title.casefold(), 0) + 1
            for index, task in enumerate(task_rows, start=1):
                title = str(task.get("title") or "").strip()
                if title and title_counts.get(title.casefold(), 0) > 1:
                    task["title"] = f"{title} {index:02d}"
            body = {"version": 1, "category": category_raw,
                    "series": {"name": category_raw.get("name") or "Imported tasks"}, "tasks": task_rows}
        elif isinstance(raw_body, dict):
            raw_body.pop("csrf_token", None)
            body = raw_body
        else:
            return json_error("Expected a task JSON object or array", "BAD_REQUEST", 400)
        if not isinstance(body, dict) or int(body.get("version") or 0) != 1:
            return json_error("Task JSON version must be 1", "INVALID_IMPORT")
        category_raw = body.get("category")
        task_raw, task_rows = body.get("task"), body.get("tasks")
        is_sequence = isinstance(task_rows, list)
        if not isinstance(category_raw, dict) or (not isinstance(task_raw, dict) and not is_sequence):
            return json_error("Task JSON requires a category and either task or tasks", "INVALID_IMPORT")
        if is_sequence and not (1 <= len(task_rows) <= 100):
            return json_error("A task sequence must contain between 1 and 100 tasks", "INVALID_IMPORT")
        db = db_session()
        created_category = None
        created_tasks: list[dict] = []
        try:
            service = AffiliateService(db)
            category_name = str(category_raw.get("name") or "").strip()
            category = next((item for item in service.categories() if item["name"].casefold() == category_name.casefold()), None)
            if category is None:
                category = service.create_category(category_raw, auth.username)
                created_category = category["category_id"]
            defaults = body.get("task_defaults") if isinstance(body.get("task_defaults"), dict) else {}
            series = body.get("series") if isinstance(body.get("series"), dict) else {}
            sequence_id = uuid.uuid4().hex if is_sequence else None
            source_rows = task_rows if is_sequence else [task_raw]
            prepared_rows: list[dict] = []
            for index, source in enumerate(source_rows, start=1):
                if not isinstance(source, dict):
                    raise AffiliateError("Every sequence task must be an object", "INVALID_IMPORT")
                payload = {**defaults, **source}
                title_prefix = str(defaults.get("title_prefix") or series.get("name") or "Watch video").strip()
                payload.setdefault("title", f"{title_prefix} {index}")
                payload["category_id"] = category["category_id"]
                payload["field_schema"] = payload.pop("response_fields", payload.get("field_schema") or [])
                payload.pop("title_prefix", None)
                payload.pop("priority", None)
                payload.pop("duration", None)
                payload.pop("action_duration_seconds", None)
                if sequence_id:
                    payload["sequence_id"], payload["sequence_order"] = sequence_id, index
                publish = bool(payload.pop("publish", False)) or str(payload.pop("status", "")).upper() == "PUBLISHED"
                if is_sequence and publish:
                    prepared_rows.append(payload)
                else:
                    value = service.create_task(payload, auth.username)
                    if publish:
                        value = service.task_status(value["task_id"], "PUBLISHED", auth.username)
                    created_tasks.append(value)
            if prepared_rows:
                created_tasks.extend(service.import_published_sequence(prepared_rows, auth.username))
        except (AffiliateError, TypeError, ValueError) as exc:
            for created in reversed(created_tasks):
                try: AffiliateService(db).delete_task(created["task_id"], auth.username)
                except Exception: pass
            if created_category:
                try: AffiliateService(db).delete_category(created_category, auth.username)
                except Exception: pass
            if isinstance(exc, AffiliateError): return json_error(exc.message, exc.code, exc.status_code)
            return json_error("Task JSON contains invalid values", "INVALID_IMPORT")
        finally: db.close()
        first = created_tasks[0]
        await broadcast_task_event(P.TASK_PUBLISHED if first["status"] == "PUBLISHED" else P.TASK_UPDATED, first)
        return {"ok": True, "task": first, "tasks": created_tasks, "sequence_id": sequence_id, "category": category}

    @app.put("/api/tasks/{task_id}")
    async def api_update_task(request: Request, task_id: str):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: value = AffiliateService(db).update_task(task_id, body or {}, auth.username)
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()
        await broadcast_task_event(P.TASK_UPDATED, value)
        return {"ok": True, "task": value}

    @app.delete("/api/tasks/{task_id}")
    async def api_delete_task(request: Request, task_id: str):
        auth, _body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: AffiliateService(db).delete_task(task_id, auth.username)
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()
        await broadcast_task_event(P.TASK_UPDATED, {"task_id": task_id, "status": "DELETED", "revision": 0})
        return {"ok": True}

    @app.post("/api/tasks/{task_id}/status")
    async def api_task_status(request: Request, task_id: str):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            value = AffiliateService(db).task_status(task_id, str((body or {}).get("status") or ""), auth.username)
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()
        event_type = {"PUBLISHED": P.TASK_PUBLISHED, "PAUSED": P.TASK_PAUSED, "CLOSED": P.TASK_CLOSED}.get(value["status"], P.TASK_UPDATED)
        await broadcast_task_event(event_type, value)
        return {"ok": True, "task": value}

    @app.get("/api/submissions")
    async def api_submissions(request: Request, status_filter: str | None = None, task_id: str | None = None,
                              device_id: str | None = None, user_id: str | None = None, campaign_id: str | None = None,
                              date_from: str | None = None, date_to: str | None = None, cursor: int = 0, limit: int = 100):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            rows = AffiliateService(db).submissions(status=status_filter, task_id=task_id, device_id=device_id, user_id=user_id,
                                                     campaign_id=campaign_id, date_from=date_from, date_to=date_to, limit=1000)
            start, cap = max(0, cursor), max(1, min(limit, 200))
            return {"ok": True, "submissions": rows[start:start + cap], "next_cursor": start + cap if start + cap < len(rows) else None}
        finally: db.close()

    @app.post("/api/submissions/{submission_id}/review")
    async def api_review_submission(request: Request, submission_id: str):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            submission, ledger = AffiliateService(db).review(submission_id, str((body or {}).get("decision") or ""),
                                                               str((body or {}).get("reason") or ""), auth.username)
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()
        link = registry.contacts.get(submission["user_id"])
        if link:
            event_type = P.COIN_CREDITED if submission["status"] == "APPROVED" else (P.REVISION_REQUESTED if submission["status"] == "REVISION_REQUESTED" else P.SUBMISSION_STATUS_CHANGED)
            try:
                await registry.send_json(link.device_id, P.envelope(event_type, link.device_id, {"submission": submission, "ledger": ledger}))
            except DeviceOffline:
                pass
        return {"ok": True, "submission": submission, "ledger": ledger}

    @app.get("/api/coin-ledger")
    async def api_coin_ledger(request: Request, user_id: str | None = None, min_amount: str | None = None,
                              max_amount: str | None = None, limit: int = 100):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "ledger": AffiliateService(db).ledger(user_id=user_id, limit=limit,
            min_amount=coins_to_milli(min_amount) if min_amount not in (None, "") else None,
            max_amount=coins_to_milli(max_amount) if max_amount not in (None, "") else None)}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.post("/api/coin-ledger/adjust")
    async def api_coin_adjust(request: Request):
        auth, body = await affiliate_admin_body(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try:
            entry = AffiliateService(db).manual_adjust(str((body or {}).get("user_id") or "").strip(), (body or {}).get("amount"),
                str((body or {}).get("reason") or ""), auth.username, str((body or {}).get("idempotency_key") or "") or None)
            return {"ok": True, "entry": entry}
        except AffiliateError as exc: return json_error(exc.message, exc.code, exc.status_code)
        finally: db.close()

    @app.get("/api/analytics")
    async def api_analytics(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse): return auth
        db = db_session()
        try: return {"ok": True, "metrics": AffiliateService(db).analytics()}
        finally: db.close()

    @app.get("/api/devices")
    async def api_devices(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            rows = device_ops.list_active_devices(db)
            return {"ok": True, "devices": [device_public(d) for d in rows]}
        finally:
            db.close()

    @app.get("/api/devices/{device_id}")
    async def api_device(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device = device_ops.require_active(db, device_id)
            data = device_public(device)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        if registry.is_online(device_id):
            try:
                status_payload = await registry.command(device_id, P.GET_STATUS, {})
                data["device_status"] = status_payload.get("data") or status_payload
            except (DeviceOffline, CommandError):
                pass
        return {"ok": True, "device": data}

    @app.get("/api/contact/{contact_id}")
    async def api_contact(request: Request, contact_id: str):
        ip = client_ip(request)
        if contact_limiter.too_many(ip):
            return json_error("Too many contact lookups", "RATE_LIMITED", 429)
        contact_limiter.record(ip)
        if not (8 <= len(contact_id) <= 128):
            return json_error("Contact not found", "NOT_FOUND", 404)
        value = registry.contact_public(contact_id)
        if value is None and settings.uses_redis:
            db = db_session()
            try:
                value = db.get_contact(contact_id) if isinstance(db, RedisStore) else None
            finally:
                db.close()
        if value is None:
            return json_error("Contact not found", "NOT_FOUND", 404)
        return {"ok": True, "contact": value}

    @app.post("/api/devices/pair")
    async def api_pair(request: Request):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        body = await _safe_json(request)
        try:
            parse_csrf_from_form((body or {}).get("csrf_token"), request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        if not body:
            return json_error("Expected JSON pairing payload", "BAD_REQUEST")
        payload = body.get("payload") if isinstance(body.get("payload"), dict) else body
        redeem_code = str(payload.get("redeem_code") or "").strip()
        if redeem_code:
            if not pairing_token_entropy_ok(redeem_code):
                return json_error("Redeem code is invalid", "INVALID_TOKEN")
            lookup_db = db_session()
            try: pending = device_ops.find_unpaired_by_token(lookup_db, redeem_code)
            finally: lookup_db.close()
            if pending is None:
                return json_error("Redeem code was not found. Keep Affiliate open and try again.", "UNKNOWN_CODE", 404)
            payload = {"v": 1, "device_id": pending.device_id, "pairing_token": redeem_code, "server": settings.OWNERLINK_BASE_URL}
        try:
            v = int(payload.get("v") or 0)
        except (TypeError, ValueError):
            v = 0
        device_id = str(payload.get("device_id") or "").strip()
        token = str(payload.get("pairing_token") or "").strip()
        server = str(payload.get("server") or "").strip()
        name = str(body.get("name") or payload.get("name") or "").strip()
        if v != 1:
            return json_error("Unsupported pairing payload version", "BAD_VERSION")
        if not is_uuid(device_id):
            return json_error("Invalid device id", "INVALID_DEVICE")
        if not pairing_token_entropy_ok(token):
            return json_error("Pairing token is invalid", "INVALID_TOKEN")

        connected = registry.get(device_id) is not None
        if not connected:
            for _ in range(20):
                await asyncio.sleep(0.5)
                connected = registry.get(device_id) is not None
                if connected:
                    break
        db = db_session()
        try:
            device, session_token = device_ops.pair_device(
                db,
                device_id=device_id,
                pairing_token=token,
                expected_base_url=settings.OWNERLINK_BASE_URL,
                qr_server=server,
                friendly_name=name or None,
                device_currently_connected=connected,
            )
            affiliate = AffiliateService(db)
            affiliate.record_audit("DEVICE_PAIRED", "device", device_id, auth.username)
            account = AffiliateAccountService(db).by_device_id(device_id)
            bonus = None
            if account:
                bonus = affiliate.manual_adjust(
                    account["user_id"],
                    "50",
                    "Dashboard connection bonus",
                    "system",
                    idempotency_key=f"dashboard-connection-bonus:{account['user_id']}",
                    entry_type="DASHBOARD_BONUS",
                )
            link = registry.get(device_id)
            if link is not None:
                link.paired = True
                try:
                    await registry.send_json(
                        device_id,
                        P.envelope(P.PAIR_SUCCESS, device_id, {"session_token": session_token}),
                    )
                    if bonus is not None:
                        await registry.send_json(
                            device_id,
                            P.envelope(P.COIN_CREDITED, device_id, {"ledger": bonus, "reason": "Dashboard connection bonus"}),
                        )
                except DeviceOffline:
                    return json_error("Device disconnected during pairing", "DEVICE_OFFLINE", 409)
            return {"ok": True, "device": device_public(device)}
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 400)
        finally:
            db.close()

    @app.post("/api/devices/{device_id}/remove")
    async def api_remove(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        try:
            require_csrf(request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        db = db_session()
        try:
            device_ops.revoke_device(db, device_id)
            AffiliateService(db).record_audit("DEVICE_REVOKED", "device", device_id, auth.username)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        link = registry.get(device_id)
        if link is not None:
            try:
                await registry.send_json(device_id, P.envelope(P.REVOKED, device_id, {}))
            except Exception:
                pass
            try:
                await link.websocket.close(code=4001, reason="revoked")
            except Exception:
                pass
            await registry.detach(device_id, link.websocket)
        log.info("device removed from dashboard")
        return {"ok": True}

    @app.post("/api/devices/{device_id}/rename")
    async def api_rename(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        try:
            require_csrf(request)
        except HTTPException:
            return json_error("Invalid CSRF token", "CSRF", 403)
        body = await _safe_json(request)
        name = str((body or {}).get("name") or "").strip()
        if not name:
            return json_error("Name is required", "BAD_REQUEST")
        db = db_session()
        try:
            device = device_ops.rename_device(db, device_id, name)
            AffiliateService(db).record_audit("DEVICE_RENAMED", "device", device_id, auth.username)
            return {"ok": True, "device": device_public(device)}
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()

    @app.get("/api/devices/{device_id}/gallery")
    async def api_gallery(request: Request, device_id: str, cursor: str | None = None, limit: int = 200, album: str | None = None):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(
                device_id,
                P.LIST_GALLERY,
                {"cursor": cursor, "limit": max(1, min(limit, 200)), "album": album},
            )
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)

    @app.get("/api/devices/{device_id}/files")
    async def api_files(request: Request, device_id: str, folder: str | None = None, cursor: str | None = None):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(
                device_id,
                P.LIST_FILES,
                {"folder_id": folder, "cursor": cursor},
            )
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)

    @app.get("/api/devices/{device_id}/videos")
    async def api_videos(request: Request, device_id: str, cursor: str | None = None, limit: int = 40):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(device_id, P.LIST_VIDEOS, {"cursor": cursor, "limit": max(1, min(limit, 100))})
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device is offline.", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 403 if exc.code == "PERMISSION_MISSING" else 400)

    @app.get("/api/devices/{device_id}/location")
    async def api_location(request: Request, device_id: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(device_id, P.GET_LOCATION, {})
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device is offline.", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 403 if exc.code == "LOCATION_PERMISSION_REQUIRED" else 400)

    @app.get("/api/devices/{device_id}/call-logs")
    async def api_call_logs(request: Request, device_id: str, cursor: int = 0, limit: int = 50, filter: str | None = None):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        if filter not in {None, "incoming", "outgoing", "missed"}:
            return json_error("Invalid call type filter", "BAD_FILTER", 400)
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        try:
            result = await registry.command(
                device_id,
                P.LIST_CALL_LOGS,
                {"cursor": max(0, cursor), "limit": max(1, min(limit, 100)), "filter": filter},
            )
            return {"ok": True, **(result.get("data") or result)}
        except DeviceOffline:
            return json_error("Device is offline.", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            message = "Call Log permission is required on this device." if exc.code == "CALL_LOG_PERMISSION_REQUIRED" else exc.message
            return json_error(message, exc.code, 403 if exc.code == "CALL_LOG_PERMISSION_REQUIRED" else 400)

    @app.get("/api/devices/{device_id}/thumb")
    async def api_thumb(request: Request, device_id: str, file: str):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        request_id = str(uuid.uuid4())
        try:
            transfer = await registry.open_thumb(device_id, request_id)
            await registry.send_json(
                device_id,
                P.envelope(P.GET_THUMBNAIL, device_id, {"file_id": file}, request_id),
            )
            chunk = await _wait_bytes(transfer, timeout=settings.COMMAND_TIMEOUT_SECONDS)
            if chunk is None:
                return json_error("Thumbnail unavailable", "NOT_FOUND", 404)
            return StreamingResponse(iter([chunk]), media_type="image/jpeg")
        except DeviceOffline:
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            return json_error(exc.message, exc.code, 400)
        finally:
            registry.close_transfer(device_id, request_id)

    @app.get("/api/devices/{device_id}/download")
    async def api_download(request: Request, device_id: str, file: str, inline: bool = False):
        auth = require_api_owner(request)
        if isinstance(auth, JSONResponse):
            return auth
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError as exc:
            return json_error(exc.message, exc.code, 404)
        finally:
            db.close()
        transfer_id = str(uuid.uuid4())
        offset = 0
        requested_length = None
        range_header = request.headers.get("range")
        if range_header:
            import re
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header.strip())
            if match is None:
                return json_error("Only a single start-based byte range is supported", "INVALID_RANGE", 416)
            offset = int(match.group(1))
            if match.group(2):
                end = int(match.group(2))
                if end < offset:
                    return json_error("Invalid byte range", "INVALID_RANGE", 416)
                requested_length = end - offset + 1
        try:
            transfer = await registry.open_transfer(device_id, transfer_id)
            await registry.send_json(
                device_id,
                P.envelope(
                    P.DOWNLOAD_FILE,
                    device_id,
                    {"file_id": file, "transfer_id": transfer_id, "offset": offset, **({"length": requested_length} if requested_length is not None else {})},
                    transfer_id,
                ),
            )
            first = await _wait_queue_item(transfer, timeout=settings.COMMAND_TIMEOUT_SECONDS)
            meta: dict[str, Any] = {}
            first_bytes: bytes | None = None
            if isinstance(first, dict) and "_meta" in first:
                meta = first["_meta"] or {}
            elif isinstance(first, (bytes, bytearray)):
                first_bytes = bytes(first)
            elif first is None:
                return json_error("Empty file", "NOT_FOUND", 404)

            async def generate():
                delivered = 0
                try:
                    if first_bytes:
                        delivered += len(first_bytes)
                        yield first_bytes
                    while True:
                        item = await _wait_queue_item(transfer, timeout=settings.DOWNLOAD_TIMEOUT_SECONDS)
                        if item is None:
                            expected = meta.get("size")
                            if isinstance(expected, int) and delivered != expected:
                                raise RuntimeError("Incomplete device file transfer")
                            break
                        if isinstance(item, dict):
                            continue
                        if isinstance(item, (bytes, bytearray)):
                            delivered += len(item)
                            yield bytes(item)
                finally:
                    registry.close_transfer(device_id, transfer_id)

            mime = str(meta.get("mime") or "application/octet-stream")
            disposition = content_disposition(str(meta.get("name") or "download"))
            if inline and (mime.startswith("image/") or mime.startswith("video/") or mime == "application/pdf"):
                disposition = disposition.replace("attachment;", "inline;", 1)
            headers = {"Content-Disposition": disposition, "Cache-Control": "private, no-store", "X-Accel-Buffering": "no", "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "sandbox"}
            size = meta.get("size")
            total_size = meta.get("total_size", size)
            if isinstance(total_size, int) and total_size >= 0:
                headers["Accept-Ranges"] = "bytes"
            if isinstance(size, int) and size >= 0:
                headers["Content-Length"] = str(size)
            if range_header:
                if not isinstance(total_size, int) or not isinstance(size, int) or size <= 0 or meta.get("offset") != offset:
                    registry.close_transfer(device_id, transfer_id)
                    return json_error("Device cannot serve this byte range; update the phone app", "INVALID_RANGE", 416)
                headers["Content-Range"] = f"bytes {offset}-{offset + size - 1}/{total_size}"
            return StreamingResponse(generate(), status_code=206 if range_header else 200, media_type=mime, headers=headers)
        except asyncio.TimeoutError:
            registry.close_transfer(device_id, transfer_id)
            return json_error("Phone did not start the file transfer in time", "TIMEOUT", 504)
        except DeviceOffline:
            registry.close_transfer(device_id, transfer_id)
            return json_error("Device offline", "DEVICE_OFFLINE", 409)
        except CommandError as exc:
            registry.close_transfer(device_id, transfer_id)
            return json_error(exc.message, exc.code, 416 if exc.code == "INVALID_RANGE" else 400)

    # ----- websockets -----
    @app.websocket("/ws/device")
    async def device_ws(websocket: WebSocket):
        await websocket.accept()
        state: dict[str, Any] = {"link": None, "device_id": None}
        try:
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    break
                if message.get("text") is not None:
                    try:
                        data = json.loads(message["text"])
                    except json.JSONDecodeError:
                        await websocket.send_json(P.envelope(P.ERROR, "", {"code": "BAD_JSON", "message": "Invalid JSON"}))
                        continue
                    if not isinstance(data, dict):
                        await websocket.send_json(P.envelope(P.ERROR, "", {"code": "BAD_JSON", "message": "JSON envelope must be an object"}))
                        continue
                    if data.get("v", 1) != 1:
                        await websocket.send_json(P.envelope(P.ERROR, "", {"code": "BAD_VERSION", "message": "Unsupported protocol version"}))
                        continue
                    await _handle_device_text(websocket, data, state, SessionLocal, registry)
                elif message.get("bytes") is not None:
                    link = state.get("link")
                    if link is not None:
                        await registry.handle_binary(link, message["bytes"])
        except WebSocketDisconnect:
            pass
        except Exception:
            log.exception("device websocket error")
        finally:
            device_id = state.get("device_id")
            if device_id:
                await registry.detach(device_id, websocket)

    @app.websocket("/ws/screen/{device_id}")
    async def screen_ws(websocket: WebSocket, device_id: str):
        await websocket.accept()
        session = websocket.scope.get("session") or {}
        if not session.get("owner_id"):
            await websocket.close(code=4401, reason="Authentication required")
            return
        db = db_session()
        try:
            device_ops.require_active(db, device_id)
        except device_ops.PairingError:
            db.close()
            await websocket.close(code=4404, reason="Device not found")
            return
        finally:
            db.close()

        if not registry.is_online(device_id):
            await websocket.send_json({"type": "ERROR", "payload": {"code": "DEVICE_OFFLINE", "message": "Device offline"}})
            await websocket.close(code=4409)
            return

        queue = registry.subscribe_screen(device_id)
        first = registry.screen_subscriber_count(device_id) == 1
        try:
            if first:
                await registry.send_json(
                    device_id,
                    P.envelope(P.START_SCREEN_STREAM, device_id, {"fps": 4, "quality": 55, "max_width": 720}),
                )
            await websocket.send_json({"type": "STATUS", "payload": {"online": True, "streaming": True}})
            while True:
                recv_task = _ws_receive_task(websocket)
                get_task = _queue_get_task(queue)
                done, pending = await _wait_first(recv_task, get_task)
                for task in pending:
                    task.cancel()
                if recv_task in done:
                    incoming = recv_task.result()
                    if incoming is None:
                        break
                    # View-only channel: browser messages never become device commands.
                    continue
                if get_task in done:
                    item = get_task.result()
                    if item is None:
                        continue
                    if item[0] == "json":
                        await websocket.send_json(item[1])
                    elif item[0] == "frame":
                        _header, jpeg = item[1], item[2]
                        await websocket.send_json({"type": "FRAME_META", "payload": _header})
                        await websocket.send_bytes(jpeg)
        except WebSocketDisconnect:
            pass
        except DeviceOffline:
            try:
                await websocket.send_json(
                    {"type": "ERROR", "payload": {"code": "DEVICE_OFFLINE", "message": "Device offline"}}
                )
            except Exception:
                pass
        except Exception:
            log.exception("screen websocket error")
        finally:
            remaining = registry.unsubscribe_screen(device_id, queue)
            if remaining == 0:
                try:
                    await registry.send_json(device_id, P.envelope(P.STOP_SCREEN_STREAM, device_id, {}))
                except Exception:
                    pass
            if websocket.client_state == WebSocketState.CONNECTED:
                try:
                    await websocket.close()
                except Exception:
                    pass

    return app


async def _safe_json(request: Request) -> dict | None:
    try:
        body = await request.json()
    except Exception:
        return None
    return body if isinstance(body, dict) else None


async def _handle_device_text(websocket: WebSocket, data: dict, state: dict, SessionLocal, registry: Registry) -> None:
    msg_type = data.get("type")
    device_id = str(data.get("device_id") or "")
    payload = data.get("payload", {})
    if payload is None:
        payload = {}
    if not isinstance(payload, dict):
        await websocket.send_json(P.envelope(P.ERROR, device_id, {"code": "BAD_PAYLOAD", "message": "Payload must be an object"}))
        return

    if msg_type == P.UNPAIRED_HELLO:
        token = str(payload.get("pairing_token") or "")
        name = str(payload.get("name") or "Android Device")
        db = SessionLocal()
        try:
            device = device_ops.upsert_unpaired(db, device_id, token, name)
            link = await registry.attach(device.device_id, websocket, paired=False)
            state["link"] = link
            state["device_id"] = device.device_id
            await websocket.send_json(
                P.envelope("UNPAIRED_ACK", device.device_id, {"status": "waiting_for_owner"})
            )
        except device_ops.PairingError as exc:
            await websocket.send_json(
                P.envelope(P.ERROR, device_id, {"code": exc.code, "message": exc.message})
            )
        finally:
            db.close()
        return

    if msg_type == P.DEVICE_AUTH:
        token = str(payload.get("session_token") or "")
        db = SessionLocal()
        try:
            device = device_ops.authenticate_session(db, device_id, token)
            link = await registry.attach(device.device_id, websocket, paired=True)
            state["link"] = link
            state["device_id"] = device.device_id
            await websocket.send_json(P.envelope(P.AUTH_OK, device.device_id, {"name": device.friendly_name}))
            log.info("device authenticated")
        except device_ops.PairingError as exc:
            await websocket.send_json(
                P.envelope(P.ERROR, device_id, {"code": exc.code, "message": exc.message})
            )
        finally:
            db.close()
        return

    link = state.get("link")
    if link is None:
        await websocket.send_json(
            P.envelope(P.ERROR, device_id, {"code": "NOT_AUTHENTICATED", "message": "Send DEVICE_AUTH or UNPAIRED_HELLO first"})
        )
        return

    if msg_type == P.CHAT_IDENTITY_HELLO:
        contact_id = str(payload.get("contact_id") or "").strip()
        public_key = str(payload.get("public_key") or "").strip()
        challenge = str(payload.get("challenge") or "")
        signature = str(payload.get("signature") or "")
        if not (8 <= len(contact_id) <= 128 and 32 <= len(public_key) <= 4096):
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "INVALID_CHAT_IDENTITY", "message": "Invalid chat identity"
            }, data.get("request_id")))
            return
        expected_challenge = state.pop("chat_challenge", None)
        if not expected_challenge or not secrets.compare_digest(challenge, expected_challenge) or not _verify_chat_identity(contact_id, public_key, signature, challenge, link.device_id):
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "CHAT_IDENTITY_PROOF_FAILED", "message": "Chat identity proof failed"
            }, data.get("request_id")))
            return
        name = str(payload.get("name") or "PrivatChat user").strip()[:80] or "PrivatChat user"
        await registry.register_contact(link, contact_id, public_key, name)
        db = SessionLocal()
        try:
            if isinstance(db, RedisStore):
                db.save_contact(contact_id, public_key, name)
        finally:
            db.close()
        await websocket.send_json(P.envelope(P.CHAT_IDENTITY_ACK, link.device_id, {
            "contact_id": contact_id
        }, data.get("request_id")))
        return

    peer_types = {
        P.PEER_MESSAGE, P.PEER_DELIVERED, P.PEER_READ, P.PEER_PRESENCE,
        P.PEER_CONNECT_REQUEST, P.PEER_CONNECT_ACCEPT, P.PEER_CONNECT_REJECT, P.PEER_SIGNAL,
        P.CALL_INVITE, P.CALL_RINGING, P.CALL_ACCEPT, P.CALL_OFFER, P.CALL_ANSWER,
        P.CALL_ICE, P.CALL_DECLINE, P.CALL_END,
    }
    if msg_type in peer_types:
        raw_size = len(json.dumps(data, separators=(",", ":")))
        if raw_size > 262_144:
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "MESSAGE_TOO_LARGE", "message": "Envelope exceeds 256 KiB"
            }, data.get("request_id")))
            return
        if not link.allow_route():
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "RATE_LIMITED", "message": "Too many signaling messages"
            }, data.get("request_id")))
            return
        delivered = await registry.route_peer(link, data)
        if msg_type == P.PEER_MESSAGE:
            await websocket.send_json(P.envelope(P.PEER_DELIVER, link.device_id, {
                "message_id": payload.get("message_id"),
                "delivered": delivered,
            }, data.get("request_id")))
        return

    if msg_type == P.CHAT_CHALLENGE_REQUEST:
        challenge = secrets.token_urlsafe(32)
        state["chat_challenge"] = challenge
        await websocket.send_json(P.envelope(P.CHAT_CHALLENGE, link.device_id, {"challenge": challenge}))
        return

    if msg_type == P.RTC_CONFIG_REQUEST:
        if not link.allow_route(limit=30):
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {"code": "RATE_LIMITED", "message": "Too many configuration requests"}, data.get("request_id")))
            return
        if not link.contact_id:
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "CHAT_IDENTITY_REQUIRED", "message": "Register chat identity before requesting call configuration"
            }, data.get("request_id")))
            return
        subject = link.contact_id
        await websocket.send_json(P.envelope(P.RTC_CONFIG, link.device_id, create_ice_configuration(registry.settings, subject)))
        return

    if msg_type == P.TASK_SYNC_REQUEST:
        try:
            cursor = max(0, int(payload.get("cursor") or 0))
        except (TypeError, ValueError):
            cursor = 0
        db = SessionLocal()
        try:
            account = AffiliateAccountService(db).authenticate(str(payload.get("auth_token") or ""))
            if not account:
                await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                    "code": "AUTH_REQUIRED", "message": "Affiliate login is required before syncing tasks"
                }, data.get("request_id")))
                return
            sync = AffiliateService(db).sync(account["user_id"], link.device_id, account.get("display_name", "Affiliate worker"), cursor)
            await websocket.send_json(P.envelope(P.TASK_SYNC, link.device_id, sync, data.get("request_id")))
        finally:
            db.close()
        return

    if msg_type == P.TASK_SUBMIT:
        if not link.allow_route(limit=30):
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "RATE_LIMITED", "message": "Too many task submissions"
            }, data.get("request_id")))
            return
        db = SessionLocal()
        try:
            account = AffiliateAccountService(db).authenticate(str(payload.get("auth_token") or ""))
            if not account:
                await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                    "code": "AUTH_REQUIRED", "message": "Affiliate login is required before submitting tasks"
                }, data.get("request_id")))
                return
            submission = AffiliateService(db).submit(
                str(payload.get("task_id") or ""), str(payload.get("client_submission_id") or ""),
                account["user_id"], link.device_id, payload.get("fields") or {},
            )
            try:
                schedule_auto_review(AffiliateService(db), submission)
            except Exception:
                log.exception("Submission saved; automatic review scheduling will be repaired")
            await websocket.send_json(P.envelope(P.TASK_SUBMISSION_ACK, link.device_id, {"submission": submission}, data.get("request_id")))
        except AffiliateError as exc:
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {"code": exc.code, "message": exc.message}, data.get("request_id")))
        finally:
            db.close()
        return

    if msg_type == P.PRESENCE_QUERY:
        target = str(payload.get("contact_id") or "")
        await websocket.send_json(P.envelope(P.PRESENCE_UPDATE, link.device_id, {
            "contact_id": target,
            "online": target in registry.contacts,
        }, data.get("request_id")))
        return

    if msg_type == P.DASHBOARD_DISCONNECT:
        if not link.paired:
            await websocket.send_json(P.envelope(P.ERROR, link.device_id, {
                "code": "NOT_PAIRED", "message": "Dashboard is not connected"
            }, data.get("request_id")))
            return
        db = SessionLocal()
        try:
            device_ops.revoke_device(db, link.device_id)
        finally:
            db.close()
        link.paired = False
        await websocket.send_json(P.envelope(P.REVOKED, link.device_id, {}))
        return

    if msg_type == P.HEARTBEAT:
        link.touch()
        if payload.get("battery") is not None:
            try:
                link.battery = int(payload.get("battery"))
            except (TypeError, ValueError):
                pass
        if payload.get("charging") is not None:
            link.charging = bool(payload.get("charging"))
        db = SessionLocal()
        try:
            device_ops.touch_device(db, link.device_id, link.battery, link.charging)
        finally:
            db.close()
        return

    await registry.handle_text(link, data)


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _verify_chat_identity(contact_id: str, public_key: str, signature: str, challenge: str, device_id: str) -> bool:
    try:
        public_bytes = _b64url_decode(public_key)
        derived = base64.urlsafe_b64encode(hashlib.sha256(public_bytes).digest()[:18]).decode().rstrip("=")
        if not secrets.compare_digest(derived, contact_id):
            return False
        key = serialization.load_der_public_key(public_bytes)
        if not isinstance(key, ec.EllipticCurvePublicKey):
            return False
        key.verify(_b64url_decode(signature), f"{challenge}:{device_id}".encode(), ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, TypeError, InvalidSignature):
        return False


async def _wait_queue_item(transfer, timeout: float):
    import asyncio

    if transfer.error is not None:
        raise transfer.error
    item = await asyncio.wait_for(transfer.queue.get(), timeout)
    if isinstance(item, CommandError):
        raise item
    return item


async def _wait_bytes(transfer, timeout: float) -> bytes | None:
    while True:
        item = await _wait_queue_item(transfer, timeout)
        if item is None:
            return None
        if isinstance(item, (bytes, bytearray)):
            return bytes(item)
        if isinstance(item, dict):
            continue


def _ws_receive_task(websocket: WebSocket):
    import asyncio

    async def _inner():
        try:
            message = await websocket.receive()
        except WebSocketDisconnect:
            return None
        if message["type"] == "websocket.disconnect":
            return None
        if message.get("text") is not None:
            try:
                return json.loads(message["text"])
            except json.JSONDecodeError:
                return None
        return None

    return asyncio.create_task(_inner())


def _queue_get_task(queue):
    import asyncio
    return asyncio.create_task(queue.get())


async def _wait_first(*tasks):
    import asyncio
    done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    return done, pending


# Use: uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
