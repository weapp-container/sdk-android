import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { cleanSource, readRelease, versionOf } from './public-release.mjs';

export function resumeDeployment(root, path) {
  const version = versionOf(root), sourceCommit = cleanSource(root);
  const release = readRelease(join(dirname(path), 'release.json'), 'weapp-container/sdk-android', version, true);
  const deployment = JSON.parse(readFileSync(path));
  const bundle = release.assets.find(asset => asset.component === 'maven-central' && asset.coordinate === `io.github.weapp-container:weapp-container-android:${version}`);
  if (!bundle || release.sourceCommit !== sourceCommit || deployment.sdkVersion !== version || deployment.sourceCommit !== sourceCommit || deployment.bundleSha256 !== bundle.sha256 || !/^[A-Za-z0-9-]+$/.test(deployment.deploymentId ?? '')) throw Error('Central deployment does not match this exact source and signed bundle');
  return { deployment, save: value => writeFileSync(path, JSON.stringify({ ...deployment, ...value }, null, 2) + '\n') };
}
