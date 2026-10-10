(function () {
  // Small range requests survive proxy timeouts; retry only the unfinished part.
  OwnerLink.bindDownload = function (link, url, item, status) {
    link.onclick = async function (event) {
      const size = Number(item.size);
      if (!Number.isSafeInteger(size) || size <= 8 * 1024 * 1024 || size > 128 * 1024 * 1024) return;
      event.preventDefault();
      if (link.dataset.downloading) return;
      link.dataset.downloading = "true";
      const original = link.textContent;
      const parts = [];
      let received = 0;
      try {
        while (received < size) {
          const end = Math.min(received + 4 * 1024 * 1024, size) - 1;
          let part;
          for (let attempt = 0; attempt < 5; attempt++) {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 180000);
            try {
              link.textContent = `Downloading ${Math.floor(received * 100 / size)}%`;
              const response = await fetch(url, { headers: { Range: `bytes=${received}-${end}` }, credentials: "same-origin", cache: "no-store", signal: controller.signal });
              if ([401, 403, 404, 416].includes(response.status)) {
                const failure = new Error("Download unavailable. Check dashboard login, file permissions and that the phone app is updated.");
                failure.permanent = true;
                throw failure;
              }
              if (response.status !== 206 || response.headers.get("Content-Range") !== `bytes ${received}-${end}/${size}`) throw new Error("Phone did not return the requested file range, or the file changed.");
              part = await response.blob();
              if (part.size !== end - received + 1) throw new Error("Incomplete download part");
              break;
            } catch (error) {
              if (error.permanent || attempt === 4) throw error;
              link.textContent = "Connection interrupted · retrying…";
              await new Promise(resolve => setTimeout(resolve, 1000 * (2 ** attempt)));
            } finally { clearTimeout(timer); }
          }
          parts.push(part);
          received += part.size;
        }
        const blob = new Blob(parts, { type: item.mime || "application/octet-stream" });
        const objectUrl = URL.createObjectURL(blob);
        const save = document.createElement("a");
        save.href = objectUrl;
        save.download = item.name || "download";
        document.body.appendChild(save);
        save.click();
        save.remove();
        setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
        if (status) status.textContent = "Complete file received. Your browser will save the download.";
      } catch (error) {
        if (status) status.textContent = `Download failed: ${error.message}. Retry after the phone reconnects.`;
      } finally {
        parts.length = 0;
        delete link.dataset.downloading;
        link.textContent = original;
      }
    };
  };
})();
