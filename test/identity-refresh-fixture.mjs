import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const canonical = value => Array.isArray(value) ? `[${value.map(canonical).join(',')}]`
  : value && typeof value === 'object' ? `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}` : JSON.stringify(value);

// A finite test application. Credentials and confidential exchange stay in the
// Node/native host roles; these business-method payloads contain only app data.
export function makeIdentityRefreshPack(destination) {
  mkdirSync(join(destination, 'blobs/sha256'), { recursive: true });
  const blob = value => { const bytes = typeof value === 'string' ? value : canonical(value), digest = sha(bytes); writeFileSync(join(destination, 'blobs/sha256', digest), bytes); return digest; };
  const guest = `
function assert(value,message){if(!value)throw Error(message);}
function host(name,value){return weappContainer.invoke('probe.'+name,value||{});}
function state(){return weappContainer.identity.getState();}
Page({onLoad(options){
 const run=async()=>{
  if(options.phase==='bob'){
   assert(state().hostLoggedIn&&!state().appLoggedIn&&!state().userInfo,'New account inherited old identity');
   const result=await host('bobVerified');
   assert(result.refreshError==='ERR_CANCELLED'&&result.manualUpdateError==='ERR_CANCELLED','Old native snapshot was accepted');
   assert(state().hostLoggedIn&&!state().appLoggedIn&&!state().userInfo,'Late Alice snapshot contaminated Bob');
   weappContainer.postMessage({kind:'passed',late:result,state:state()});return;
  }
  assert(state().hostLoggedIn&&!state().appLoggedIn,'Expected host-only initial snapshot');
  const results=[];
  for(const scenario of [{mode:'raw'},{mode:'alias'},{mode:'public'},{mode:'alias',disconnect:true}]){
   const login=await wx.login();const business=await host('exchange',{code:login.code});
   assert(business.businessSession&&!business.accessToken&&!business.hostAccessToken,'Confidential bearer entered guest');
   await host('refresh');assert(state().appLoggedIn,'Code exchange did not refresh application login');
   const before=await host('mode',scenario);await host('businessLogout',{businessSession:business.businessSession});
   const events=[];let failure;
   await wx.checkSession({fail:function(){events.push('fail');},complete:function(){events.push('complete');}}).then(
    function(){throw Error('Revoked session succeeded');},function(error){events.push('rejected');failure=error;});
   assert(events.join(',')==='fail,complete,rejected','Failure callback/complete/Promise order changed');
   assert(failure.code==='ERR_PERMISSION_DENIED'&&failure.message==='No live redeemed application session exists'&&failure.errCode===4017&&failure.errMsg==='checkSession:fail original reference rejection'&&failure.providerId==='reference-identity'&&failure.traceId==='identity-refresh-probe','Original failure fields were replaced');
   if(scenario.mode==='public')assert(failure.backendCode==='ERR_INVALID_APP_SESSION','Public backendCode was lost');
   const after=await host('stats');assert(after.identitySnapshots===before.identitySnapshots+1,'Failure did not attempt exactly one native snapshot refresh');
   assert(state().appLoggedIn===!!scenario.disconnect,'Best-effort snapshot outcome was misrepresented');
   results.push({scenario:scenario,events:events,error:failure,snapshot:state(),refreshAttempts:after.identitySnapshots-before.identitySnapshots});
   if(scenario.disconnect){await host('refresh');assert(!state().appLoggedIn,'Recovery refresh did not clear the revoked session');}
  }
  const login=await wx.login();await host('exchange',{code:login.code});await host('refresh');
  assert(state().appLoggedIn,'Late-refresh source must be an actual live Alice session');
  weappContainer.postMessage({kind:'scenarios-complete',results:results});
 };
 run().catch(function(error){weappContainer.postMessage({kind:'failed',message:String(error),error:error});});
}});`;
  const manifest = { schemaVersion: 1, protocolVersion: '1.0', compiler: { name: 'weapp-vite', commit: 'authored-finite-native-probe-not-a-compiler-build', platform: 'weapp' },
    app: { appJson: 'app.json', entryModule: 'app.js' }, routes: [{ id: 'route:pages/probe/index', path: 'pages/probe/index', kind: 'page', config: 'pages/probe/index.json', entryModule: 'pages/probe/index.js' }],
    modules: [{ id: 'module:app.js', format: 'commonjs', scope: 'app', sha256: blob('App({});') }, { id: 'module:app.json', format: 'json', scope: 'app', sha256: blob({ pages: ['pages/probe/index'] }) }, { id: 'module:pages/probe/index.js', format: 'commonjs', scope: 'page', sha256: blob(guest) }, { id: 'module:pages/probe/index.json', format: 'json', scope: 'page', sha256: blob({}) }],
    templates: [{ id: 'template:pages/probe/index.wxml', wxmlAst: `sha256:${blob({ version: 1, nodes: [{ kind: 'text', value: 'Native identity refresh regression', bindings: [] }] })}`, bindings: [] }],
    styles: [], components: [], assets: [], capabilities: Object.fromEntries(['wx.login', 'wx.checkSession', 'weappContainer.identity.getState'].map(api => [api, 'partial'])), integrity: { algorithm: 'sha256', root: '' } };
  manifest.integrity.root = sha(canonical(manifest)); writeFileSync(join(destination, 'manifest.json'), canonical(manifest)); return manifest.integrity.root;
}

