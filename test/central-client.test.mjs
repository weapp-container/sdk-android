import test from 'node:test';
import assert from 'node:assert/strict';
import { completeDeployment } from '../tools/central-client.mjs';
test('Central validates before publishing and resumes a published deployment without another upload', async () => {
  const calls = [], states = ['PENDING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED'], saved = [];
  const request = async (url, options) => {
    calls.push({ url, method: options.method });
    return { ok: true, json: async () => ({ deploymentState: states.shift() }) };
  };
  await completeDeployment('deployment-1', {}, value => saved.push(value), { request, pause: async () => {} });
  assert.deepEqual(saved.map(value => value.state), ['PENDING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED']);
  assert.equal(calls.filter(call => call.url.includes('/deployment/')).length, 1);
  assert.equal(saved.at(-1).published, true);
  await completeDeployment('deployment-1', {}, () => {}, { request: async url => {
    assert.ok(url.includes('/status?')); return { ok: true, json: async () => ({ deploymentState: 'PUBLISHED' }) };
  } });
});
test('failed validation and unavailable status never request publication', async () => {
  for (const state of ['FAILED', 'UNAVAILABLE']) await assert.rejects(() => completeDeployment('deployment-1', {}, () => {}, {
    request: async url => { assert.ok(url.includes('/status?')); return { ok: state !== 'UNAVAILABLE', status: 503, json: async () => ({ deploymentState: state }) }; }
  }), /validation failed|status failed/);
});
