#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2);
const option=(name,fallback)=>{const i=args.indexOf(name);return i<0?fallback:args[i+1];};
const serial=option('--serial'); if(!serial||serial.startsWith('--'))throw new Error('--serial must name an already-running target');
const artifacts=resolve(option('--artifacts',join(root,'artifacts/android')));
const output=resolve(option('--out',join(artifacts,'device'))); mkdirSync(output,{recursive:true});
const report=JSON.parse(readFileSync(join(artifacts,'validation.json'),'utf8'));
if(report.status!=='passed')throw new Error('Build validation must pass first');
const sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT||join(os.homedir(),'Library/Android/sdk');
const adb=process.env.ADB||join(sdk,'platform-tools/adb');
const commandLog=[];
function command(argv,optional=false) {
 const result=spawnSync(adb,['-s',serial,...argv],{encoding:'utf8',timeout:180000,maxBuffer:8*1024*1024});
 commandLog.push({argv,status:result.status,stdout:result.stdout,stderr:result.stderr});
 if(!optional&&(result.error||result.status!==0))throw new Error(`adb ${argv.join(' ')} failed: ${result.error?.message||result.stderr}`);
 return result.stdout||'';
}
const packages=['com.sonofmagic.weapp.container.sdkconsumer','com.sonofmagic.weapp.container.sdkconsumer.test'];
async function instrument(argv) {
 return await new Promise((resolveResult,reject)=>{
  const child=spawn(adb,['-s',serial,...argv],{stdio:['ignore','pipe','pipe']});
  let stdout='',stderr='',captured=false;
  const timeout=setTimeout(()=>{child.kill('SIGTERM');reject(new Error('Instrumentation timed out'));},180000);
  child.stdout.on('data',chunk=>{
   stdout+=chunk;
   if(!captured && stdout.includes('sdkStage=first-frame')) {
    captured=true;
    const screen=spawnSync(adb,['-s',serial,'exec-out','screencap','-p'],{timeout:10000,maxBuffer:32*1024*1024});
    if(screen.status===0)writeFileSync(join(output,'first-frame.png'),screen.stdout);
   }
  });
  child.stderr.on('data',chunk=>{stderr+=chunk;});
  child.on('error',error=>{clearTimeout(timeout);reject(error);});
  child.on('close',status=>{clearTimeout(timeout);commandLog.push({argv,status,stdout,stderr});status===0?resolveResult(stdout):reject(new Error(`Instrumentation exited ${status}: ${stderr}`));});
 });
}
const installed=[]; const cleanupErrors=[];
let result={schemaVersion:1,status:'failed',serial,checks:[],cleanupErrors};
try {
 if(command(['get-state']).trim()!=='device')throw new Error('Selected device is offline');
 for(const name of packages)if(command(['shell','pm','path',name],true).trim())throw new Error(`Refusing to overwrite existing package: ${name}`);
 const since=command(['shell','date "+%m-%d %H:%M:%S.000"']).trim();
 for(const [index,apk] of [report.releaseApk,report.testApk].entries()) { if(!existsSync(apk))throw new Error(`Missing APK ${apk}`); installed.push(packages[index]);command(['install','-t',apk]); }
 const text=await instrument(['shell','am','instrument','-w','-r',`${packages[1]}/com.sonofmagic.weapp.container.consumer.SdkInstrumentation`]);
 writeFileSync(join(output,'instrumentation.txt'),text);
 const logs=command(['logcat','-d','-T',since,'WeappContainer:V','skia:V','EGL_emulation:V','AndroidRuntime:E','*:S']);
 writeFileSync(join(output,'logcat.txt'),logs);
 const line=text.split(/\r?\n/).find(line=>line.startsWith('INSTRUMENTATION_RESULT: sdkReport='));
 if(!line)throw new Error('Instrumentation did not return an SDK receipt');
 const device=JSON.parse(line.slice('INSTRUMENTATION_RESULT: sdkReport='.length));
 result={...result,...device,serial,cleanupErrors,apkSha256:createHash('sha256').update(readFileSync(report.releaseApk)).digest('hex'),
   testApkSha256:createHash('sha256').update(readFileSync(report.testApk)).digest('hex'),
   observedRecoveryLogs:(logs.match(/Recreating invalid owned GLES context once/g)||[]).length,
   sourceValidation:join(artifacts,'validation.json'),targetKind:serial.startsWith('emulator-')?'emulator':'physical-device'};
 if(device.status!=='passed')throw new Error(device.error||'SDK instrumentation failed');
} catch(error) { result.status='failed';result.error=error.stack;process.exitCode=1; }
finally {
 for(const name of installed.reverse())try{command(['shell','am','force-stop',name]);command(['uninstall',name]);if(command(['shell','pm','path',name],true).trim())throw new Error('Package still installed');}catch(error){cleanupErrors.push({package:name,error:error.message});process.exitCode=1;}
 if(cleanupErrors.length)result.status='failed';
 writeFileSync(join(output,'commands.json'),JSON.stringify(commandLog,null,2)+'\n');
 writeFileSync(join(output,'result.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result,null,2));
}
