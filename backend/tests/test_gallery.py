from tests.conftest import login, new_device_id, pairing_token, unpaired_hello


def test_gallery_preserves_album_counts_and_forwards_folder_filter(client, app, monkeypatch):
    csrf = login(client)
    device_id, token = new_device_id(), pairing_token()
    with client.websocket_connect("/ws/device") as ws:
        unpaired_hello(ws, device_id, token, "My phone")
        assert client.post("/api/devices/pair", json={"v": 1, "server": "http://testserver", "device_id": device_id, "pairing_token": token, "name": "My phone"}, headers={"X-CSRF-Token": csrf}).status_code == 200
        ws.receive_json()
        captured = {}

        async def gallery(_device_id, command, payload):
            assert command == "LIST_GALLERY"
            captured.update(payload)
            return {"data": {"items": [], "total_images": 12000, "total_videos": 300, "albums": [{"id": "DCIM/Camera/", "images": 8000, "videos": 100}]}}

        monkeypatch.setattr(app.state.registry, "command", gallery)
        response = client.get(f"/api/devices/{device_id}/gallery?album=DCIM%2FCamera%2F&limit=999")
        assert response.status_code == 200
        assert captured == {"cursor": None, "limit": 200, "album": "DCIM/Camera/"}
        assert response.json()["total_images"] == 12000
        assert response.json()["albums"][0]["images"] == 8000
        page = client.get(f"/device/{device_id}/gallery")
        assert 'id="gallery-total"' in page.text
        assert 'id="gallery-albums"' in page.text
