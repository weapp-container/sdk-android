import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = readFileSync(new URL('../library/src/main/java/com/sonofmagic/weapp/container/sdk/WeappContainerSession.kt', import.meta.url), 'utf8');
test('public journal queries return before native creation, event drain and presentation', () => {
  const method = source.slice(source.indexOf('@JvmOverloads fun executeCommand('), source.indexOf('/** Same-size native observations'));
  const branch = method.indexOf('if (operation == "queryDevPackAttempt")');
  assert.ok(branch >= 0, 'queryDevPackAttempt requires an explicit passive early-return branch');
  assert.ok(branch < method.indexOf('if (handle == 0L) createNative'), 'query must refuse a missing native owner without allocating it');
  const beforeCreation = method.slice(branch, method.indexOf('if (handle == 0L) createNative'));
  assert.match(beforeCreation, /return@task command\(operation, copied\)/);
  assert.match(beforeCreation, /handle == 0L/);
  assert.doesNotMatch(beforeCreation, /drainEvents|present\(|loaded\s*=/);
});

test('layout preparation queries journal and state in one passive owner-thread task', () => {
  const method = source.slice(source.indexOf('@JvmOverloads fun hostLayoutPreparationState('), source.indexOf('/** Same-size native observations'));
  assert.match(method, /return task \{/);
  assert.match(method, /handle == 0L.*ERR_NOT_READY/);
  assert.match(method, /command\("queryDevPackAttempt", identity\)/);
  assert.match(method, /JSONObject\(\)\.put\("state", command\("state"\)\)\.put\("journalReceipt", receipt \?: JSONObject.NULL\)/);
  assert.ok(method.indexOf('command("queryDevPackAttempt"') < method.indexOf('command("state"'));
  assert.doesNotMatch(method, /createNative|drainEvents|present\(|ensurePresenter|loaded\s*=|observeAtomicLayout|applyForeground/);
});
