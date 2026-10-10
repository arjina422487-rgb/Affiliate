const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

test('gallery loads every page automatically and shows album counts without eager thumbnails', async () => {
  class Element {
    constructor() { this.children = []; this.dataset = {}; this.textContent = ''; this.isConnected = true; this.classList = { add() {}, remove() {} }; }
    appendChild(child) { this.children.push(child); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    setAttribute() {}
    removeAttribute() {}
    addEventListener() {}
  }
  const elements = new Map();
  const get = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const requests = [];
  const observers = [];
  const context = {
    window: { OWNERLINK_DEVICE_ID: 'phone' }, URLSearchParams, AbortController, setTimeout,
    IntersectionObserver: class { constructor(callback) { this.callback = callback; observers.push(this); } observe() {} unobserve() {} disconnect() {} },
    document: { getElementById: get, createElement: () => new Element() },
    OwnerLink: { bindDownload() {}, api: async url => {
      requests.push(url);
      if (url.includes('cursor=')) return { items: [{ id: 'c', name: 'third' }], has_more: false };
      return { total_images: 3000, total_videos: 20, albums: [{ id: 'DCIM/Camera/', path: 'DCIM/Camera/', name: 'Camera', images: 2000, videos: 10, count: 2010 }], items: [{ id: 'a' }, { id: 'b' }], has_more: true, cursor: '2' };
    } }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app/static/js/gallery.js'), 'utf8'), context);
  for (let i = 0; i < 30 && get('gallery-grid').children.length < 3; i++) await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(requests.length, 2);
  assert.equal(get('gallery-grid').children.length, 3);
  assert.match(get('gallery-total').textContent, /3,000 photos/);
  assert.match(get('gallery-albums').children[0].children[1].textContent, /2,000 photos/);
  assert.equal(get('gallery-grid').children[0].children[0].src, undefined);
  get('gallery-albums').children[0].onclick();
  for (let i = 0; i < 30 && requests.length < 4; i++) await new Promise(resolve => setTimeout(resolve, 5));
  assert.match(requests[2], /album=DCIM%2FCamera%2F/);
  assert.match(get('gallery-album-title').textContent, /Camera.*2,010/);
});
