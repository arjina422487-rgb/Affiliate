(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const grid = document.getElementById("gallery-grid");
  const status = document.getElementById("gallery-status");
  const more = document.getElementById("load-more");
  const dialog = document.getElementById("preview");
  const albums = document.getElementById("gallery-albums");
  const total = document.getElementById("gallery-total");
  const heading = document.getElementById("gallery-album-title");
  const format = n => Number(n || 0).toLocaleString();
  let generation = 0, selection = null, cursor = null, loading = false;
  let selectedCount = null, count = 0, controller = null;
  let activeThumbnails = 0, thumbnailQueue = [];
  const thumbUrl = item => `/api/devices/${id}/thumb?file=${encodeURIComponent(item.id)}`;
  const downloadUrl = item => `/api/devices/${id}/download?file=${encodeURIComponent(item.id)}`;
  function drainThumbnails() {
    while (activeThumbnails < 4 && thumbnailQueue.length) {
      const entry = thumbnailQueue.shift();
      if (entry.generation !== generation || !entry.img.isConnected) continue;
      activeThumbnails++;
      const done = () => { entry.img.onload = entry.img.onerror = null; activeThumbnails--; drainThumbnails(); };
      entry.img.onload = done; entry.img.onerror = done;
      entry.img.src = entry.url;
    }
  }
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) if (entry.isIntersecting) {
      observer.unobserve(entry.target);
      thumbnailQueue.push({ img: entry.target, url: entry.target.dataset.url, generation });
    }
    drainThumbnails();
  }, { rootMargin: "250px" });
  function addItem(item) {
    const btn = document.createElement("button");
    btn.type = "button"; btn.className = "thumb";
    btn.title = item.name || "media";
    const img = document.createElement("img");
    img.alt = item.name || "media"; img.decoding = "async";
    img.dataset.url = thumbUrl(item);
    btn.appendChild(img); grid.appendChild(btn); observer.observe(img);
    btn.addEventListener("click", () => {
      document.getElementById("preview-img").src = item.is_video ? thumbUrl(item) : downloadUrl(item) + "&inline=true";
      const dl = document.getElementById("preview-download");
      dl.href = downloadUrl(item); dl.setAttribute("download", item.name || "download");
      OwnerLink.bindDownload(dl, downloadUrl(item), item, status);
      dialog.showModal();
    });
  }
  function renderAlbums(data) {
    if (!Array.isArray(data.albums)) return;
    total.textContent = `${format(data.total_images)} photos · ${format(data.total_videos)} videos · ${format(data.albums.length)} albums`;
    albums.replaceChildren();
    for (const album of data.albums) {
      const button = document.createElement("button"); button.type = "button";
      button.setAttribute("aria-pressed", String(selection === album.id));
      const name = document.createElement("strong"); name.textContent = album.name;
      const amount = document.createElement("span"); amount.textContent = `${format(album.images)} photos · ${format(album.videos)} videos`;
      const path = document.createElement("span"); path.textContent = album.path || "Other";
      button.append(name, amount, path);
      button.onclick = () => selectAlbum(album.id, album.name, album.count);
      albums.appendChild(button);
    }
    if (selection === null) selectedCount = Number(data.total_images || 0) + Number(data.total_videos || 0);
    else selectedCount = Number(data.albums.find(a => a.id === selection)?.count || 0);
    heading.textContent = `${selection === null ? "All photos and videos" : heading.dataset.name} · ${format(selectedCount)} items`;
  }
  async function load(run = generation) {
    if (loading || run !== generation) return;
    loading = true; more.classList.add("hidden");
    try {
      do {
        status.textContent = `Loading photo list: ${format(count)}${selectedCount === null ? "" : " / " + format(selectedCount)}`;
        const params = new URLSearchParams({ limit: "200" });
        if (cursor !== null) params.set("cursor", cursor);
        if (selection !== null) params.set("album", selection);
        const data = await OwnerLink.api(`/api/devices/${id}/gallery?${params}`, { signal: controller.signal });
        if (run !== generation) return;
        renderAlbums(data);
        const items = data.items || [];
        // Render metadata automatically. Image bytes are requested only near the viewport.
        items.forEach(addItem); count += items.length;
        const next = data.cursor == null ? null : String(data.cursor);
        if (data.has_more && (next === null || next === cursor || !items.length)) throw new Error("Phone returned an invalid gallery cursor");
        cursor = next;
        if (!data.has_more) { cursor = null; break; }
        await new Promise(resolve => setTimeout(resolve, 0));
      } while (run === generation);
      status.textContent = count ? `${format(count)} items ready. Scroll to view photos.` : "No accessible media in this album.";
    } catch (error) {
      if (run === generation && error.name !== "AbortError") {
        status.textContent = `${error.message}. ${format(count)} items loaded; retry to continue.`;
        more.classList.remove("hidden");
      }
    } finally { if (run === generation) loading = false; }
  }
  function selectAlbum(album, name, amount) {
    generation++; controller?.abort(); controller = new AbortController();
    selection = album; selectedCount = amount; cursor = null; loading = false; count = 0;
    observer.disconnect(); thumbnailQueue = []; grid.replaceChildren();
    heading.dataset.name = name; heading.textContent = name;
    load(generation);
  }
  more.onclick = () => load();
  document.getElementById("gallery-all").onclick = () => selectAlbum(null, "All photos and videos", null);
  document.getElementById("gallery-refresh").onclick = () => selectAlbum(selection, heading.dataset.name || "All photos and videos", null);
  document.getElementById("preview-close").onclick = () => { dialog.close(); document.getElementById("preview-img").removeAttribute("src"); };
  selectAlbum(null, "All photos and videos", null);
})();
