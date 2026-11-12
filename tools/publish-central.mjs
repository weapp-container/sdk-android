import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { completeDeployment } from './central-client.mjs';
import { assetEntry, cleanSource, filesUnder, run, sha256, versionOf, writeRelease } from './public-release.mjs';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2), option = name => { const i = args.indexOf(name); return i < 0 ? undefined : args[i + 1]; };
if (option('--resume')) {
  const path = resolve(option('--resume'));
  const deployment = JSON.parse(readFileSync(path));
  const username = process.env.MAVEN_CENTRAL_USERNAME, password = process.env.MAVEN_CENTRAL_PASSWORD;
  if (!username || !password) throw Error('Central Portal user-token credentials are required');
  await completeDeployment(deployment.deploymentId, { Authorization: `Bearer ${Buffer.from(`${username}:${password}`).toString('base64')}` }, value => writeFileSync(path, JSON.stringify(value, null, 2) + '\n'));
  process.exit(0);
}
if (!option('--sdk') || !option('--out')) throw Error('Usage: publish-central.mjs --sdk <verified Android installation> --out <new directory> [--execute]');
const sourceCommit = cleanSource(root), version = versionOf(root);
const sdk = resolve(option('--sdk')), out = resolve(option('--out'));
const receipt = JSON.parse(readFileSync(join(sdk, 'distribution.json')));
if (receipt.status !== 'passed' || receipt.sdkVersion !== version || receipt.sourceCommit !== sourceCommit || receipt.sourceDirty !== false) throw Error('A successful clean Android SDK consumer receipt is required');
for (const entry of receipt.mavenArtifacts) if (sha256(readFileSync(join(sdk, 'maven', entry.file))) !== entry.sha256) throw Error(`Maven artifact changed: ${entry.file}`);
const coordinate = `io/github/weapp-container/weapp-container-android/${version}`;
const input = join(sdk, 'maven', coordinate), stem = `weapp-container-android-${version}`;
for (const suffix of ['.aar', '.pom', '-sources.jar', '-javadoc.jar']) if (!existsSync(join(input, stem + suffix))) throw Error(`Missing Central artifact ${suffix}`);
if (existsSync(out)) throw Error('Use a fresh Central output directory');
if (!process.env.WEAPP_SIGNING_KEY) throw Error('WEAPP_SIGNING_KEY must contain the ASCII armored signing key; publication credentials are not written to source');
const temporary = mkdtempSync(join(tmpdir(), 'weapp-central-'));
try {
  const gpgHome = join(temporary, 'gpg'), bundle = join(temporary, 'bundle'), target = join(bundle, coordinate);
  mkdirSync(gpgHome, { mode: 0o700 }); mkdirSync(target, { recursive: true });
  const gpg = ['--homedir', gpgHome, '--batch', '--yes'];
  run('gpg', [...gpg, '--import'], root, { input: process.env.WEAPP_SIGNING_KEY });
  const passphrase = process.env.WEAPP_SIGNING_PASSWORD ?? '';
  for (const name of filesUnder(input).filter(file => /\.(aar|pom|jar|module)$/.test(file))) {
    const path = join(target, name); cpSync(join(input, name), path);
    // The passphrase travels over stdin, never in the process arguments.
    run('gpg', [...gpg, '--pinentry-mode', 'loopback', '--passphrase-fd', '0', '--armor', '--detach-sign', path], root, { input: passphrase + '\n' });
  }
  for (const file of filesUnder(target)) for (const algorithm of ['md5', 'sha1', 'sha256']) {
    writeFileSync(join(target, `${file}.${algorithm}`), createHash(algorithm).update(readFileSync(join(target, file))).digest('hex'));
  }
  mkdirSync(out, { recursive: true });
  const fileName = `weapp-container-android-${version}-central.zip`;
  run('python3', ['-c', 'import pathlib,sys,zipfile; r=pathlib.Path(sys.argv[1]); z=zipfile.ZipFile(sys.argv[2],"w",zipfile.ZIP_DEFLATED); [z.write(p,p.relative_to(r).as_posix()) for p in sorted(r.rglob("*")) if p.is_file()]; z.close()', bundle, join(out, fileName)], root);
  writeRelease(root, out, 'weapp-container/sdk-android', [assetEntry(out, fileName, { component: 'maven-central', coordinate: `io.github.weapp-container:weapp-container-android:${version}` })]);
  if (args.includes('--execute')) {
    const username = process.env.MAVEN_CENTRAL_USERNAME, password = process.env.MAVEN_CENTRAL_PASSWORD;
    if (!username || !password) throw Error('Maven Central Portal user-token credentials are required');
    const headers = { Authorization: `Bearer ${Buffer.from(`${username}:${password}`).toString('base64')}` };
    const body = new FormData(); body.set('bundle', new Blob([readFileSync(join(out, fileName))]), fileName);
    const response = await fetch(`https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=${encodeURIComponent(stem)}`, { method: 'POST', headers, body, signal: AbortSignal.timeout(120000) });
    if (!response.ok) throw Error(`Central upload failed (${response.status}); no release was declared successful`);
    const deploymentId = (await response.text()).trim();
    writeFileSync(join(out, 'deployment.json'), JSON.stringify({ deploymentId, state: 'UPLOADED', published: false }, null, 2) + '\n');
    await completeDeployment(deploymentId, headers, value => writeFileSync(join(out, 'deployment.json'), JSON.stringify(value, null, 2) + '\n'));
    console.log(`Central deployment ${deploymentId} published after server validation.`);
  }
} finally { rmSync(temporary, { recursive: true, force: true }); }
