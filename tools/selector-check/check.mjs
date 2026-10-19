import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {Selector, Transform, QueryContext} from '@gkd-kit/selector';

const subscription = JSON.parse(readFileSync(new URL('../../gkd-app/src/main/assets/xadskip/ltt-drafts.json', import.meta.url), 'utf8'));
let count = 0;
for (const app of subscription.apps) {
  for (const group of app.groups) {
    for (const rule of group.rules) {
      for (const source of rule.matches) {
        assert.doesNotThrow(() => Selector.Companion.parse(source), `${app.id}: ${source}`);
        count++;
      }
    }
  }
}

const adapter = Transform.Companion.multiplatformBuild(
  (target, name) => (target instanceof QueryContext ? target.current : target)[name] ?? null,
  () => null,
  node => node.name,
  node => node.children,
  node => node.parent ?? null,
);
const makeNode = (text) => ({name: 'TextView', text, children: []});
const guard = makeNode('广告');
const target = makeNode('跳过');
const root = {name: 'Root', children: [guard, target]};
guard.parent = root;
target.parent = root;
const matches = ['[text="广告" || desc="广告"]', '[text="跳过" || desc="跳过"]'];
// Match the actual GKD runtime ordering: every guard must exist, last match is clicked.
function resolve() {
  let node = null;
  for (const source of matches) {
    node = adapter.querySelector(root, Selector.Companion.parse(source));
    if (!node) return null;
  }
  return node;
}
assert.equal(resolve(), target);
root.children = [target];
assert.equal(resolve(), null, 'Never click a target when its popup guard is absent');
root.children = [guard];
assert.equal(resolve(), null, 'Never click the guard when the action target is absent');
console.log(`Validated ${count} selectors with published GKD selector 0.5.23; guard/target behavior passed. Android source uses 0.6.0 and still needs build/device validation.`);
