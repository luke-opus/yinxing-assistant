import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';

const source = await readFile(new URL('../app.js', import.meta.url), 'utf8');

function page(ready, recipient = 'elder', consent = true) {
  const handlers = new Map();
  const downloads = [];
  const button = { addEventListener: (event, handler) => handlers.set(event, handler) };
  const status = {};
  const checkbox = { checked: consent, addEventListener() {} };
  const document = {
    body: { dataset: { releaseReady: String(ready) }, append() {} },
    getElementById: id => ({ 'download-apk': button, 'download-status': status, 'download-consent': checkbox })[id],
    querySelector: () => ({ value: recipient }),
    querySelectorAll: () => [],
    createElement: () => ({ click() { downloads.push(this.href); }, remove() {} }),
  };
  vm.runInNewContext(source, {
    document, window: { location: { hash: '' }, addEventListener() {} },
    setTimeout: fn => fn(),
  });
  return { button, downloads, click: () => handlers.get('click')() };
}

test('source preview never starts APK downloads even after purpose consent', () => {
  const preview = page(false);
  assert.equal(preview.button.disabled, true);
  preview.click();
  assert.equal(preview.downloads.length, 0);
});

test('prepared release permits a consented download and still respects the user selection', () => {
  const ready = page(true);
  assert.equal(ready.button.disabled, false);
  ready.click();
  assert.deepEqual(ready.downloads, ['downloads/XAdSkip-0.3.4.apk']);
  for (const [recipient, consent] of [['elder', false], ['other', true]]) {
    const blocked = page(true, recipient, consent);
    assert.equal(blocked.button.disabled, true);
    blocked.click();
    assert.equal(blocked.downloads.length, 0);
  }
});
