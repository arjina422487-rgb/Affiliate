from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

from .binary import FLAG_LAST, KIND_FILE, KIND_SCREEN, KIND_THUMB, parse_frame, unpack_screen_payload
from .config import Settings
from .protocol import ERROR, FILE_BEGIN, FILE_END, RESPONSE, SCREEN_STATUS, envelope

log = logging.getLogger("ownerlink.registry")


class DeviceOffline(Exception):
    pass


class CommandError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class Transfer:
    queue: asyncio.Queue
    meta: dict[str, Any] | None = None
    error: CommandError | None = None
    done: bool = False
    next_sequence: int = 0
    received_bytes: int = 0
    closed: asyncio.Event = field(default_factory=asyncio.Event)


@dataclass
class DeviceLink:
    websocket: WebSocket
    device_id: str
    paired: bool = False
    last_seen: float = field(default_factory=time.time)
    battery: int | None = None
    charging: bool | None = None
    pending: dict[str, asyncio.Future] = field(default_factory=dict)
    transfers: dict[str, Transfer] = field(default_factory=dict)
    thumbs: dict[str, Transfer] = field(default_factory=dict)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    contact_id: str | None = None
    public_key: str | None = None
    contact_name: str | None = None
    route_times: list[float] = field(default_factory=list)

    def touch(self) -> None:
        self.last_seen = time.time()

    def is_stale(self, timeout: float) -> bool:
        return (time.time() - self.last_seen) > timeout

    def allow_route(self, limit: int = 240, window: float = 60.0) -> bool:
        now = time.time()
        self.route_times = [item for item in self.route_times if now - item < window]
        if len(self.route_times) >= limit:
            return False
        self.route_times.append(now)
        return True


