// Local behavioral checks: no browser, network, or real user files are used.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import test from 'node:test';

const source = await readFile(new URL('../feedback.js', import.meta.url), 'utf8');
const inputNames = {
  'app-name': 'appName', 'app-version': 'appVersion', 'xadskip-version': 'xadskipVersion',
  description: 'description', email: 'email', consent: 'consent'
};

function harness(query = '?xadskipVersion=0.3.4') {
  const nodes = new Map();
  const requests = [];
  const urls = new Map();
  const revocations = [];
  let activeFile;
  let reply = async () => ({ ok: false, status: 503 });
  class Element {
    constructor(tag = 'div') {
      this.tag = tag; this.children = []; this.events = new Map(); this.attributes = {};
      this.value = ''; this.defaultValue = ''; this.hidden = false; this.type = '';
      this.classList = { toggle() {} };
    }
    addEventListener(name, handler) { this.events.set(name, handler); }
    async fire(name, extras = {}) {
      return this.events.get(name)?.({ target: this, preventDefault() {}, ...extras });
    }
    setAttribute(key, value) { this.attributes[key] = value; }
    removeAttribute(key) { delete this.attributes[key]; }
    append(...items) { this.children.push(...items); }
    replaceChildren(...items) { this.children = items; }
    setCustomValidity(message) { this.validityMessage = message; }
    reportValidity() { return [...nodes.values()].every(node => !node.validityMessage); }
    focus() { this.focused = true; }
    querySelector() { return nodes.get('issue-type'); }
    reset() { for (const id of Object.keys(inputNames)) nodes.get(id).value = nodes.get(id).defaultValue; }
    load() {}
    set src(value) {
      this._src = value;
      activeFile = urls.get(value);
      const file = activeFile;
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
    createElement(tag) { return new Element(tag); }
  };
  for (const id of [...Object.keys(inputNames), 'issue-type', 'form-panel', 'success-panel']) document.getElementById(id);
  nodes.get('issue-type').value = 'missed';
  nodes.get('consent').value = 'true';
  class FakeFormData {
    constructor() {
      this.entries = new Map([['issueType', ['missed']]]);
      for (const [id, name] of Object.entries(inputNames)) this.set(name, nodes.get(id).value);
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
  const window = { location: { href: `https://feedback.xadskip.com/feedback.html${query}` }, setTimeout, clearTimeout, addEventListener() {} };
  vm.runInNewContext(source, {
    document, window, URL: FakeURL, FormData: FakeFormData, crypto: webcrypto,
    AbortController, Uint8Array, setTimeout, clearTimeout,
    fetch: async (url, options) => { requests.push({ url, options }); return reply(); }
  });
  async function choose(files, kind = 'image') {
    const input = nodes.get(`${kind}-input`);
    input.files = files;
    await input.fire('change');
    for (let index = 0; index < files.length * 3 + 3; index++) await new Promise(resolve => setImmediate(resolve));
  }
  async function submit() {
    nodes.get('app-name').value ||= '本地测试应用';
    await nodes.get('feedback-form').fire('submit');
  }
  return { nodes, requests, revocations, choose, submit, reply: handler => { reply = handler; } };
}

const image = (name = 'sample.png', overrides = {}) => ({ name, type: 'image/png', size: 200, lastModified: 1, ...overrides });
const video = (overrides = {}) => ({ name: 'sample.mp4', type: 'video/mp4', size: 300, lastModified: 1, duration: 8, ...overrides });
const receipt = { ok: true, feedbackId: 'c1f33053-a306-4dbe-9ad4-0a5aa26857e8' };

test('only valid xadskipVersion is prefilled; startup never uploads', () => {
  const valid = harness();
  assert.equal(valid.nodes.get('xadskip-version').value, '0.3.4');
  assert.equal(valid.requests.length, 0);
  const invalid = harness('?xadskipVersion=%3Cscript%3E&appName=not-allowed');
  assert.equal(invalid.nodes.get('xadskip-version').value, '');
  assert.equal(invalid.nodes.get('app-name').value, '');
});

test('file selection only previews; removal clears attachment and revokes URL', async () => {
  const h = harness();
  await h.choose([image()]);
  assert.equal(h.requests.length, 0);
  assert.equal(h.nodes.get('attachment-list').children.length, 1);
  await h.nodes.get('attachment-list').children[0].children[3].fire('click');
  assert.equal(h.nodes.get('attachment-list').children.length, 0);
  assert.equal(h.revocations.length, 1);
  await h.submit();
  assert.equal(h.requests.length, 0);
  assert.match(h.nodes.get('attachment-status').textContent, /至少/);
});

test('image count, size, type and unreadable content are rejected locally', async () => {
  const h = harness();
  await h.choose([image('bad.png', { unreadable: true }), image('large.png', { size: 8 * 1024 * 1024 + 1 }), image('fake.png', { type: 'video/mp4' })]);
  assert.equal(h.nodes.get('attachment-list').children.length, 0);
  assert.match(h.nodes.get('attachment-status').textContent, /超过 8 MB/);
  await h.choose([image('1.png'), image('2.png'), image('3.png'), image('4.png')]);
  assert.equal(h.nodes.get('attachment-list').children.length, 3);
  assert.match(h.nodes.get('attachment-status').textContent, /最多添加 3/);
  assert.equal(h.requests.length, 0);
});

test('long or oversized video is rejected; video alone can be submitted', async () => {
  const h = harness();
  await h.choose([video({ duration: 61 })], 'video');
  assert.equal(h.nodes.get('attachment-list').children.length, 0);
  assert.match(h.nodes.get('attachment-status').textContent, /超过 60 秒/);
  await h.choose([video({ size: 30 * 1024 * 1024 + 1 })], 'video');
  assert.match(h.nodes.get('attachment-status').textContent, /超过 30 MB/);
  await h.choose([video()], 'video');
  await h.submit();
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].options.body.getAll('attachments').length, 1);
});

test('network failure preserves content and attachment; retry reuses requestId', async () => {
  const h = harness();
  await h.choose([image()]);
  h.reply(async () => { throw new Error('offline'); });
  await h.submit();
  const first = h.requests[0].options.body;
  assert.equal(first.get('xadskipVersion'), '0.3.4');
  assert.match(first.get('requestId'), /^[0-9a-f-]{36}$/);
  assert.equal(h.nodes.get('app-name').value, '本地测试应用');
  assert.equal(h.nodes.get('attachment-list').children.length, 1);
  assert.match(h.nodes.get('submit-status').textContent, /保留/);
  await h.submit();
  assert.equal(h.requests[1].options.body.get('requestId'), first.get('requestId'));
  h.nodes.get('description').value = '修改后的内容';
  await h.nodes.get('feedback-form').fire('input', { target: h.nodes.get('description') });
  await h.submit();
  assert.notEqual(h.requests[2].options.body.get('requestId'), first.get('requestId'));
});

test('2xx without ok=true and feedbackId is not a success', async () => {
  for (const result of [{}, { id: 'old-contract' }, { ok: false, feedbackId: 'bad' }, { ok: true, feedbackId: ' ' }]) {
    const h = harness();
    await h.choose([image()]);
    h.reply(async () => ({ ok: true, status: 200, json: async () => result }));
    await h.submit();
    assert.notEqual(h.nodes.get('form-panel').hidden, true);
    assert.equal(h.nodes.get('attachment-list').children.length, 1);
    assert.match(h.nodes.get('submit-status').textContent, /无法确认/);
  }
});

test('201 receipt shows confirmation only after server acknowledgement', async () => {
  const h = harness();
  await h.choose([image()]);
  h.reply(async () => ({ ok: true, status: 201, json: async () => receipt }));
  await h.submit();
  assert.equal(h.requests[0].url, '/api/feedback');
  assert.equal(h.requests[0].options.method, 'POST');
  assert.equal(h.nodes.get('feedback-id').textContent, receipt.feedbackId);
  assert.equal(h.nodes.get('form-panel').hidden, true);
  assert.equal(h.nodes.get('success-panel').hidden, false);
  assert.equal(h.nodes.get('attachment-list').children.length, 0);
  assert.equal(h.nodes.get('app-name').value, '');
  assert.equal(h.revocations.length, 1);
});

test('server rejection preserves content and never trusts its message as HTML', async () => {
  const h = harness();
  await h.choose([image()]);
  h.reply(async () => ({ ok: false, status: 413, json: async () => ({ error: { message: '<script>bad</script>' } }) }));
  await h.submit();
  assert.match(h.nodes.get('submit-status').textContent, /服务器接收上限/);
  assert.equal(h.nodes.get('attachment-list').children.length, 1);
  assert.equal(h.nodes.get('feedback-fields').disabled, false);
});
