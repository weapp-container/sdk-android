#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const option = name => { const i = process.argv.indexOf(name); return i < 0 ? null : process.argv[i + 1]; };
if (!option('--kotlin-lib') || !option('--json-jar')) throw new Error('Use --kotlin-lib <compiler library directory> --json-jar <org.json jar>');
const libraries = resolve(option('--kotlin-lib')), json = resolve(option('--json-jar'));
const stdlib = readdirSync(libraries).find(name => /^kotlin-stdlib-[0-9].*\.jar$/.test(name));
if (!stdlib) throw new Error('Missing kotlin-stdlib compiler input');
const temporary = mkdtempSync(resolve(tmpdir(), 'weapp-identity-snapshot-'));
const separator = process.platform === 'win32' ? ';' : ':';
try {
  for (const [command, args] of [
    ['java', ['-cp', resolve(libraries, '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', [resolve(libraries, stdlib), json].join(separator), '-d', temporary,
      resolve(root, 'library/src/main/java/com/sonofmagic/weapp/container/sdk/HostIdentitySnapshot.kt'), resolve(root, 'test/HostIdentitySnapshotTests.kt')]],
    ['java', ['-cp', [temporary, resolve(libraries, stdlib), json].join(separator), 'com.sonofmagic.weapp.container.sdk.HostIdentitySnapshotTestsKt']],
  ]) {
    const result = spawnSync(command, args, { stdio: 'inherit' });
    if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message ?? result.status}`);
  }
} finally { rmSync(temporary, { recursive: true, force: true }); }
