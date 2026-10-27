#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, cpSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { copyPack, readNative, readNotices, requireNativeRoot, sourceProvenance, nativeIdentity, SDK_VERSION } from './native-input.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || join(os.homedir(), 'Library/Android/sdk');
const buildEnv = { ...process.env, ANDROID_HOME: sdk };
function option(name, fallback) { const i=args.indexOf(name); return i < 0 ? fallback : args[i+1]; }
const native = requireNativeRoot(args);
const licensesRoot = resolve(option('--licenses-root', join(native, '..', 'licenses')));
if (!option('--pack')) throw new Error('--pack is required for the independent consumer');
const out = resolve(option('--out', join(root, 'artifacts/android')));
const maven = join(out, 'maven'); const consumer = join(out, 'consumer');
mkdirSync(out, { recursive:true });
function run(command, argv, cwd=root) {
  console.log(`Running ${command} ${argv.join(' ')}`);
  const result=spawnSync(command, argv, {cwd, stdio:'inherit', env:buildEnv});
  if(result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message || result.status}`);
}
function findGradle() {
  if(process.env.GRADLE) return process.env.GRADLE;
  const dist=join(os.homedir(), '.gradle/wrapper/dists/gradle-8.9-bin');
  if(existsSync(dist)) for(const entry of readdirSync(dist)) { const bin=join(dist,entry,'gradle-8.9/bin/gradle'); if(existsSync(bin)) return bin; }
  return 'gradle';
}
const gradle=findGradle();
const common=['--no-daemon','--console=plain']; if(args.includes('--offline')) common.push('--offline');
const receiptPath=join(out,'validation.json');
const receipt={schemaVersion:1,sdkVersion:SDK_VERSION,status:'building',...sourceProvenance(root),startedAt:new Date().toISOString(),nativeRoot:native,licensesRoot,native:[],deviceExecution:'not-run'};
const save=()=>{
  writeFileSync(receiptPath,JSON.stringify(receipt,null,2)+'\n');
  // Keep command paths and local build directories in external evidence only.
  // The Maven installation has a separate portable, source-bound receipt.
  const publicError = receipt.error && [root,native,licensesRoot,out,sdk,gradle]
    .sort((a,b)=>b.length-a.length).reduce((text,path)=>text.replaceAll(path,'<local-build-directory>'),receipt.error);
  const distribution = Object.fromEntries([
    'schemaVersion','sdkVersion','status','sourceCommit','sourceDirty','sourceKind',
    'originSourceCommit','originManifestSha256','startedAt','finishedAt','native',
    'mavenArtifacts','binaries','checks','deviceExecution','fixtureIntegrity',
  ].filter(key=>receipt[key]!==undefined).map(key=>[key,receipt[key]]));
  distribution.artifactRoot = 'maven';
  if(publicError)distribution.error=publicError;
  writeFileSync(join(out,'distribution.json'),JSON.stringify(distribution,null,2)+'\n');
};
save();
try {
for (const target of ['aarch64-linux-android', 'x86_64-linux-android']) {
  const input = readNative(native, target, ['libweapp_container_sdk.so', 'libc++_shared.so']);
  receipt.native.push(nativeIdentity(input.receipt));
  readNotices(native, licensesRoot, target);
}
run(gradle,[...common,':library:assembleRelease',':library:publishReleasePublicationToLocalSdkRepository',`-PweappSdkNativeDir=${native}`,`-PweappSdkMavenDir=${maven}`,`-PweappSdkLicensesDir=${licensesRoot}`],root);
// Stage an independent source consumer: no included project, Cargo, JNI, or source-repository path.
if(existsSync(consumer)) rmSync(consumer,{recursive:true,force:true});
cpSync(join(root,'consumer'),consumer,{recursive:true,filter:p=>!['build','.gradle','.cxx'].includes(p.split('/').at(-1))});
const fixture=join(consumer,'src/main/assets/portable-pack');
const fixtureIntegrity = copyPack(option('--pack'), fixture);
run(gradle,[...common,'assembleRelease','assembleReleaseAndroidTest',`-PweappSdkMavenDir=${maven}`],consumer);
const aar=join(root,'library/build/outputs/aar/library-release.aar');
const unpacked=join(out,'aar-check'); rmSync(unpacked,{recursive:true,force:true}); mkdirSync(unpacked,{recursive:true});
run('python3',['-c','import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])',aar,unpacked]);
const ndk = process.env.ANDROID_NDK_HOME || process.env.ANDROID_NDK_ROOT || join(sdk, 'ndk/27.1.12297006');
const llvmRoot=join(ndk,'toolchains/llvm/prebuilt');
const llvmHost=readdirSync(llvmRoot).find(name=>existsSync(join(llvmRoot,name,'bin/llvm-readelf')));
const readelf=join(llvmRoot,llvmHost,'bin/llvm-readelf');
const binaries=[];
for(const [abi,machine] of [['arm64-v8a',183],['x86_64',62]]) for(const lib of ['libweapp_container_sdk.so','libweapp_container_android.so','libc++_shared.so']) {
  const path=join(unpacked,'jni',abi,lib); const data=readFileSync(path);
  if(data.subarray(0,4).toString('hex')!=='7f454c46'||data[4]!==2||data[5]!==1||data.readUInt16LE(18)!==machine) throw new Error(`Wrong ELF architecture: ${path}`);
  const phoff=Number(data.readBigUInt64LE(32)); const size=data.readUInt16LE(54); const count=data.readUInt16LE(56); const loads=[];
  for(let i=0;i<count;i++){const at=phoff+i*size;if(data.readUInt32LE(at)===1){const alignment=Number(data.readBigUInt64LE(at+48));const offset=Number(data.readBigUInt64LE(at+8));const vaddr=Number(data.readBigUInt64LE(at+16));if(alignment<16384||offset%16384!==vaddr%16384)throw new Error(`ELF is not 16 KB compatible: ${path}`);loads.push(alignment);}}
  if(!loads.length)throw new Error(`No ELF load segments: ${path}`);
  const dynamic=spawnSync(readelf,['-d',path],{encoding:'utf8'});if(dynamic.status!==0)throw new Error(dynamic.stderr);
  const needed=[...dynamic.stdout.matchAll(/\(NEEDED\).*?\[(.*?)\]/g)].map(match=>match[1]);
  const soname=dynamic.stdout.match(/\(SONAME\).*?\[(.*?)\]/)?.[1];
  if(soname!==lib||needed.some(name=>name.includes('/')||name.includes('\\')))throw new Error(`Nonportable ELF dynamic names: ${path}`);
  binaries.push({abi,library:lib,machine,loadAlignments:loads,soname,needed,sha256:createHash('sha256').update(data).digest('hex')});
}
const releaseDir=join(consumer,'build/outputs/apk/release'); const testDir=join(consumer,'build/outputs/apk/androidTest/release');
const apkIn=directory=>{const files=readdirSync(directory).filter(name=>name.endsWith('.apk'));if(files.length!==1)throw new Error(`Expected one APK in ${directory}`);return join(directory,files[0]);};
run(join(sdk,'build-tools/36.0.0/zipalign'),['-c','-P','16','4',apkIn(releaseDir)]);
function artifactFiles(directory, prefix='') {
  return readdirSync(directory,{withFileTypes:true}).flatMap(entry => {
    const relative=prefix+entry.name;
    return entry.isDirectory() ? artifactFiles(join(directory,entry.name),relative+'/') : [{file:relative,sha256:createHash('sha256').update(readFileSync(join(directory,entry.name))).digest('hex')}];
  });
}
const mavenArtifacts=artifactFiles(maven);
Object.assign(receipt,{status:'passed',fixtureIntegrity,mavenArtifacts,aar,maven,consumer,
  releaseApk:apkIn(releaseDir),testApk:apkIn(testDir),
  binaries,checks:['release-AAR','local-Maven-publication','independent-no-Rust-consumer','R8-release-application','arm64-and-x86_64-ELF','16KB-ELF-load-alignment','16KB-APK-ZIP-alignment','portable-ELF-SONAME-and-dependencies','packaged-Apache2-and-native-notices'],deviceExecution:'not-run'});
} catch(error) {receipt.status='failed';receipt.error=error.message;process.exitCode=1;}
receipt.finishedAt=new Date().toISOString();save();console.log(JSON.stringify(receipt,null,2));
