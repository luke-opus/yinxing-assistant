// Behavioral checks use a simulated DOM, synthetic files and fake API responses only.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import test from 'node:test';

const source = await readFile(new URL('../wishes.js', import.meta.url), 'utf8');
const emptyReply = { ok: true, items: [], nextCursor: null };
const receipt = { ok: true, wishId: 'wish_0123456789', status: 'pending_review', createdAt: '2026-11-18T12:00:00Z' };
const response = value => ({ ok: true, status: 200, json: async () => value });

function harness(listResponse = async () => response(emptyReply)) {
  const nodes = new Map(), requests = [], urls = new Map(), revocations = [];
  let postResponse = async () => ({ ok: false, status: 503 });
  let getResponse = listResponse;
  class Element {
    constructor(tag = 'div') {
      this.tag = tag; this.children = []; this.events = new Map(); this.attributes = {};
      this.value = ''; this.defaultValue = ''; this.checked = false; this.hidden = false; this.type = '';
      this.classList = { toggle() {} };
    }
    addEventListener(name, handler) { this.events.set(name, handler); }
    async fire(name, extras = {}) { return this.events.get(name)?.({ target: this, preventDefault() {}, ...extras }); }
    setAttribute(key, value) { this.attributes[key] = value; }
    removeAttribute(key) { delete this.attributes[key]; }
    append(...items) { this.children.push(...items); }
    replaceChildren(...items) { this.children = items; }
    setCustomValidity(message) { this.validityMessage = message; }
    reportValidity() { return [...nodes.values()].every(node => !node.validityMessage); }
    focus() { this.focused = true; }
    reset() { for (const id of ['description', 'email']) nodes.get(id).value = ''; nodes.get('consent').checked = false; }
    load() {}
    set src(value) {
      this._src = value; const file = urls.get(value);
      queueMicrotask(() => {
        if (file?.unreadable) this.onerror?.();
        else if (this.tag === 'img') this.onload?.();
        else if (this.tag === 'video') { this.duration = file?.duration ?? 8; this.onloadedmetadata?.(); }
      });
    }
    get src() { return this._src; }
  }
  const document = {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); },
    createElement(tag) { return new Element(tag); },
  };
  for (const id of ['description', 'email', 'consent', 'form-panel', 'success-panel', 'list-empty']) document.getElementById(id);
  nodes.get('success-panel').hidden = nodes.get('list-empty').hidden = true;
  class FakeFormData {
    constructor() {
      this.entries = new Map();
      for (const name of ['description', 'email']) this.set(name, nodes.get(name).value);
      if (nodes.get('consent').checked) this.set('consent', 'true');
    }
    set(key, value) { this.entries.set(key, [value]); }
    get(key) { return this.entries.get(key)?.[0]; }
    getAll(key) { return this.entries.get(key) || []; }
    append(key, value) { this.entries.set(key, [...this.getAll(key), value]); }
  }
  class FakeURL extends URL {
    static createObjectURL(file) { const url = `blob:test-${urls.size}`; urls.set(url, file); return url; }
    static revokeObjectURL(url) { revocations.push(url); }
  }
  const window = { setTimeout, clearTimeout, addEventListener() {} };
  vm.runInNewContext(source, { document, window, URL: FakeURL, FormData: FakeFormData, crypto: webcrypto,
    AbortController, Uint8Array, setTimeout, clearTimeout,
    fetch: async (url, options) => { requests.push({ url, options }); return options.method === 'POST' ? postResponse() : getResponse(url); },
  });
  async function flush() { for (let n = 0; n < 8; n++) await new Promise(resolve => setImmediate(resolve)); }
  async function choose(files, kind = 'image') {
    const input = nodes.get(`${kind}-input`); input.files = files;
    await input.fire('change'); await flush();
  }
  async function submit(text = '希望按钮更大一点', agreed = true) {
    nodes.get('description').value = text; nodes.get('consent').checked = agreed;
    await nodes.get('wish-form').fire('submit');
  }
  return { nodes, requests, revocations, choose, submit, flush,
    posts: () => requests.filter(item => item.options.method === 'POST'),
    reply: handler => { postResponse = handler; },
    listReply: handler => { getResponse = handler; },
    refresh: async () => { await nodes.get('refresh-wishes').fire('click'); await flush(); },
    more: async () => { await nodes.get('load-more').fire('click'); await flush(); },
  };
}

const image = (name = 'idea.png', extra = {}) => ({ name, type: 'image/png', size: 120, lastModified: 1, ...extra });
const video = extra => ({ name: 'idea.mp4', type: 'video/mp4', size: 200, lastModified: 1, duration: 8, ...extra });
const publicItem = extra => ({ id: 'public_1234', description: '希望按钮更大', status: 'pending', developerNote: '',
  createdAt: '2026-11-18T12:00:00Z', updatedAt: '2026-11-18T13:00:00Z', ...extra });
const textOf = node => [node.textContent || '', ...node.children.map(textOf)].join(' ');

test('startup reads only approved list; confirmed empty response is a real empty state', async () => {
  const h = harness(); await h.flush();
  assert.equal(h.posts().length, 0);
  assert.equal(h.requests[0].url, '/api/wishes?limit=20');
  assert.equal(h.nodes.get('list-empty').hidden, false);
  assert.equal(h.nodes.get('wish-list').children.length, 0);
});

