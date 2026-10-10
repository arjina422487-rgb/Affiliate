(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const grid = document.getElementById("video-grid");
  const status = document.getElementById("video-status");
  const more = document.getElementById("video-more");
  const dialog = document.getElementById("video-preview");
  const player = document.getElementById("video-player");
  let cursor = null, loading = false;
  const downloadUrl = item => `/api/devices/${id}/download?file=${encodeURIComponent(item.id)}`;
  const thumbUrl = item => `/api/devices/${id}/thumb?file=${encodeURIComponent(item.id)}`;
  const size = n => n >= 1048576 ? `${(n / 1048576).toFixed(1)} MB` : `${Math.round(n / 1024)} KB`;
  const duration = ms => { const s=Math.floor((ms||0)/1000); return `${Math.floor(s/60)}:${String(s%60).padStart(2,"0")}`; };
  function add(item) {
    const card=document.createElement("button"); card.type="button"; card.className="thumb";
    const img=document.createElement("img"); img.loading="lazy"; img.alt=item.name||"video"; img.src=thumbUrl(item);
    const label=document.createElement("span"); label.textContent=`${item.name} · ${duration(item.duration_ms)} · ${size(item.size||0)}`;
    card.append(img,label); card.addEventListener("click",()=>{ player.src=downloadUrl(item)+"&inline=true"; document.getElementById("video-download").href=downloadUrl(item); document.getElementById("video-download").download=item.name||"video"; OwnerLink.bindDownload(document.getElementById("video-download"),downloadUrl(item),item,status); dialog.showModal(); player.play().catch(()=>{}); });
    grid.appendChild(card);
  }
  async function load() { if(loading)return; loading=true; status.textContent="Loading videos…"; try { const q=cursor?`&cursor=${encodeURIComponent(cursor)}`:""; const data=await OwnerLink.api(`/api/devices/${id}/videos?limit=40${q}`); (data.items||[]).forEach(add); cursor=data.cursor||null; more.classList.toggle("hidden",!data.has_more); status.textContent=grid.children.length?"":"No accessible videos found."; } catch(e){status.textContent=e.message;} finally{loading=false;} }
  more.addEventListener("click",load); document.getElementById("video-close").addEventListener("click",()=>{player.pause();player.removeAttribute("src");player.load();dialog.close();}); load();
})();
