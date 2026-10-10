const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

test('35 MiB download retries an incomplete range and saves every byte', async () => {
  const original = Buffer.alloc(35 * 1024 * 1024);
  for (let i = 0; i < original.length; i++) original[i] = i % 251;
  const ranges = [];
  let interrupted = false, saved, clicked = false;
  const context = {
    OwnerLink: {}, Blob, AbortController,
    setTimeout: (callback, delay) => delay < 20000 ? setTimeout(callback, 0) : 0,
    clearTimeout,
    URL: { createObjectURL: blob => { saved = blob; return 'blob:test'; }, revokeObjectURL() {} },
    document: { body: { appendChild() {} }, createElement: () => ({ click() { clicked = true; }, remove() {} }) },
    fetch: async (url, options) => {
      const match = options.headers.Range.match(/^bytes=(\d+)-(\d+)$/);
      const start = Number(match[1]), end = Number(match[2]);
      ranges.push(start);
      let bytes = original.subarray(start, end + 1);
      if (start === 16 * 1024 * 1024 && !interrupted) { interrupted = true; bytes = bytes.subarray(0, 1024); }
      return { status: 206, headers: { get: () => `bytes ${start}-${end}/${original.length}` }, blob: async () => new Blob([bytes]) };
    }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app/static/js/downloads.js'), 'utf8'), context);
  const link = { dataset: {}, textContent: 'Download' }, status = { textContent: '' };
  context.OwnerLink.bindDownload(link, '/download', { size: original.length, name: 'file.bin' }, status);
  await link.onclick({ preventDefault() {} });
  assert.equal(clicked, true);
  assert.equal(saved.size, original.length);
  const hash = buffer => crypto.createHash('sha256').update(buffer).digest('hex');
  assert.equal(hash(Buffer.from(await saved.arrayBuffer())), hash(original));
  assert.equal(ranges.filter(start => start === 16 * 1024 * 1024).length, 2);
  assert.equal(link.textContent, 'Download');
  assert.equal(link.dataset.downloading, undefined);
});

test('unauthorized download never saves a partial file or retries', async () => {
  let requests = 0;
  const context = { OwnerLink: {}, Blob, AbortController, setTimeout: () => 0, clearTimeout,
    fetch: async () => { requests++; return { status: 401 }; } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app/static/js/downloads.js'), 'utf8'), context);
  const link = { dataset: {}, textContent: 'Download' }, status = { textContent: '' };
  context.OwnerLink.bindDownload(link, '/download', { size: 35 * 1024 * 1024 }, status);
  await link.onclick({ preventDefault() {} });
  assert.equal(requests, 1);
  assert.match(status.textContent, /Download failed/);
});