test('text-only submission succeeds only after valid pending-review receipt', async () => {
  const h = harness(); await h.flush();
  h.reply(async () => ({ ...response(receipt), status: 201 }));
  await h.submit();
  assert.equal(h.posts().length, 1);
  const sent = h.posts()[0];
  assert.equal(sent.url, '/api/wishes');
  assert.equal(sent.options.body.get('description'), '希望按钮更大一点');
  assert.equal(sent.options.body.getAll('attachments').length, 0);
  assert.equal(sent.options.body.get('consent'), 'true');
  assert.equal(h.nodes.get('wish-id').textContent, receipt.wishId);
  assert.equal(h.nodes.get('success-panel').hidden, false);
  assert.equal(h.nodes.get('form-panel').hidden, true);
  assert.equal(h.nodes.get('wish-list').children.length, 0, 'unreviewed submission must not be inserted into public list');
});

test('blank description or missing consent never sends a request', async () => {
  const h = harness(); await h.flush();
  await h.submit('   '); assert.equal(h.posts().length, 0);
  await h.submit('希望按钮更大', false); assert.equal(h.posts().length, 0);
});

test('optional media only previews before submit, enforcing image and video limits', async () => {
  const h = harness(); await h.flush();
  await h.choose([image('large.png', { size: 8 * 1024 * 1024 + 1 }), image('bad.png', { unreadable: true })]);
  assert.equal(h.nodes.get('attachment-list').children.length, 0);
  await h.choose([video({ duration: 61 })], 'video');
  assert.equal(h.nodes.get('attachment-list').children.length, 0);
  await h.choose([image('1.png'), image('2.png'), image('3.png'), image('4.png')]);
  assert.equal(h.nodes.get('attachment-list').children.length, 3);
  await h.choose([video({})], 'video');
  assert.equal(h.nodes.get('attachment-list').children.length, 4);
  assert.equal(h.posts().length, 0);
  await h.submit();
  assert.equal(h.posts()[0].options.body.getAll('attachments').length, 4);
});

test('network failure preserves text and files; retry reuses idempotency key', async () => {
  const h = harness(); await h.flush(); await h.choose([image()]);
  h.reply(async () => { throw new Error('offline'); });
  await h.submit('希望界面再简洁一点');
  const first = h.posts()[0].options.body.get('requestId');
  assert.equal(h.nodes.get('description').value, '希望界面再简洁一点');
  assert.equal(h.nodes.get('attachment-list').children.length, 1);
  assert.equal(h.nodes.get('success-panel').hidden, true);
  await h.submit('希望界面再简洁一点');
  assert.equal(h.posts()[1].options.body.get('requestId'), first);
  await h.nodes.get('wish-form').fire('input', { target: h.nodes.get('description') });
  await h.submit('修改后的愿望');
  assert.notEqual(h.posts()[2].options.body.get('requestId'), first);
});

test('HTTP rejection, malformed JSON and incomplete 2xx receipts cannot show success', async () => {
  const replies = [
    async () => ({ ok: false, status: 413, json: async () => ({ message: '<script>bad</script>' }) }),
    async () => ({ ok: true, status: 200, json: async () => { throw new Error('not JSON'); } }),
    async () => response({ ok: true, feedbackId: 'wrong-api' }),
    async () => response({ ...receipt, status: 'completed' }),
    async () => response({ ...receipt, wishId: '' }),
  ];
  for (const reply of replies) {
    const h = harness(); await h.flush(); h.reply(reply); await h.submit();
    assert.equal(h.nodes.get('success-panel').hidden, true);
    assert.equal(h.nodes.get('form-panel').hidden, false);
    assert.match(h.nodes.get('submit-status').textContent, /保留/);
    assert.equal(h.nodes.get('description').value, '希望按钮更大一点');
  }
});

test('public cards use server status, plain text and developer notes without attachments or email', async () => {
  const h = harness(async () => response({ ok: true, items: [publicItem({
    status: 'completed', description: '<img src=x onerror=alert(1)>', developerNote: '<b>已实现</b>',
    email: 'private@example.invalid', attachments: [{ url: '/private/file.png' }],
  })], nextCursor: null }));
  await h.flush();
  const card = h.nodes.get('wish-list').children[0];
  assert.match(textOf(card), /已完成 ✓/);
  assert.match(textOf(card), /<img src=x onerror=alert\(1\)>/);
  assert.match(textOf(card), /<b>已实现<\/b>/);
  assert.doesNotMatch(textOf(card), /private@example|\/private\/file/);
  assert.equal(card.children[1].tag, 'p');
});

test('list failure is not empty success; later refresh failure preserves previous cards', async () => {
  const h = harness(async () => ({ ok: false, status: 503 })); await h.flush();
  assert.equal(h.nodes.get('list-empty').hidden, true);
  assert.match(h.nodes.get('list-status').textContent, /暂时读不到/);
  h.listReply(async () => response({ ok: true, items: [publicItem({ status: 'planned' })], nextCursor: null }));
  await h.refresh();
  assert.match(textOf(h.nodes.get('wish-list')), /计划中/);
  h.listReply(async () => { throw new Error('offline'); });
  await h.refresh();
  assert.equal(h.nodes.get('wish-list').children.length, 1);
  assert.match(h.nodes.get('list-status').textContent, /仍保留/);
});

test('pagination uses only opaque returned cursor and merges duplicate ids', async () => {
  const cursor = 'opaque+/=?token';
  const h = harness(async () => response({ ok: true, items: [publicItem({})], nextCursor: cursor }));
  await h.flush();
  h.listReply(async url => {
    assert.equal(url, '/api/wishes?limit=20&cursor=' + encodeURIComponent(cursor));
    return response({ ok: true, items: [publicItem({}), publicItem({ id: 'second', status: 'planned' })], nextCursor: null });
  });
  await h.more();
  assert.equal(h.nodes.get('wish-list').children.length, 2);
  assert.equal(h.nodes.get('load-more').hidden, true);
});
