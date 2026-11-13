import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { assetEntry, cleanSource, run, writeRelease } from '../tools/public-release.mjs';
import { resumeDeployment } from '../tools/central-receipt.mjs';

test('Central resume preserves identity and refuses changed bundle, version or source', t => {
  const root = mkdtempSync(join(tmpdir(), 'weapp-central-receipt-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const version = '0.1.0-preview.7.0';
  writeFileSync(join(root, '.gitignore'), 'artifacts/\n');
  writeFileSync(join(root, 'package.json'), JSON.stringify({ version }));
  run('git', ['init', '-q'], root); run('git', ['add', '.'], root);
  run('git', ['-c', 'user.name=Publication Test', '-c', 'user.email=publication@example.invalid', 'commit', '-qm', 'Fixture'], root);
  const out = join(root, 'artifacts'); mkdirSync(out);
  writeFileSync(join(out, 'central.zip'), 'inert signed bundle');
  const bundle = assetEntry(out, 'central.zip', { component: 'maven-central', coordinate: `io.github.weapp-container:weapp-container-android:${version}` });
  writeRelease(root, out, 'weapp-container/sdk-android', [bundle]);
  const path = join(out, 'deployment.json');
  const identity = { deploymentId: 'fixture-deployment', sdkVersion: version, sourceCommit: cleanSource(root), bundleSha256: bundle.sha256 };
  writeFileSync(path, JSON.stringify(identity));
  resumeDeployment(root, path).save({ state: 'PUBLISHED', published: true });
  const saved = JSON.parse(readFileSync(path));
  for (const [key, value] of Object.entries(identity)) assert.equal(saved[key], value);
  assert.equal(saved.published, true);
  for (const wrong of [{ sdkVersion: '0.1.0-preview.6.0' }, { sourceCommit: 'a'.repeat(40) }, { bundleSha256: 'b'.repeat(64) }]) {
    writeFileSync(path, JSON.stringify({ ...identity, ...wrong }));
    assert.throws(() => resumeDeployment(root, path), /does not match/);
  }
  writeFileSync(path, JSON.stringify(identity));
  writeFileSync(join(out, 'central.zip'), 'changed signed bundle');
  assert.throws(() => resumeDeployment(root, path), /changed/);
});
