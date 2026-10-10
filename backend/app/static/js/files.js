(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const body = document.getElementById("file-body");
  const status = document.getElementById("files-status");
  const pathBar = document.getElementById("path-bar");
  const parentButton = document.getElementById("files-parent");
  const search = document.getElementById("files-search");
  const sort = document.getElementById("files-sort");
  const stack = [{ id: null, name: "Storage" }];
  let items = [], generation = 0, controller;
  const downloadUrl = fileId => `/api/devices/${id}/download?file=${encodeURIComponent(fileId)}`;

  function renderPath() {
    pathBar.replaceChildren();
    stack.forEach((part, idx) => {
      if (idx) pathBar.appendChild(document.createTextNode(" / "));
      const a = document.createElement("a");
      a.href = "#";
      a.textContent = part.name;
      a.addEventListener("click", ev => {
        ev.preventDefault(); stack.splice(idx + 1); load();
      });
      pathBar.appendChild(a);
    });
    parentButton.hidden = stack.length <= 1;
  }

  function render() {
    body.replaceChildren();
    const query = search.value.trim().toLocaleLowerCase();
    const visible = items.filter(item => item.name.toLocaleLowerCase().includes(query));
    visible.sort((a, b) => {
      if (!!a.directory !== !!b.directory) return a.directory ? -1 : 1;
      if (sort.value === "size") return (b.size || 0) - (a.size || 0) || a.name.localeCompare(b.name);
      if (sort.value === "modified") return (b.modified || 0) - (a.modified || 0) || a.name.localeCompare(b.name);
      return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" });
    });
    status.textContent = items.length ? `${visible.length} of ${items.length} items` : "This folder is empty.";
    const fragment = document.createDocumentFragment();
    for (const item of visible) {
      const tr = document.createElement("tr");
      const cells = Array.from({ length: 5 }, () => document.createElement("td"));
      if (item.directory) {
        const a = document.createElement("a");
        a.href = "#"; a.textContent = item.name + "/";
        a.addEventListener("click", ev => {
          ev.preventDefault(); stack.push({ id: item.id, name: item.name }); load();
        });
        cells[0].appendChild(a);
      } else {
        cells[0].textContent = item.name;
        const url = downloadUrl(item.id);
        if (/^(image\/|video\/)/.test(item.mime || "") || item.mime === "application/pdf") {
          const preview = document.createElement("a");
          preview.href = url + "&inline=true";
          preview.textContent = "Preview";
          preview.target = "_blank"; preview.rel = "noopener noreferrer";
          cells[4].append(preview, document.createTextNode(" · "));
        }
        const a = document.createElement("a");
        a.href = url; a.textContent = "Download";
        a.setAttribute("download", item.name || "download");
        OwnerLink.bindDownload(a, url, item, status);
        cells[4].appendChild(a);
      }
      cells[1].textContent = item.directory ? "Folder" : item.mime || "File";
      cells[2].textContent = item.directory ? "" : OwnerLink.formatSize(item.size);
      cells[3].textContent = item.modified ? new Date(item.modified).toLocaleString() : "—";
      tr.append(...cells); fragment.appendChild(tr);
    }
    body.appendChild(fragment);
  }

  async function load() {
    const current = ++generation;
    if (controller) controller.abort();
    controller = new AbortController();
    items = []; search.value = ""; body.replaceChildren(); renderPath();
    status.textContent = "Reading folder from phone…";
    const folder = stack[stack.length - 1].id;
    try {
      const q = folder ? `?folder=${encodeURIComponent(folder)}` : "";
      const data = await OwnerLink.api(`/api/devices/${id}/files${q}`, { signal: controller.signal });
      if (current !== generation) return;
      if (data.ok === false) throw new Error(data.message || "Folder access denied");
      items = data.items || []; render();
    } catch (err) {
      if (current !== generation || err.name === "AbortError") return;
      status.textContent = err.message;
    }
  }
  parentButton.addEventListener("click", () => {
    if (stack.length > 1) { stack.pop(); load(); }
  });
  document.getElementById("files-refresh").addEventListener("click", load);
  search.addEventListener("input", render);
  sort.addEventListener("change", render);
  load();
})();