/** Actual reference service with controlled error serialization/connection loss.
 * No success, code, session, or snapshot is fabricated by the fault controls.
 */
export async function createIdentityRefreshServer({ referenceRoot, controlToken, packRoot }) {
  const { HostServices } = await import(pathToFileURL(join(referenceRoot, 'src/service.mjs')).href);
  const { createHostServicesHandler } = await import(pathToFileURL(join(referenceRoot, 'src/server.mjs')).href);
  const { equalSecret } = await import(pathToFileURL(join(referenceRoot, 'src/crypto.mjs')).href);
  const { randomBytes } = await import('node:crypto');
  const clientSecret = randomBytes(32).toString('base64url'), clientId = 'identity-probe-business';
  const application = { hostAppId: 'identity-refresh-host', tenantId: 'identity-refresh-tenant', publisherId: 'identity-refresh-publisher', appId: 'identity-refresh-probe', environment: 'develop', version: '1', packRoot };
  // This historical transport fixture exercises v1, not the new v2 consent contract.
  const service = new HostServices({ issuerSecret: randomBytes(32).toString('base64url'), identitySecret: randomBytes(32).toString('base64url'), applications: [{ miniAppId: application.appId, identityProtocol: '1.0', hostAppId: application.hostAppId, tenantId: application.tenantId, environment: application.environment, clientId, clientSecret, trustedPackRoots: [packRoot] }] });
  const core = createHostServicesHandler(service), businessSessions = new Map(), active = new Set();
  const stats = { identitySnapshots: 0, checkFailures: 0, disconnectedSnapshots: 0, heldSnapshots: 0, releasedSnapshots: 0 };
  let mode = 'raw', disconnect = false, hold = false, held = null, closing = false;
  const send = (response, status, value) => { response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(JSON.stringify(value)); };
  const body = async request => { let bytes = Buffer.alloc(0); for await (const chunk of request) { bytes = Buffer.concat([bytes, chunk]); if (bytes.length > 262144) throw Error('Fixture body limit'); } const value = JSON.parse(bytes); if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('Fixture object required'); return value; };
  const bearer = request => /^Bearer (.+)$/.exec(request.headers.authorization ?? '')?.[1];
  const controlled = request => { if (!equalSecret(bearer(request), controlToken)) throw Error('Native fixture control required'); };
  const capture = () => ({ writeHead(status, headers) { this.status = status; this.headers = headers; }, end(value) { this.bytes = value; } });
  const server = createServer((request, response) => {
    const operation = (async () => {
      try {
        if (closing) throw Error('Fixture closed');
        const path = new URL(request.url, 'http://127.0.0.1').pathname;
        if (path === '/fixture/host') {
          controlled(request); const input = await body(request);
          if (!['alice', 'bob'].includes(input.principalId)) throw Error('Unknown fixture principal');
          const issued = service.issueHostSession({ hostAppId: application.hostAppId, tenantId: application.tenantId, environment: application.environment, principalId: input.principalId, principalEpoch: input.principalEpoch });
          return send(response, 201, { ...issued, application, account: { principalId: input.principalId, epoch: issued.principalEpoch } });
        }
        if (path === '/fixture/mode') { controlled(request); const input = await body(request); if (!['raw', 'alias', 'public'].includes(input.mode)) throw Error('Unknown failure mode'); mode = input.mode; disconnect = input.disconnect === true; return send(response, 200, stats); }
        if (path === '/fixture/stats') { controlled(request); await body(request); return send(response, 200, { ...stats, held: Boolean(held), activeRequests: active.size }); }
        if (path === '/fixture/hold') { controlled(request); await body(request); if (hold || held) throw Error('Snapshot already held'); hold = true; return send(response, 200, stats); }
        if (path === '/fixture/release') { controlled(request); await body(request); if (!held) throw Error('No held snapshot'); held(); return send(response, 200, stats); }
        if (path === '/business/exchange') { const input = await body(request), app = service.exchangeLogin(clientId, clientSecret, input.code), businessSession = randomBytes(32).toString('base64url'); businessSessions.set(businessSession, app.accessToken); return send(response, 200, { businessSession, appUserId: app.appUserId }); }
        if (path === '/business/logout') { const input = await body(request), token = businessSessions.get(input.businessSession); if (!token) throw Error('Unknown business session'); const result = service.logoutApp(token); businessSessions.delete(input.businessSession); return send(response, 200, result); }
        if (path === '/v1/host/identity-state') {
          stats.identitySnapshots++;
          if (disconnect) { disconnect = false; stats.disconnectedSnapshots++; request.socket.destroy(); return; }
          if (hold) {
            hold = false; const captured = capture(); await core(request, captured);
            if (captured.status !== 200) throw Error('Held snapshot was not produced by the actual reference service');
            stats.heldSnapshots++; await new Promise(resolve => { held = resolve; }); held = null; stats.releasedSnapshots++;
            if (!response.destroyed) { response.writeHead(captured.status, captured.headers); response.end(captured.bytes); } return;
          }
        }
        if (path === '/v1/host/check-session') {
          const captured = capture(); await core(request, captured); const original = JSON.parse(captured.bytes);
          if (captured.status === 401 && original.error === 'invalid_app_session') {
            stats.checkFailures++; const fields = { errCode: 4017, errMsg: 'checkSession:fail original reference rejection', providerId: 'reference-identity', traceId: 'identity-refresh-probe' };
            const failure = mode === 'raw' ? { ...original, ...fields } : { error: { code: mode === 'alias' ? 'ERR_INVALID_APP_SESSION' : 'permission_denied', message: original.message, ...fields, ...(mode === 'public' ? { backendCode: 'ERR_INVALID_APP_SESSION' } : {}) } };
            return send(response, captured.status, failure);
          }
          response.writeHead(captured.status, captured.headers); response.end(captured.bytes); return;
        }
        if (path.startsWith('/v1/')) return await core(request, response);
        send(response, 404, { error: 'fixture_not_found' });
      } catch { if (!response.destroyed && !response.headersSent) send(response, 500, { error: 'fixture_failed' }); else response.end(); }
    })(); active.add(operation); void operation.finally(() => active.delete(operation));
  });
  server.requestTimeout = 20000; server.headersTimeout = 10000;
  return { server, observations: () => ({ ...stats, activeRequests: active.size, held: Boolean(held) }), async close() { closing = true; held?.(); await Promise.allSettled([...active]); businessSessions.clear(); await new Promise((done, reject) => server.close(error => error ? reject(error) : done())); service.close(); } };
}
