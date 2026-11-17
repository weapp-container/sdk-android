#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const index=process.argv.indexOf('--kotlin-lib');
if(index<0||!process.argv[index+1])throw new Error('Use --kotlin-lib <Kotlin compiler library directory>');
const libraries=resolve(process.argv[index+1]);
const stdlib=readdirSync(libraries).find(name=>/^kotlin-stdlib-[0-9].*\.jar$/.test(name));
if(!stdlib)throw new Error('Missing kotlin-stdlib compiler input');
const temporary=mkdtempSync(resolve(tmpdir(),'weapp-account-cleanup-'));
try {
  for(const [command,args] of [
    ['java',['-cp',resolve(libraries,'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',resolve(libraries,stdlib),'-d',temporary,
      resolve(root,'library/src/main/java/com/sonofmagic/weapp/container/sdk/AccountCleanupQueue.kt'),resolve(root,'test/AccountCleanupQueueTest.kt')]],
    ['java',['-cp',[temporary,resolve(libraries,stdlib)].join(process.platform==='win32'?';':':'),'com.sonofmagic.weapp.container.sdk.AccountCleanupQueueTestKt']],
  ]) {
    const result=spawnSync(command,args,{stdio:'inherit'});
    if(result.error||result.status!==0)throw new Error(`${command} failed: ${result.error?.message??result.status}`);
  }
} finally {rmSync(temporary,{recursive:true,force:true});}
