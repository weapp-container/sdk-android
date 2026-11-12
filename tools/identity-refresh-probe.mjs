#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeIdentityRefreshPack, createIdentityRefreshServer } from '../test/identity-refresh-fixture.mjs';
import { networkDependencies } from './network-dependencies.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = process.argv.slice(2);
const option = name => { const index = args.indexOf(name); return index < 0 ? undefined : args[index + 1]; };
const serial = option('--serial'), native = option('--native-dir'), output = option('--out');
if (!serial || !native || !output) throw Error('Use --serial <existing emulator> --native-dir <explicit arm64 native slice> --out <new directory> [--reference-root <reference service source>]');
if (existsSync(output)) throw Error('Use a new output directory; prior evidence is immutable');
mkdirSync(output, { recursive: true });
const out = resolve(output), sdk = process.env.ANDROID_HOME || join(homedir(), 'Library/Android/sdk');
const adb = join(sdk, 'platform-tools/adb'), build = join(sdk, 'build-tools/36.0.0'), android = join(sdk, 'platforms/android-36/android.jar');
const packageName = 'com.weappcontainer.identityrefreshprobe', controlToken = randomBytes(32).toString('base64url');
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const redact = value => typeof value === 'string' ? value.replaceAll(controlToken, '[native-control-redacted]') : value;
const collect = directory => readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? collect(join(directory, entry.name)) : entry.name.endsWith('.kt') ? [join(directory, entry.name)] : []);
const commands = [], sources = new Map(), resources = [], cleanupErrors = [];
const report = { schemaVersion: 1, status: 'failed', classification: 'finite-current-source-wrapper-with-explicit-historical-native-runtime', serial, commands, resources, cleanupErrors, limitations: ['Not a newly installed SDK archive, current clean native producer, release acceptance, production identity or full compatibility claim.', 'The pack is authored source rather than real compiler output; no compiler-delivery acceptance is claimed.'] };
const run = (program, argv, { optional = false, cwd, timeout = 180000 } = {}) => {
  const result = spawnSync(program, argv, { cwd, encoding: 'utf8', timeout, maxBuffer: 64 * 1024 * 1024 });
  commands.push({ program, argv: argv.map(redact), status: result.status, stdout: redact(result.stdout), stderr: redact(result.stderr) });
  if (!optional && (result.error || result.status !== 0)) throw Error(`${program} failed: ${result.error?.message || result.stderr}`);
  return result.stdout ?? '';
};
const device = (argv, options) => run(adb, ['-s', serial, ...argv], options);
const source = path => { const digest = hash(path); sources.set(path, digest); return digest; };
const own = (kind, id) => { const resource = { kind, id, ownership: 'created', terminal: 'open' }; resources.push(resource); return resource; };
let fixture, fixtureResource, packageResource, reverseResource, installed = false, port;
try {
  if (device(['get-state']).trim() !== 'device' || device(['shell', 'getprop', 'ro.kernel.qemu']).trim() !== '1') throw Error('Select an already-running emulator');
  if (device(['shell', 'pm', 'path', packageName], { optional: true }).trim()) throw Error('Refusing to overwrite an existing probe package');
  report.borrowedDevice = { serial, created: false, cleanup: 'retained without changing global device settings' };
  report.environment = { node: process.versions.node, androidRelease: device(['shell', 'getprop', 'ro.build.version.release']).trim(), androidApi: device(['shell', 'getprop', 'ro.build.version.sdk']).trim(), fingerprint: device(['shell', 'getprop', 'ro.build.fingerprint']).trim(), abi: device(['shell', 'getprop', 'ro.product.cpu.abi']).trim() };
  const nativeRoot = resolve(native), receipt = JSON.parse(readFileSync(join(nativeRoot, 'build.json')));
  if (receipt.status !== 'built' || receipt.target !== 'aarch64-linux-android' || receipt.sdkVersion !== '0.1.0-preview.7.0') throw Error('An explicit matching successful arm64 native producer receipt is required');
  for (const name of ['libweapp_container_sdk.so', 'libc++_shared.so', 'weapp_container_sdk.h']) {
    const artifact = receipt.artifacts.find(artifact => artifact.file === name);
    if (!artifact || hash(join(nativeRoot, name)) !== artifact.sha256) throw Error(`Native producer input mismatch: ${name}`);
  }
  report.native = { directory: nativeRoot, producer: receipt, receiptSha256: hash(join(nativeRoot, 'build.json')) };
  const packRoot = makeIdentityRefreshPack(join(out, 'assets/identity-pack'));
  const referenceRoot = resolve(option('--reference-root') ?? join(root, '../../tools/host-services-reference'));
  report.reference = { directory: referenceRoot, sourceFiles: ['service', 'server', 'crypto', 'errors', 'providers'].map(name => { const path = join(referenceRoot, `src/${name}.mjs`); return { path: `src/${name}.mjs`, sha256: source(path) }; }) };
  fixture = await createIdentityRefreshServer({ referenceRoot, controlToken, packRoot });
  await new Promise((done, reject) => { fixture.server.once('error', reject); fixture.server.listen(0, '127.0.0.1', done); });
  port = fixture.server.address().port; fixtureResource = own('reference-http-sqlite-server', `http://127.0.0.1:${port}`);
  if (device(['reverse', '--list']).split('\n').some(line => line.split(/\s+/).includes(`tcp:${port}`))) throw Error('Refusing to replace an existing reverse mapping');
  device(['reverse', `tcp:${port}`, `tcp:${port}`]); reverseResource = own('adb-reverse', `tcp:${port}`);
  const classes = join(out, 'classes'); mkdirSync(classes);
  const dependencies = await networkDependencies(option('--dependency-cache') ?? join(out, 'dependencies'));
  report.compilerDependencies = dependencies.map(({ path, ...input }) => input);
  const kotlin = join(homedir(), '.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/lib'), stdlib = join(kotlin, 'kotlin-stdlib-1.9.23.jar');
  const kotlinSources = [...collect(join(root, 'library/src/main/java')), join(root, 'test/HostIdentityRefreshProbeActivity.kt')];
  for (const path of [...kotlinSources, fileURLToPath(import.meta.url), join(root, 'test/identity-refresh-fixture.mjs'), join(root, 'tools/network-dependencies.mjs'), join(root, 'library/src/main/cpp/weapp_jni.cpp')]) source(path);
  report.wrapper = { sourceCommit: run('git', ['rev-parse', 'HEAD'], { cwd: root }).trim(), workingTreeDirty: run('git', ['status', '--porcelain'], { cwd: root }).trim() !== '', historicalOriginSha256: hash(join(root, 'ORIGIN.json')), sourceFiles: kotlinSources.map(path => ({ path: path.slice(root.length + 1), sha256: hash(path) })) };
  run('java', ['-cp', join(kotlin, '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', [android, stdlib, ...dependencies.map(input => input.path)].join(':'), '-jvm-target', '17', '-d', classes, ...kotlinSources]);
  run('jar', ['cf', join(out, 'probe.jar'), '-C', classes, '.']);
  const dex = join(out, 'dex'); mkdirSync(dex);
  run(join(build, 'd8'), ['--min-api', '26', '--lib', android, '--output', dex, join(out, 'probe.jar'), stdlib, ...dependencies.map(input => input.path)]);
  const ndk = process.env.ANDROID_NDK_HOME || join(sdk, 'ndk/27.1.12297006');
  const clang = join(ndk, 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++'), libs = join(out, 'lib/arm64-v8a'); mkdirSync(libs, { recursive: true });
  run(clang, ['-shared', '-fPIC', '-std=c++17', '-Wl,-z,max-page-size=16384', '-Wl,-soname,libweapp_container_android.so', '-I', nativeRoot, join(root, 'library/src/main/cpp/weapp_jni.cpp'), '-L', nativeRoot, '-lweapp_container_sdk', '-o', join(libs, 'libweapp_container_android.so')]);
  for (const name of ['libweapp_container_sdk.so', 'libc++_shared.so']) writeFileSync(join(libs, name), readFileSync(join(nativeRoot, name)));
  const manifest = join(out, 'AndroidManifest.xml'); writeFileSync(manifest, `<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${packageName}"><uses-permission android:name="android.permission.INTERNET"/><application android:label="Identity Refresh Regression" android:debuggable="true" android:usesCleartextTraffic="true" android:extractNativeLibs="true"><activity android:name="com.sonofmagic.weapp.container.sdk.HostIdentityRefreshProbeActivity" android:exported="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"/></application></manifest>`);
  const unsigned = join(out, 'unsigned.apk'); run(join(build, 'aapt2'), ['link', '-I', android, '--manifest', manifest, '--min-sdk-version', '26', '--target-sdk-version', '36', '--version-code', '1', '-A', join(out, 'assets'), '-o', unsigned]);
  for (const name of readdirSync(dex).filter(name => name.endsWith('.dex'))) run('zip', ['-j', unsigned, join(dex, name)]);
  run('zip', ['-r', unsigned, 'lib'], { cwd: out });
  const aligned = join(out, 'aligned.apk'); run(join(build, 'zipalign'), ['-P', '16', '-f', '4', unsigned, aligned]);
  const key = join(out, 'probe.jks'); run('keytool', ['-genkeypair', '-keystore', key, '-alias', 'probe', '-storepass', 'android', '-keypass', 'android', '-dname', 'CN=Finite Identity Probe', '-keyalg', 'RSA', '-validity', '2']);
  const apk = join(out, 'probe.apk'); run(join(build, 'apksigner'), ['sign', '--ks', key, '--ks-key-alias', 'probe', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, aligned]);
  report.apk = { sha256: hash(apk), size: readFileSync(apk).length };
  device(['install', '-t', apk]); installed = true; packageResource = own('android-probe-package', packageName);
  device(['shell', 'am', 'start', '-W', '-n', `${packageName}/com.sonofmagic.weapp.container.sdk.HostIdentityRefreshProbeActivity`, '--es', 'origin', `http://127.0.0.1:${port}`, '--es', 'control', controlToken]);
  const deadline = Date.now() + 100000;
  while (Date.now() < deadline) {
    const value = device(['shell', 'run-as', packageName, 'cat', 'files/identity-refresh-report.json'], { optional: true, timeout: 5000 });
    if (value.trim().startsWith('{')) { report.probe = JSON.parse(value); break; }
    await new Promise(done => setTimeout(done, 200));
  }
  const pid = device(['shell', 'pidof', packageName], { optional: true }).trim();
  if (pid) writeFileSync(join(out, 'logcat.txt'), device(['logcat', '-d', `--pid=${pid}`], { optional: true }));
  if (!report.probe || report.probe.status !== 'passed') throw Error(`Native identity probe failed: ${JSON.stringify(report.probe ?? null)}`);
  report.backend = fixture.observations();
  if (report.backend.checkFailures !== 4 || report.backend.disconnectedSnapshots !== 1 || report.backend.heldSnapshots !== 1 || report.backend.releasedSnapshots !== 1 || report.backend.held) throw Error('Actual HTTP fault/snapshot counts differ from the finite test');
  report.status = 'passed';
} catch (error) { report.error = String(error.stack ?? error); }
finally {
  if (installed) try { device(['uninstall', packageName]); if (device(['shell', 'pm', 'path', packageName], { optional: true }).trim()) throw Error('Probe package remains installed'); packageResource.terminal = 'closed'; } catch (error) { cleanupErrors.push(String(error)); packageResource.terminal = 'failed'; }
  if (reverseResource) try { device(['reverse', '--remove', `tcp:${port}`]); if (device(['reverse', '--list']).split('\n').some(line => line.split(/\s+/).includes(`tcp:${port}`))) throw Error('Probe reverse mapping remains'); reverseResource.terminal = 'closed'; } catch (error) { cleanupErrors.push(String(error)); reverseResource.terminal = 'failed'; }
  if (fixture) try { await fixture.close(); report.backendAfterClose = fixture.observations(); if (report.backendAfterClose.activeRequests || report.backendAfterClose.held || fixture.server.listening) throw Error('Reference fixture did not drain'); if (fixtureResource) fixtureResource.terminal = 'closed'; } catch (error) { cleanupErrors.push(String(error)); if (fixtureResource) fixtureResource.terminal = 'failed'; }
  const changed = [...sources].filter(([path, digest]) => !existsSync(path) || hash(path) !== digest).map(([path]) => path);
  report.executedSourceInputs = [...sources].map(([path, sha256]) => ({ path, sha256 }));
  report.sourceStability = { status: changed.length ? 'failed' : 'passed', count: sources.size, changed };
  if (cleanupErrors.length || changed.length) report.status = 'failed';
  report.finishedAt = new Date().toISOString(); writeFileSync(join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n');
}
console.log(JSON.stringify({ status: report.status, report: join(out, 'report.json'), cleanupErrors }));
if (report.status !== 'passed') process.exitCode = 1;
