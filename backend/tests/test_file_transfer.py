import asyncio
import hashlib
import uuid
from concurrent.futures import ThreadPoolExecutor

from app.binary import KIND_FILE, make_frame
from app.registry import CommandError, DeviceLink, Registry
from tests.conftest import login, make_settings, new_device_id, pairing_token, unpaired_hello


def test_40_mib_transfer_preserves_bytes_with_slow_consumer(tmp_path):
    async def run():
        registry = Registry(make_settings(tmp_path))
        link = DeviceLink(websocket=None, device_id="phone", paired=True)
        registry.links[link.device_id] = link
        tid = uuid.uuid4()
        transfer = await registry.open_transfer(link.device_id, str(tid))
        transfer.queue = asyncio.Queue(maxsize=2)
        expected = hashlib.sha256()
        actual = hashlib.sha256()
        count = 0

        async def consume():
            nonlocal count
            while True:
                item = await transfer.queue.get()
                if item is None:
                    return
                if isinstance(item, bytes):
                    actual.update(item)
                    count += len(item)
                    await asyncio.sleep(0.0001)

        consumer = asyncio.create_task(consume())
        await registry.handle_text(link, {"type": "FILE_BEGIN", "payload": {"transfer_id": str(tid), "size": 40 * 1024 * 1024}})
        for seq in range(640):
            chunk = bytes([seq % 251]) * (64 * 1024)
            expected.update(chunk)
            await registry.handle_binary(link, make_frame(KIND_FILE, tid, seq, chunk))
        await registry.handle_text(link, {"type": "FILE_END", "payload": {"transfer_id": str(tid)}})
        await asyncio.wait_for(consumer, 10)
        assert count == 40 * 1024 * 1024
        assert actual.digest() == expected.digest()

    asyncio.run(run())


def test_cancelled_consumer_releases_blocked_receiver(tmp_path):
    async def run():
        registry = Registry(make_settings(tmp_path))
        link = DeviceLink(websocket=None, device_id="phone", paired=True)
        registry.links[link.device_id] = link
        tid = uuid.uuid4()
        transfer = await registry.open_transfer(link.device_id, str(tid))
        transfer.queue = asyncio.Queue(maxsize=1)
        await transfer.queue.put(b"full")
        blocked = asyncio.create_task(registry.handle_binary(link, make_frame(KIND_FILE, tid, 0, b"next")))
        await asyncio.sleep(0)
        registry.close_transfer(link.device_id, str(tid))
        await asyncio.wait_for(blocked, 1)

    asyncio.run(run())


def test_incomplete_file_reports_error(tmp_path):
    async def run():
        registry = Registry(make_settings(tmp_path))
        link = DeviceLink(websocket=None, device_id="phone", paired=True)
        registry.links[link.device_id] = link
        tid = uuid.uuid4()
        transfer = await registry.open_transfer(link.device_id, str(tid))
        await registry.handle_text(link, {"type": "FILE_BEGIN", "payload": {"transfer_id": str(tid), "size": 10}})
        await registry.handle_binary(link, make_frame(KIND_FILE, tid, 0, b"short"))
        await registry.handle_text(link, {"type": "FILE_END", "payload": {"transfer_id": str(tid)}})
        assert isinstance(transfer.error, CommandError)
        assert transfer.error.code == "INCOMPLETE_FILE"

    asyncio.run(run())


def test_out_of_order_chunk_reports_error(tmp_path):
    async def run():
        registry = Registry(make_settings(tmp_path))
        link = DeviceLink(websocket=None, device_id="phone", paired=True)
        registry.links[link.device_id] = link
        tid = uuid.uuid4()
        transfer = await registry.open_transfer(link.device_id, str(tid))
        await registry.handle_binary(link, make_frame(KIND_FILE, tid, 1, b"wrong"))
        assert transfer.error.code == "INVALID_SEQUENCE"
        assert transfer.received_bytes == 0

    asyncio.run(run())


def test_download_range_headers_and_body(client):
    csrf = login(client)
    device_id, token = new_device_id(), pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "My phone")
        assert client.post("/api/devices/pair", json={"v": 1, "server": "http://testserver", "device_id": device_id, "pairing_token": token, "name": "My phone"}, headers={"X-CSRF-Token": csrf}).status_code == 200
        ws.receive_json()
        with ThreadPoolExecutor(max_workers=1) as pool:
            response = pool.submit(client.get, f"/api/devices/{device_id}/download?file=test&inline=true", headers={"Range": "bytes=4-7"})
            command = ws.receive_json()
            tid = command["payload"]["transfer_id"]
            assert command["payload"]["offset"] == 4
            assert command["payload"]["length"] == 4
            ws.send_json({"type": "FILE_BEGIN", "request_id": tid, "payload": {"transfer_id": tid, "name": "clip.mp4", "mime": "video/mp4", "size": 4, "total_size": 10, "offset": 4}})
            ws.send_bytes(make_frame(KIND_FILE, uuid.UUID(tid), 0, b"4567"))
            ws.send_json({"type": "FILE_END", "request_id": tid, "payload": {"transfer_id": tid}})
            result = response.result(timeout=10)
        assert result.status_code == 206
        assert result.content == b"4567"
        assert result.headers["content-range"] == "bytes 4-7/10"
        assert result.headers["content-length"] == "4"
        assert result.headers["content-disposition"].startswith("inline;")


def test_invalid_range_rejected_before_device_request(client):
    device_id, token = new_device_id(), pairing_token()
    csrf = login(client)
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "My phone")
        client.post("/api/devices/pair", json={"v": 1, "server": "http://testserver", "device_id": device_id, "pairing_token": token, "name": "My phone"}, headers={"X-CSRF-Token": csrf})
        ws.receive_json()
        result = client.get(f"/api/devices/{device_id}/download?file=test", headers={"Range": "bytes=8-3"})
        assert result.status_code == 416
