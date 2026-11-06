import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const canonical = value => Array.isArray(value) ? `[${value.map(canonical).join(',')}]` : value !== null && typeof value === 'object' ? `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}` : JSON.stringify(value);
const sha = value => createHash('sha256').update(value).digest('hex');
export function makeHostServicesFixture(destination) {
  mkdirSync(join(destination, 'blobs/sha256'), { recursive: true });
  const blob = value => { const bytes = typeof value === 'string' ? value : canonical(value), hash = sha(bytes); writeFileSync(join(destination, 'blobs/sha256', hash), bytes); return hash; };
  const page = `
function send(value) { weappContainer.postMessage(value); }
function operation(fs, method, options) { return new Promise((resolve,reject) => fs[method](Object.assign({},options,{success:resolve,fail:reject}))); }
Page({
 onLoad(options) {
  this.phase = options.phase; if(this.phase!=='A'&&this.phase!=='B'){send({kind:'probe-failure',stage:'launch',options});return;} this.fs = wx.getFileSystemManager();
  const fs = this.fs;
  const run = async () => {
   const initial=weappContainer.identity.getState();
   if(!initial.hostLoggedIn||initial.appLoggedIn||initial.userInfo)throw new Error('initial identity must be host-only without disclosure');
   await new Promise((resolve,reject) => wx.setScreenBrightness({value:0.4,success:resolve,fail:reject}));
   await new Promise((resolve,reject) => wx.setKeepScreenOn({keepScreenOn:true,success:resolve,fail:reject}));
   if (this.phase === 'A') {
    const nativeRequest=(path,data)=>wx.request({url:options.networkOrigin+path,data});
    let headers=false,chunks=false;const responseTask=wx.request({url:options.networkOrigin+'/json',data:{sample:'actual-query'},enableChunked:true});responseTask.onHeadersReceived(()=>{headers=true;});responseTask.onChunkReceived(()=>{chunks=true;});const nativeJson=await responseTask;
    if(nativeJson.data.query!=='actual-query'||!headers||!chunks)throw new Error('native HTTP query/header/chunk mismatch '+JSON.stringify({response:nativeJson,headers,chunks}));
    await operation(fs,'writeFile',{filePath:'wxfile://user/upload.txt',data:'native-upload-body'});
    const upload=await wx.uploadFile({url:options.networkOrigin+'/upload',filePath:'wxfile://user/upload.txt',name:'file',formData:{probe:'real'}});const uploaded=JSON.parse(upload.data);if(!uploaded.multipart||!uploaded.containsFile)throw new Error('native multipart did not transport managed bytes');
    const download=await wx.downloadFile({url:options.networkOrigin+'/download'});const downloaded=await operation(fs,'readFile',{filePath:download.tempFilePath,encoding:'utf8'});if(downloaded.data!=='native-network-download\\n')throw new Error('native download did not persist actual bytes');
    const socket=wx.connectSocket({url:options.networkOrigin.replace('http://','ws://')+'/socket'});const opened=new Promise((resolve,reject)=>{socket.onOpen(resolve);socket.onError(reject);});const echoed=new Promise((resolve,reject)=>{socket.onMessage(resolve);socket.onError(reject);});const socketClosed=new Promise(resolve=>socket.onClose(resolve));await opened;await socket.send({data:'actual-websocket-echo'});if((await echoed).data!=='actual-websocket-echo')throw new Error('native WebSocket echo mismatch');await socket.close({code:1000,reason:'probe done'});await socketClosed;
    const recorder=wx.getRecorderManager();let invalidBitrate=false;try{await recorder.start({sampleRate:8000,encodeBitRate:64000});}catch{invalidBitrate=true;}if(!invalidBitrate)throw new Error('recorder accepted incompatible sample rate/bitrate');const defaults=new Promise(resolve=>{recorder.onStop(value=>resolve({success:true,...value}));recorder.onError(value=>resolve({success:false,...value}));});await recorder.start({duration:5000});await new Promise(resolve=>setTimeout(resolve,1200));let defaultRejected=false;try{await recorder.stop();}catch{defaultRejected=true;}const defaultResult=await defaults;if(defaultResult.success===defaultRejected||(!defaultResult.success&&!defaultResult.errMsg.includes('requested 8000 Hz / 2 channels')))throw new Error('recorder default format result was inconsistent');send({kind:'recorder-default-result',...defaultResult});const recorded=new Promise((resolve,reject)=>{recorder.onStop(resolve);recorder.onError(reject);});await recorder.start({duration:5000,numberOfChannels:1});await new Promise(resolve=>setTimeout(resolve,1200));await recorder.stop();const audio=await recorded;if(!audio.tempFilePath||audio.fileSize<=128)throw new Error('native recorder returned no audio file');send({kind:'transport-recording',path:audio.tempFilePath});
    if(options.probeWifi==='true'){
     let connectedEvents=0,resolveList;const found=new Promise(resolve=>{resolveList=resolve;});const listener=value=>{if(value.wifi.SSID)connectedEvents++;};const scanned=value=>resolveList(value.wifiList);wx.onWifiConnectedWithPartialInfo(listener);wx.onGetWifiList(scanned);
     await wx.startWifi();const partial=await wx.getConnectedWifi({partialInfo:true});if(!partial.wifi.SSID||Object.keys(partial.wifi).some(key=>key!=='SSID'))throw new Error('partial Wi-Fi information contract mismatch');await wx.getWifiList();let timer;const list=await Promise.race([found,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('No actual fresh Wi-Fi scan event')),20000);})]);clearTimeout(timer);const full=await wx.getConnectedWifi({partialInfo:false});if(!list.some(item=>item.SSID===full.wifi.SSID&&item.BSSID===full.wifi.BSSID)||full.wifi.SSID!==partial.wifi.SSID||!connectedEvents)throw new Error('Wi-Fi native query and real scan disagree');await wx.stopWifi();const count=connectedEvents;await new Promise(resolve=>setTimeout(resolve,1000));let stopped=false;try{await wx.getConnectedWifi({partialInfo:true});}catch(error){stopped=error.errCode===12000;}if(!stopped||count!==connectedEvents)throw new Error('Wi-Fi stop failed to retire module/events');await wx.startWifi();await new Promise(resolve=>setTimeout(resolve,400));if(connectedEvents<=count)throw new Error('Wi-Fi stop/start lost the existing subscription');await wx.stopWifi();wx.offGetWifiList(scanned);wx.offWifiConnectedWithPartialInfo(listener);send({kind:'wifi-verified',wifi:full.wifi,scanCount:list.length,partialEvents:count,restartPartialEvents:connectedEvents});
    }
    if(options.probeLocation==='true'){
     let count=0,resolveFix;const fixed=new Promise(resolve=>{resolveFix=resolve;});const listener=value=>{count++;if(Math.abs(value.latitude-30.456)<0.00001&&Math.abs(value.longitude-120.123)<0.00001)resolveFix(value);};
     wx.onLocationChange(listener);await wx.startLocationUpdate({type:'wgs84'});send({kind:'location-ready'});let timer;await Promise.race([fixed,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('No actual native GPS update')),20000);})]);clearTimeout(timer);
     await wx.stopLocationUpdate();const stopped=count;await new Promise(resolve=>setTimeout(resolve,1500));if(count!==stopped)throw new Error('Location callbacks survived stop');wx.offLocationChange(listener);send({kind:'location-verified'});
    }
    const supported=await wx.checkIsSupportSoterAuthentication();const enrolled=await wx.checkIsSoterEnrolledInDevice({checkAuthMode:'fingerPrint'});if(supported.supportMode.length||enrolled.isEnrolled)throw new Error('SOTER support fabricated without a native signer');
    await wx.addPhoneContact({firstName:options.systemMarker,mobilePhoneNumber:'15500009999',remark:options.systemMarker});
    await wx.addPhoneCalendar({title:options.systemMarker,startTime:1800000000,endTime:1800003600,alarm:true,alarmOffset:600});send({kind:'system-commits'});
    const request=async(path,data,method='POST')=>{const response=await wx.request({url:options.businessOrigin+path,method,data,header:{'content-type':'application/json',...(method==='GET'?{Authorization:'Bearer '+this.businessSession}:{})}});if(response.statusCode<200||response.statusCode>=300)throw new Error('business '+path+' '+JSON.stringify(response.data));return response.data;};
    this.business=request;
    const login=await wx.login();
    if(weappContainer.identity.getState().appLoggedIn)throw new Error('issuing a code must not create an app login');
    const business=await request('/demo/login',{code:login.code});this.businessSession=business.businessSession;
    await weappContainer.invoke('demo.identity.refresh',{});await wx.checkSession();
    const logged=weappContainer.identity.getState();if(!logged.appLoggedIn||logged.userInfo)throw new Error('redemption must login without profile consent');
    const profile=await wx.getUserProfile({desc:'Native development probe nickname disclosure'});if(!profile.userInfo.nickName)throw new Error('profile was not disclosed after separate consent');
    const phone=new Promise((resolve,reject)=>{this.phoneResolve=resolve;this.phoneReject=reject;});send({kind:'phone-ready'});const phoneResult=await phone;
    const order=await request('/demo/orders',{businessSession:this.businessSession,productId:'reference-item',idempotencyKey:'android-native-probe'});
    await weappContainer.payment.request({orderId:order.orderId});
    const paid=await request('/demo/orders/'+encodeURIComponent(order.orderId),undefined,'GET');if(paid.status!=='paid')throw new Error('native payment success without server paid state');
    send({kind:'identity-payment',orderStatus:paid.status,phoneSuffix:phoneResult.phoneNumber.slice(-4)});
    await operation(fs,'writeFile',{filePath:'wxfile://user/alpha.txt',data:'account-A',encoding:'utf8'});
    fs.appendFileSync('wxfile://user/alpha.txt','-sync','utf8');
    const value=await operation(fs,'readFile',{filePath:'wxfile://user/alpha.txt',encoding:'utf8'});
    if(value.data!=='account-A-sync')throw new Error('read did not retain actual native bytes');
    let rejected=false;try{await operation(fs,'writeFile',{filePath:'wxfile://user/../escape',data:'bad'});}catch(error){rejected=true;}
    if(!rejected)throw new Error('path traversal was accepted');
    this.pendingSocket=wx.connectSocket({url:options.networkOrigin.replace('http://','ws://')+'/socket'});await new Promise((resolve,reject)=>{this.pendingSocket.onOpen(resolve);this.pendingSocket.onError(reject);});
    this.pendingRequest=wx.request({url:options.networkOrigin+'/slow'});this.pendingRequest.catch(()=>{});await new Promise(resolve=>setTimeout(resolve,100));
    send({kind:'files-A'});
   } else {
    let drained=false;for(let attempt=0;attempt<30;attempt++){const state=await wx.request({url:options.networkOrigin+'/stats'});if(state.data.sockets===0&&state.data.slowRequests===0){drained=true;break;}await new Promise(resolve=>setTimeout(resolve,50));}if(!drained)throw new Error('old native HTTP/socket peer survived account retirement');
    let missing=false;try{await operation(fs,'access',{path:'wxfile://user/alpha.txt'});}catch(error){missing=true;}
    if(!missing)throw new Error('account A file leaked into B');
    await operation(fs,'writeFile',{filePath:'wxfile://user/beta.txt',data:'account-B'});
    send({kind:'files-B'});
   }
  };
  run().catch(error=>send({kind:'probe-failure',stage:'files',message:String(error),details:error}));
 },
 onPhone(event) {
  const detail=event.detail||{};
  if(!this.phoneResolve)return;
  if(!detail.code){this.phoneReject(new Error(detail.errMsg||'phone button did not return a code'));return;}
  this.business('/demo/phone',{businessSession:this.businessSession,code:detail.code}).then(this.phoneResolve,this.phoneReject);
 },
 onCameraReady() {
  if(this.phase!=='B'||this.photoRequested)return; this.photoRequested=true;
  setTimeout(()=>{
   const camera=wx.createCameraContext();
   camera.takePhoto({quality:'normal',success:value=>send({kind:'photo',path:value.tempImagePath}),fail:error=>send({kind:'probe-failure',stage:'camera',details:error})});
  },800);
 },
 onCameraError(event) { send({kind:'camera-error',details:event.detail}); }
});`;
  const text = value => ({ kind: 'text', value, bindings: [] });
  const element = (tag, attrs, children, events) => ({ kind: 'element', tag, attrs, bindings: [], ...(events ? { directives: { events } } : {}), children });
  const template = { version: 1, nodes: [element('view', {class:'root'}, [
    element('text', {class:'title'}, [text('Native host services development probe')]),
    element('button', {id:'probe-phone',class:'phone','open-type':'getPhoneNumber',bindgetphonenumber:'onPhone'}, [text('Authorize test phone')], {bindgetphonenumber:'onPhone'}),
    element('camera', {id:'probe-camera',class:'camera','device-position':'back',flash:'off',mode:'normal',bindinitdone:'onCameraReady',binderror:'onCameraError'}, [], {bindinitdone:'onCameraReady',binderror:'onCameraError'}),
  ])] };
  const style = { version: 1, imports: [], rules: [
    {selectors:['.root'],nested:[],declarations:[['padding','12px'],['background','#f5f7fa'],['height','100%']].map(([property,value])=>({property,value,important:false}))},
    {selectors:['.title'],nested:[],declarations:[['display','block'],['height','60px'],['font-size','18px']].map(([property,value])=>({property,value,important:false}))},
    {selectors:['.phone'],nested:[],declarations:[['display','block'],['width','300px'],['height','50px'],['background','#e0eaff']].map(([property,value])=>({property,value,important:false}))},
    {selectors:['.camera'],nested:[],declarations:[['display','block'],['width','300px'],['height','220px']].map(([property,value])=>({property,value,important:false}))},
  ] };
  const manifest = {
    schemaVersion:1,protocolVersion:'1.0',compiler:{name:'weapp-vite',commit:'authored-native-host-services-probe-not-a-compiler-build',platform:'weapp'},
    app:{appJson:'app.json',entryModule:'app.js'},routes:[{id:'route:pages/probe/index',path:'pages/probe/index',kind:'page',config:'pages/probe/index.json',entryModule:'pages/probe/index.js'}],
    modules:[{id:'module:app.js',format:'commonjs',scope:'app',sha256:blob('App({});')},{id:'module:app.json',format:'json',scope:'app',sha256:blob({pages:['pages/probe/index']})},{id:'module:pages/probe/index.js',format:'commonjs',scope:'page',sha256:blob(page)},{id:'module:pages/probe/index.json',format:'json',scope:'page',sha256:blob({})}],
    templates:[{id:'template:pages/probe/index.wxml',wxmlAst:`sha256:${blob(template)}`,bindings:[]}],
    styles:[{id:'style:app.wxss',scope:'app',wxssIr:`sha256:${blob({version:1,imports:[],rules:[]})}`},{id:'style:pages/probe/index.wxss',scope:'page',wxssIr:`sha256:${blob(style)}`}],
    components:[],assets:[],capabilities:Object.fromEntries(['wx.startWifi','wx.stopWifi','wx.getConnectedWifi','wx.connectWifi','wx.getWifiList','wx.onGetWifiList','wx.offGetWifiList','wx.onWifiConnected','wx.offWifiConnected','wx.onWifiConnectedWithPartialInfo','wx.offWifiConnectedWithPartialInfo','wx.startLocationUpdate','wx.stopLocationUpdate','wx.onLocationChange','wx.offLocationChange','wx.onLocationChangeError','wx.offLocationChangeError','wx.timer','wx.addPhoneContact','wx.addPhoneCalendar','wx.checkIsSupportSoterAuthentication','wx.checkIsSoterEnrolledInDevice','wx.request','wx.uploadFile','wx.downloadFile','object:RequestTask.onHeadersReceived','object:RequestTask.onChunkReceived','wx.connectSocket','object:SocketTask.onOpen','object:SocketTask.onClose','object:SocketTask.onMessage','object:SocketTask.onError','object:SocketTask.send','object:SocketTask.close','wx.getRecorderManager','object:RecorderManager.start','object:RecorderManager.stop','object:RecorderManager.onStop','object:RecorderManager.onError','wx.login','wx.checkSession','wx.getUserProfile','component:button.open-type.getPhoneNumber','weappContainer.identity.authorizePhone','weappContainer.identity.getState','weappContainer.payment.request','wx.getFileSystemManager','object:FileSystemManager.writeFile','object:FileSystemManager.readFile','object:FileSystemManager.access','object:FileSystemManager.appendFileSync','wx.setScreenBrightness','wx.setKeepScreenOn','component:camera','wx.createCameraContext','object:CameraContext.takePhoto'].map(name=>[name,'partial'])),integrity:{algorithm:'sha256',root:''},
  };
  manifest.integrity.root=sha(canonical(manifest));writeFileSync(join(destination,'manifest.json'),canonical(manifest));return manifest.integrity.root;
}
