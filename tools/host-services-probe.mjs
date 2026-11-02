#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';
import os from 'node:os';
import { makeHostServicesFixture } from '../test/host-services-fixture.mjs';
import { networkDependencies } from './network-dependencies.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..'),args=process.argv.slice(2);
const option=name=>{const i=args.indexOf(name);return i<0?undefined:args[i+1];};
const serial=option('--serial'),native=option('--native-dir'),out=option('--out');
if(!serial||!native||!out)throw Error('Use --serial <already-running emulator> --native-dir <development aarch64-linux-android slice> --out <new directory> [--accept-app-consent]');
if(existsSync(out))throw Error('Select a new output directory to preserve prior probe evidence');
mkdirSync(out,{recursive:true});
const sdk=process.env.ANDROID_HOME||join(os.homedir(),'Library/Android/sdk'),build=join(sdk,'build-tools/36.0.0'),android=join(sdk,'platforms/android-36/android.jar');
const adb=join(sdk,'platform-tools/adb'),pkg='com.weappcontainer.hostservicesprobe',commands=[],cleanupErrors=[],control=randomBytes(32).toString('base64url');
const redact=value=>typeof value==='string'?value.replaceAll(control,'[native-demo-control-redacted]'):value;
const execute=(program,argv,{optional=false,binary=false,timeout=180000}={})=>{
 const result=spawnSync(program,argv,{encoding:binary?undefined:'utf8',timeout,maxBuffer:64*1024*1024});
 commands.push({program,argv:argv.map(redact),status:result.status,...(!binary?{stdout:redact(result.stdout),stderr:redact(result.stderr)}:{})});
 if(!optional&&(result.error||result.status!==0))throw Error(`${program} failed (${result.status}): ${result.error?.message||result.stderr}`);
 return result.stdout;
};
const device=(argv,options)=>execute(adb,['-s',serial,...argv],options);
const hash=path=>createHash('sha256').update(readFileSync(path)).digest('hex');
const collect=(directory,suffix)=>readdirSync(directory,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?collect(join(directory,entry.name),suffix):entry.name.endsWith(suffix)?[join(directory,entry.name)]:[]);
const report={schemaVersion:1,status:'failed',scope:'Unpublished development native/wrapper/guest Android emulator probe; no physical-device, BLE peripheral, merchant, parity or release claim',serial,checks:[],cleanupErrors};
let installed=false,uiCreated=false,serverProcess,reversePorts=[],phoneTapped=false;
try {
 if(device(['get-state']).trim()!=='device'||device(['shell','getprop','ro.kernel.qemu']).trim()!=='1')throw Error('Select an already-running emulator');
 if(device(['shell','pm','path',pkg],{optional:true}).trim())throw Error(`Refusing to overwrite an existing ${pkg}`);
 const receipt=JSON.parse(readFileSync(join(native,'build.json'),'utf8'));
 if(receipt.status!=='built'||receipt.target!=='aarch64-linux-android'||receipt.profile!=='dev')throw Error('Supply an explicit successful arm64 development producer receipt');
 report.nativeProducer=receipt;
 for(const artifact of receipt.artifacts)if(artifact.file==='libweapp_container_sdk.so'&&hash(join(native,artifact.file))!==artifact.sha256)throw Error('Native runtime hash mismatch');
 const assets=join(out,'assets'),packRoot=makeHostServicesFixture(join(assets,'probe-pack'));
 const serverSource=resolve(option('--reference-root')??join(root,'../../tools/host-services-reference'),'scripts/demo-business-server.mjs');
 if(!existsSync(serverSource))throw Error('Use --reference-root <tools/host-services-reference> when running outside the monorepo');
 const serverCode=`import {createDemoBusinessServer} from ${JSON.stringify(pathToFileURL(serverSource).href)};import {makeNetworkFixtureServer} from ${JSON.stringify(pathToFileURL(join(root,'test/host-services-network-fixture.mjs')).href)};const {server}=createDemoBusinessServer({controlToken:process.env.HOST_DEMO_CONTROL_TOKEN,packRoot:process.env.HOST_DEMO_PACK_ROOT});const network=makeNetworkFixtureServer();await Promise.all([new Promise(done=>server.listen(0,'127.0.0.1',done)),new Promise(done=>network.listen(0,'127.0.0.1',done))]);console.log(JSON.stringify({port:server.address().port,networkPort:network.address().port}));process.once('SIGTERM',()=>{server.close();server.closeIdleConnections();network.close();network.closeAllConnections();});`;
 serverProcess=spawn(process.execPath,['--input-type=module','-e',serverCode],{env:{...process.env,HOST_DEMO_CONTROL_TOKEN:control,HOST_DEMO_PACK_ROOT:packRoot},stdio:['ignore','pipe','pipe']});
 let serverError='';serverProcess.stderr.on('data',data=>{serverError+=data;});
 const {port,networkPort}=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Demo server startup timed out')),10000);let text='';serverProcess.once('error',reject);serverProcess.once('exit',code=>reject(Error(`Demo server exited ${code}: ${serverError}`)));serverProcess.stdout.on('data',data=>{text+=data;if(text.includes('\n')){clearTimeout(timer);resolve(JSON.parse(text.split('\n')[0]));}});});
 for(const mapping of [port,networkPort]){if(device(['reverse','--list']).split('\n').some(line=>line.split(/\s+/).includes(`tcp:${mapping}`)))throw Error('Refusing to replace an existing adb reverse port');device(['reverse',`tcp:${mapping}`,`tcp:${mapping}`]);reversePorts.push(mapping);}
 report.referenceBackend={kind:'ephemeral-loopback-reference-only',packRoot,sourceSha256:hash(serverSource)};
 const classes=join(out,'classes');mkdirSync(classes);
 const dependencies=await networkDependencies(option('--dependency-cache')??join(out,'dependencies'));
 report.networkDependencies=dependencies.map(({path,...input})=>input);
 const gradle=join(os.homedir(),'.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/lib'),stdlib=join(gradle,'kotlin-stdlib-1.9.23.jar');
 const source=collect(join(root,'library/src/main/java'),'.kt').concat(join(root,'test/HostServicesProbeActivity.kt'),join(root,'examples/host-services/ReferenceHostServices.kt'));
 report.wrapperSources=source.map(path=>({path:path.slice(root.length+1),sha256:hash(path)}));
 report.fixtureSource={sha256:hash(join(root,'test/host-services-fixture.mjs'))};report.jniSource={sha256:hash(join(root,'library/src/main/cpp/weapp_jni.cpp'))};
 execute('java',['-cp',join(gradle,'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',[android,stdlib,...dependencies.map(input=>input.path)].join(':'),'-jvm-target','17','-d',classes,...source]);
 execute('jar',['cf',join(out,'probe.jar'),'-C',classes,'.']);
 const dex=join(out,'dex');mkdirSync(dex);execute(join(build,'d8'),['--min-api','26','--lib',android,'--output',dex,join(out,'probe.jar'),stdlib,...dependencies.map(input=>input.path)]);
 const ndk=process.env.ANDROID_NDK_HOME||join(sdk,'ndk/27.1.12297006'),clang=join(ndk,'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++');
 const libs=join(out,'lib/arm64-v8a');mkdirSync(libs,{recursive:true});
 execute(clang,['-shared','-fPIC','-std=c++17','-Wl,-z,max-page-size=16384','-Wl,-soname,libweapp_container_android.so','-I',native,join(root,'library/src/main/cpp/weapp_jni.cpp'),'-L',native,'-lweapp_container_sdk','-o',join(libs,'libweapp_container_android.so')]);
 for(const name of ['libweapp_container_sdk.so','libc++_shared.so'])writeFileSync(join(libs,name),readFileSync(join(native,name)));
 const manifest=join(out,'AndroidManifest.xml');writeFileSync(manifest,`<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${pkg}"><uses-permission android:name="android.permission.CAMERA"/><uses-permission android:name="android.permission.READ_CONTACTS"/><uses-permission android:name="android.permission.WRITE_CONTACTS"/><uses-permission android:name="android.permission.READ_CALENDAR"/><uses-permission android:name="android.permission.WRITE_CALENDAR"/><uses-permission android:name="android.permission.USE_FINGERPRINT"/><uses-permission android:name="android.permission.RECORD_AUDIO"/><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><uses-permission android:name="android.permission.VIBRATE"/><uses-feature android:name="android.hardware.camera" android:required="false"/><application android:label="Host Services Development Probe" android:debuggable="true" android:usesCleartextTraffic="true" android:extractNativeLibs="true"><activity android:name="com.sonofmagic.weapp.container.sdk.HostServicesProbeActivity" android:exported="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>`);
 const unsigned=join(out,'unsigned.apk');execute(join(build,'aapt2'),['link','-I',android,'--manifest',manifest,'--min-sdk-version','26','--target-sdk-version','36','--version-code','1','-A',assets,'-o',unsigned]);
 for(const file of collect(dex,'.dex'))execute('zip',['-j',unsigned,file]);
 // zip's cwd is explicit because only task-owned native files belong in the APK.
 const zip=spawnSync('zip',['-r',unsigned,'lib'],{cwd:out,encoding:'utf8'});if(zip.status!==0)throw Error(zip.stderr);
 const aligned=join(out,'aligned.apk');execute(join(build,'zipalign'),['-P','16','-f','4',unsigned,aligned]);
 const key=join(out,'probe.jks');execute('keytool',['-genkeypair','-keystore',key,'-alias','probe','-storepass','android','-keypass','android','-dname','CN=Development Probe','-keyalg','RSA','-validity','2']);
 const apk=join(out,'probe.apk');execute(join(build,'apksigner'),['sign','--ks',key,'--ks-key-alias','probe','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned]);
 report.apk={sha256:hash(apk)};
 device(['install','-g','-t',apk]);installed=true;
 device(['shell','am','start','-W','-n',`${pkg}/com.sonofmagic.weapp.container.sdk.HostServicesProbeActivity`,'--es','demoOrigin',`http://127.0.0.1:${port}`,'--es','networkOrigin',`http://127.0.0.1:${networkPort}`,'--es','demoControl',control]);
 const deadline=Date.now()+130000,ui='/sdcard/weapp-host-services-probe-ui.xml';
 while(Date.now()<deadline){
  const value=device(['shell','run-as',pkg,'cat','files/host-services-probe.json'],{optional:true,timeout:10000});
  if(value.trim().startsWith('{')){report.probe=JSON.parse(value);report.status=report.probe.status;break;}
  if(args.includes('--accept-app-consent')){
   const dumped=device(['shell','uiautomator','dump',ui],{optional:true,timeout:10000});if(dumped.includes('dumped to'))uiCreated=true;
   const xml=device(['shell','cat',ui],{optional:true,timeout:10000});
   const consent=['小程序权限申请','授权昵称和头像','授权手机号'].some(title=>xml.includes(title)),payment=xml.includes('本地 reference 支付');
   const contact=xml.includes('添加联系人'),calendar=xml.includes('添加日历：');
   if(consent||payment||contact||calendar){
    const label=payment?'确认测试订单':contact?'确认添加':calendar?(xml.match(/text="(weapp-native-probe-[^"]+)"/)?.[1]??''):'允许';
    const button=xml.match(new RegExp(`<node[^>]*text="${label}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
    if(button)device(['shell','input','tap',String((Number(button[1])+Number(button[3]))>>1),String((Number(button[2])+Number(button[4]))>>1)]);
   }else if(!phoneTapped){
    const pending=device(['shell','run-as',pkg,'cat','files/phone-tap.json'],{optional:true,timeout:10000});
    if(pending.trim().startsWith('{')){const target=JSON.parse(pending);device(['shell','input','tap',String(target.x),String(target.y)]);phoneTapped=true;report.realPhoneButtonTap=target;}
   }
  }
  await new Promise(done=>setTimeout(done,500));
 }
 const image=device(['exec-out','screencap','-p'],{binary:true});writeFileSync(join(out,'screen.png'),image);
 const frame=device(['exec-out','run-as',pkg,'cat','files/host-services-frame.png'],{binary:true,optional:true});if(frame?.length)writeFileSync(join(out,'host-services-frame.png'),frame);
 const pid=device(['shell','pidof',pkg],{optional:true}).trim();
 if(pid)writeFileSync(join(out,'logcat.txt'),device(['logcat','-d',`--pid=${pid}`],{optional:true}));
 if(!report.probe)throw Error('Probe did not produce a terminal report');
}catch(error){report.error=error.stack;}
finally{
 if(installed){try{device(['uninstall',pkg]);if(device(['shell','pm','path',pkg],{optional:true}).trim())throw Error('Probe package still installed');}catch(error){cleanupErrors.push(error.message);}}
 if(uiCreated)try{device(['shell','rm','-f','/sdcard/weapp-host-services-probe-ui.xml']);}catch(error){cleanupErrors.push(error.message);}
 for(const mapping of reversePorts)try{device(['reverse','--remove',`tcp:${mapping}`]);if(device(['reverse','--list']).split('\n').some(line=>line.split(/\s+/).includes(`tcp:${mapping}`)))throw Error('Task adb reverse remained');}catch(error){cleanupErrors.push(error.message);}
 if(serverProcess){serverProcess.kill('SIGTERM');await new Promise(done=>{if(serverProcess.exitCode!==null||serverProcess.signalCode!==null)return done();const timer=setTimeout(()=>{cleanupErrors.push('Demo server did not close within 5 seconds');done();},5000);serverProcess.once('exit',()=>{clearTimeout(timer);done();});});}
 if(cleanupErrors.length)report.status='failed';
 writeFileSync(join(out,'commands.json'),JSON.stringify(commands,null,2));writeFileSync(join(out,'report.json'),JSON.stringify(report,null,2));
}
console.log(JSON.stringify({status:report.status,report:join(out,'report.json'),checks:report.probe?.checks,cleanupErrors},null,2));
if(report.status!=='passed')process.exitCode=1;
