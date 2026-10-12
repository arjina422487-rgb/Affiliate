(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const status = document.getElementById("location-status");
  const result = document.getElementById("location-result");
  const button = document.getElementById("location-refresh");
  async function load() {
    button.disabled = true; status.textContent = "Requesting location from phone…"; result.replaceChildren();
    try {
      const d = await OwnerLink.api(`/api/devices/${id}/location`);
      if (!Number.isFinite(d.latitude) || !Number.isFinite(d.longitude)) throw new Error("Location coordinates unavailable");
      const coordinates = document.createElement("p");
      coordinates.textContent = `${d.latitude.toFixed(6)}, ${d.longitude.toFixed(6)}`;
      const detail = document.createElement("p");
      detail.textContent = `Accuracy: about ${Math.round(d.accuracy_m)} m · Updated ${new Date(d.timestamp).toLocaleString()}`;
      const source = document.createElement("p");
      source.textContent = d.cached ? "Last known location — this may not be the phone's current position." : "Location returned by the phone. Check the timestamp and accuracy.";
      const map = document.createElement("a");
      map.textContent = "Open in map"; map.className = "primary"; map.target = "_blank"; map.rel = "noopener noreferrer";
      map.href = `https://www.google.com/maps?q=${encodeURIComponent(d.latitude + ',' + d.longitude)}`;
      result.append(coordinates, detail, source, map); status.textContent = "";
    } catch (e) { status.textContent = e.message; }
    finally { button.disabled = false; }
  }
  button.addEventListener("click", load);
})();
