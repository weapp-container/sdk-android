#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const option = name => { const index = process.argv.indexOf(name); return index < 0 ? null : process.argv[index + 1]; };
if (!option('--kotlin-lib') || !option('--json-jar')) throw new Error('Use --kotlin-lib <compiler library directory> --json-jar <org.json jar>');
const libraries = resolve(option('--kotlin-lib'));
const json = resolve(option('--json-jar'));
const android = resolve(option('--android-jar') || resolve(process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || resolve(homedir(), 'Library/Android/sdk'), 'platforms/android-36/android.jar'));
if (!existsSync(android)) throw new Error('Supply the locked Android 36 android.jar with --android-jar');
const stdlib = readdirSync(libraries).find(name => /^kotlin-stdlib-[0-9].*\.jar$/.test(name));
if (!stdlib) throw new Error('Missing kotlin-stdlib compiler input');
const temporary = mkdtempSync(resolve(tmpdir(), 'weapp-host-backend-error-'));
const separator = process.platform === 'win32' ? ';' : ':';
try {
  const source = resolve(root, 'library/src/main/java/com/sonofmagic/weapp/container/sdk/HostBackendError.kt');
  const models = resolve(root, 'library/src/main/java/com/sonofmagic/weapp/container/sdk/Models.kt');
  const test = resolve(root, 'test/HostBackendErrorTests.kt');
  const classpath = [resolve(libraries, stdlib), json, android].join(separator);
  const compile = spawnSync('java', ['-cp', resolve(libraries, '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', temporary, source, models, test], { stdio: 'inherit' });
  if (compile.error || compile.status !== 0) throw new Error(`Kotlin compiler failed: ${compile.error?.message ?? compile.status}`);
  const run = spawnSync('java', ['-cp', [temporary, classpath].join(separator), 'com.sonofmagic.weapp.container.sdk.HostBackendErrorTestsKt'], { stdio: 'inherit' });
  if (run.error || run.status !== 0) throw new Error(`Backend error test failed: ${run.error?.message ?? run.status}`);
} finally { rmSync(temporary, { recursive: true, force: true }); }