class Registry:
    async def _enqueue_transfer(self, transfer: Transfer, item: Any) -> None:
        if transfer.closed.is_set():
            return
        put = asyncio.create_task(transfer.queue.put(item))
        closed = asyncio.create_task(transfer.closed.wait())
        try:
            done, _ = await asyncio.wait({put, closed}, timeout=120, return_when=asyncio.FIRST_COMPLETED)
            if not done:
                raise CommandError("TRANSFER_STALLED", "Download consumer stopped reading")
            if put in done:
                put.result()
        finally:
            put.cancel()
            closed.cancel()
            await asyncio.gather(put, closed, return_exceptions=True)

    def __init__(self, settings: Settings):
        self.settings = settings
        self.links: dict[str, DeviceLink] = {}
        self.screen_subs: dict[str, set[asyncio.Queue]] = {}
        self._guard = asyncio.Lock()
        self.contacts: dict[str, DeviceLink] = {}

    def get(self, device_id: str) -> DeviceLink | None:
        return self.links.get(device_id)

    def is_online(self, device_id: str) -> bool:
        link = self.links.get(device_id)
        if link is None:
            return False
        return not link.is_stale(self.settings.HEARTBEAT_STALE_SECONDS)

    async def attach(self, device_id: str, websocket: WebSocket, paired: bool) -> DeviceLink:
        async with self._guard:
            old = self.links.get(device_id)
            if old is not None and old.websocket is not websocket:
                try:
                    await old.websocket.close(code=4000, reason="replaced by newer connection")
                except Exception:
                    pass
                self._fail_pending(old, CommandError("REPLACED", "Device reconnected"))
            link = DeviceLink(websocket=websocket, device_id=device_id, paired=paired)
            self.links[device_id] = link
            return link

    async def detach(self, device_id: str, websocket: WebSocket) -> None:
        async with self._guard:
            current = self.links.get(device_id)
            if current is not None and current.websocket is websocket:
                self.links.pop(device_id, None)
                if current.contact_id and self.contacts.get(current.contact_id) is current:
                    self.contacts.pop(current.contact_id, None)
                self._fail_pending(current, CommandError("DISCONNECTED", "Device disconnected"))

    async def register_contact(self, link: DeviceLink, contact_id: str, public_key: str, name: str) -> None:
        async with self._guard:
            previous = self.contacts.get(contact_id)
            if previous is not None and previous is not link:
                previous.contact_id = None
            if link.contact_id and self.contacts.get(link.contact_id) is link:
                self.contacts.pop(link.contact_id, None)
            link.contact_id = contact_id
            link.public_key = public_key
            link.contact_name = name[:80]
            self.contacts[contact_id] = link

    def contact_public(self, contact_id: str) -> dict[str, str] | None:
        link = self.contacts.get(contact_id)
        if link is None or not link.public_key:
            return None
        return {"contact_id": contact_id, "public_key": link.public_key, "name": link.contact_name or "PrivatChat user"}

    async def route_peer(self, sender: DeviceLink, message: dict) -> bool:
        """Relay an opaque message/signaling envelope to one exact registered contact."""
        target_id = str((message.get("payload") or {}).get("to") or "")
        target = self.contacts.get(target_id)
        if not sender.contact_id or target is None:
            return False
        forwarded = dict(message)
        payload = dict(message.get("payload") or {})
        payload["from"] = sender.contact_id
        payload.pop("to", None)
        forwarded["device_id"] = target.device_id
        forwarded["payload"] = payload
        async with target.lock:
            await target.websocket.send_json(forwarded)
        return True

    def _fail_pending(self, link: DeviceLink, error: CommandError) -> None:
        for fut in list(link.pending.values()):
            if not fut.done():
                fut.set_exception(error)
        link.pending.clear()
        for transfer in list(link.transfers.values()) + list(link.thumbs.values()):
            if transfer.done:
                continue
            transfer.error = error
            try:
                transfer.queue.put_nowait(error)
            except Exception:
                pass

    async def send_json(self, device_id: str, message: dict) -> None:
        link = self.links.get(device_id)
        if link is None:
            raise DeviceOffline("Device is offline")
        async with link.lock:
            await link.websocket.send_json(message)

    async def command(self, device_id: str, msg_type: str, payload: dict | None = None, timeout: float | None = None) -> dict:
        link = self.links.get(device_id)
        if link is None or not link.paired:
            raise DeviceOffline("Device is offline")
        request_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        link.pending[request_id] = fut
        try:
            await self.send_json(device_id, envelope(msg_type, device_id, payload or {}, request_id))
            return await asyncio.wait_for(fut, timeout or self.settings.COMMAND_TIMEOUT_SECONDS)
        except DeviceOffline:
            raise
        except asyncio.TimeoutError as exc:
            raise CommandError("TIMEOUT", "Request timed out") from exc
        finally:
            link.pending.pop(request_id, None)

    async def handle_text(self, link: DeviceLink, message: dict) -> None:
        link.touch()
        msg_type = message.get("type")
        request_id = message.get("request_id")
        payload = message.get("payload") or {}

        if msg_type == RESPONSE and request_id and request_id in link.pending:
            fut = link.pending.get(request_id)
            if fut and not fut.done():
                if payload.get("ok") is False:
                    fut.set_exception(
                        CommandError(payload.get("code") or "ERROR", payload.get("message") or "Device error")
                    )
                else:
                    fut.set_result(payload)
            return

        if msg_type == ERROR and request_id:
            err = CommandError(payload.get("code") or "ERROR", payload.get("message") or "Device error")
            fut = link.pending.get(request_id)
            if fut and not fut.done():
                fut.set_exception(err)
            transfer = link.transfers.get(request_id) or link.thumbs.get(request_id)
            if transfer is not None:
                transfer.error = err
                try:
                    transfer.queue.put_nowait(err)
                except Exception:
                    pass
            return

        if msg_type == FILE_BEGIN:
            transfer_id = payload.get("transfer_id") or request_id
            transfer = link.transfers.get(transfer_id)
            if transfer is not None:
                transfer.meta = payload
                await self._enqueue_transfer(transfer, {"_meta": payload})
            return

        if msg_type == FILE_END:
            transfer_id = payload.get("transfer_id") or request_id
            transfer = link.transfers.get(transfer_id)
            if transfer is not None:
                transfer.done = True
                size = (transfer.meta or {}).get("size")
                if isinstance(size, int) and transfer.received_bytes != size:
                    transfer.error = CommandError("INCOMPLETE_FILE", "File transfer ended before the declared size was received")
                    await self._enqueue_transfer(transfer, transfer.error)
                else:
                    await self._enqueue_transfer(transfer, None)
            return

        if msg_type == SCREEN_STATUS:
            self._broadcast_screen_json(link.device_id, message)
            return

    async def handle_binary(self, link: DeviceLink, data: bytes) -> None:
        link.touch()
        try:
            kind, transfer_uuid, seq, flags, payload = parse_frame(data)
        except ValueError:
            log.warning("ignored malformed binary frame from %s", link.device_id)
            return
        transfer_id = str(transfer_uuid)

        if kind == KIND_FILE:
            transfer = link.transfers.get(transfer_id)
            if transfer is None:
                return
            if seq != transfer.next_sequence:
                transfer.error = CommandError("INVALID_SEQUENCE", "File chunks arrived out of order")
                await self._enqueue_transfer(transfer, transfer.error)
                return
            transfer.next_sequence += 1
            transfer.received_bytes += len(payload)
            await self._enqueue_transfer(transfer, payload)
            if flags & FLAG_LAST:
                transfer.done = True
                await self._enqueue_transfer(transfer, None)
            return

        if kind == KIND_THUMB:
            transfer = link.thumbs.get(transfer_id)
            if transfer is None:
                return
            await self._enqueue_transfer(transfer, payload)
            if flags & FLAG_LAST:
                await self._enqueue_transfer(transfer, None)
            return

        if kind == KIND_SCREEN:
            try:
                width, height, rotation, jpeg = unpack_screen_payload(payload)
            except ValueError:
                return
            self._broadcast_screen_frame(link.device_id, width, height, rotation, jpeg, seq)

    def _broadcast_screen_json(self, device_id: str, message: dict) -> None:
        for queue in list(self.screen_subs.get(device_id, set())):
            try:
                queue.put_nowait(("json", message))
            except asyncio.QueueFull:
                try:
                    queue.get_nowait()
                except Exception:
                    pass
                try:
                    queue.put_nowait(("json", message))
                except Exception:
                    pass

    def _broadcast_screen_frame(
        self, device_id: str, width: int, height: int, rotation: int, jpeg: bytes, seq: int
    ) -> None:
        header = {"type": "FRAME_META", "width": width, "height": height, "rotation": rotation, "seq": seq}
        for queue in list(self.screen_subs.get(device_id, set())):
            try:
                queue.put_nowait(("frame", header, jpeg))
            except asyncio.QueueFull:
                try:
                    queue.get_nowait()
                except Exception:
                    pass
                try:
                    queue.put_nowait(("frame", header, jpeg))
                except Exception:
                    pass

    def subscribe_screen(self, device_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=self.settings.SCREEN_QUEUE_SIZE)
        self.screen_subs.setdefault(device_id, set()).add(queue)
        return queue

    def unsubscribe_screen(self, device_id: str, queue: asyncio.Queue) -> int:
        subs = self.screen_subs.get(device_id)
        if not subs:
            return 0
        subs.discard(queue)
        remaining = len(subs)
        if remaining == 0:
            self.screen_subs.pop(device_id, None)
        return remaining

    def screen_subscriber_count(self, device_id: str) -> int:
        return len(self.screen_subs.get(device_id, set()))

    async def open_transfer(self, device_id: str, transfer_id: str) -> Transfer:
        link = self.links.get(device_id)
        if link is None or not link.paired:
            raise DeviceOffline("Device is offline")
        transfer = Transfer(queue=asyncio.Queue(maxsize=self.settings.TRANSFER_QUEUE_SIZE))
        link.transfers[transfer_id] = transfer
        return transfer

    def close_transfer(self, device_id: str, transfer_id: str) -> None:
        link = self.links.get(device_id)
        if link is not None:
            transfer = link.transfers.pop(transfer_id, None) or link.thumbs.pop(transfer_id, None)
            if transfer is not None:
                transfer.closed.set()

    async def open_thumb(self, device_id: str, request_id: str) -> Transfer:
        link = self.links.get(device_id)
        if link is None or not link.paired:
            raise DeviceOffline("Device is offline")
        transfer = Transfer(queue=asyncio.Queue(maxsize=4))
        link.thumbs[request_id] = transfer
        return transfer
