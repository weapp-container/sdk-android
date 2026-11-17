#!/usr/bin/env node
import {spawnSync} from 'node:child_process';
import {existsSync,mkdirSync,readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import os from 'node:os';
import {copyPack,readNative,readNotices,requireNativeRoot} from './native-input.mjs';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2);
const option=(key,fallback)=>{const at=args.indexOf(key);return at<0?fallback:args[at+1];};
const serial=option('--serial');if(!serial||serial.startsWith('--'))throw new Error('--serial must select an existing device');
const artifacts=resolve(option('--artifacts',join(root,'artifacts/android')));
const out=resolve(option('--out',join(artifacts,'gles-recovery')));mkdirSync(out,{recursive:true});
const sdk=process.env.ANDROID_HOME||join(os.homedir(),'Library/Android/sdk');
const adb=process.env.ADB||join(sdk,'platform-tools/adb');
const commands=[];const cleanupErrors=[];let installed=false;
function run(command,argv,options={}){
 const result=spawnSync(command,argv,{encoding:'utf8',timeout:300000,maxBuffer:32*1024*1024,...options});
 commands.push({command,argv,status:result.status,stdout:result.stdout,stderr:result.stderr});
 if(result.error||result.status!==0)throw new Error(`${command} failed: ${result.error?.message||result.stderr||result.stdout}`);
 return result.stdout;
}
const device=(argv)=>run(adb,['-s',serial,...argv]);
const packageName='com.sonofmagic.weapp.container.sdk.test';
let result={schemaVersion:1,status:'failed',serial,cleanupErrors,targetKind:serial.startsWith('emulator-')?'emulator':'physical-device'};
try {
 if(!args.includes('--skip-build')){
  const dist=join(os.homedir(),'.gradle/wrapper/dists/gradle-8.9-bin');
  const gradle=process.env.GRADLE||readdirSync(dist).map(name=>join(dist,name,'gradle-8.9/bin/gradle')).find(existsSync);
  const native=requireNativeRoot(args);const licenses=resolve(option('--licenses-root',join(native,'..','licenses')));
  for(const target of ['aarch64-linux-android','x86_64-linux-android']){readNative(native,target,['libweapp_container_sdk.so','libc++_shared.so']);readNotices(native,licenses,target);}
  copyPack(option('--pack'),join(root,'library/build/generated/recoveryAssets/portable-pack'));
  run(gradle,['--no-daemon','--console=plain',...(args.includes('--offline')?['--offline']:[]),':library:assembleDebugAndroidTest',`-PweappSdkNativeDir=${native}`,`-PweappSdkLicensesDir=${licenses}`],{cwd:root,env:{...process.env,ANDROID_HOME:sdk}});
 }
 const apk=join(root,'library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk');
 const hash=createHash('sha256').update(readFileSync(apk)).digest('hex');
 const commit=run('git',['rev-parse','HEAD'],{cwd:root}).trim();
 const dirty=Boolean(run('git',['status','--porcelain'],{cwd:root}).trim());
 if(device(['get-state']).trim()!=='device')throw new Error('Selected device is offline');
 const existing=spawnSync(adb,['-s',serial,'shell','pm','path',packageName],{encoding:'utf8'});
 if(existing.stdout.trim())throw new Error(`Refusing to overwrite existing package: ${packageName}`);
 const since=device(['shell','date "+%m-%d %H:%M:%S.000"']).trim();
 installed=true;device(['install','-t',apk]);
 const output=device(['shell','am','instrument','-w','-r',`${packageName}/com.sonofmagic.weapp.container.sdk.GlesRecoveryInstrumentation`]);
 writeFileSync(join(out,'instrumentation.txt'),output);
 const logs=device(['logcat','-d','-T',since,'WeappContainer:V','skia:V','EGL_emulation:V','AndroidRuntime:E','*:S']);
 writeFileSync(join(out,'logcat.txt'),logs);
 const line=output.split(/\r?\n/).find(line=>line.startsWith('INSTRUMENTATION_RESULT: sdkReport='));
 if(!line)throw new Error('Missing recovery instrumentation receipt');
 result={...result,...JSON.parse(line.slice('INSTRUMENTATION_RESULT: sdkReport='.length)),cleanupErrors,
  sourceCommit:commit,sourceDirty:dirty,apk,apkSha256:hash,
  observedRecoveryLogs:(logs.match(/Recreating invalid owned GLES context once/g)||[]).length};
 if(result.status!=='passed')throw new Error(result.error||'Recovery test failed');
} catch(error){result.status='failed';result.error=error.stack;process.exitCode=1;}
finally{
 if(installed)try{device(['shell','am','force-stop',packageName]);device(['uninstall',packageName]);const state=spawnSync(adb,['-s',serial,'shell','pm','path',packageName],{encoding:'utf8'});if(state.stdout.trim())throw new Error('Package remains installed');}catch(error){cleanupErrors.push({package:packageName,error:error.message});process.exitCode=1;}
 if(cleanupErrors.length)result.status='failed';
 writeFileSync(join(out,'commands.json'),JSON.stringify(commands,null,2)+'\n');
 writeFileSync(join(out,'result.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result,null,2));
}
