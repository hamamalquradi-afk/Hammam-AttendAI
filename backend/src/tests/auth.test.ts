import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile,rm,writeFile} from 'node:fs/promises';

const port=18120,base=`http://127.0.0.1:${port}`,store='/tmp/hammam-auth-node-test.json';
const bootstrapToken='deployment-bootstrap-secret';
const bootstrapHash=createHash('sha256').update(bootstrapToken).digest('hex');
function start(){return spawn(process.execPath,['dist/server.js'],{env:{...process.env,PORT:String(port),CLOUD_SYNC_ENABLED:'true',SYNC_STORE_PATH:store,APP_ENV:'development',AUTH_TOKEN_HASH:bootstrapHash,SYNC_WORKSPACE_ID:'workspace-a',SESSION_TTL_SECONDS:'3600'},stdio:'ignore'})}
async function waitReady(){for(let i=0;i<60;i++){try{if((await fetch(`${base}/health`)).ok)return}catch{}await new Promise(r=>setTimeout(r,50))}throw new Error('server not ready')}
async function stop(child:ReturnType<typeof start>){child.kill();await new Promise(r=>setTimeout(r,150))}
async function post(path:string,value:any={},auth:string|null=null){const headers:Record<string,string>={'content-type':'application/json'};if(auth!==null)headers.authorization=`Bearer ${auth}`;const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(value)});return{status:r.status,body:await r.json() as any}}

test('account provisioning, membership and persistent session foundation is secure',async()=>{
 await rm(store,{force:true});let child=start();await waitReady();
 try{
  const status0=await post('/api/v1/auth/status');assert.equal(status0.status,200);assert.equal(status0.body.provisioningRequired,true);
  const missingBootstrap=await post('/api/v1/auth/bootstrap',{displayName:'Owner'});assert.equal(missingBootstrap.status,401);assert.equal(missingBootstrap.body.error,'AUTH_REQUIRED');
  const badBootstrap=await post('/api/v1/auth/bootstrap',{displayName:'Owner'},'wrong-secret');assert.equal(badBootstrap.status,401);assert.equal(badBootstrap.body.error,'AUTH_INVALID');

  const boot=await post('/api/v1/auth/bootstrap',{displayName:'Owner',workspaceId:'workspace-a',workspaceName:'Workspace A'},bootstrapToken);
  assert.equal(boot.status,201);assert.equal(boot.body.workspaceId,'workspace-a');assert.ok(String(boot.body.accountId).startsWith('acct_'));assert.ok(String(boot.body.accountCredential).length>=40);
  const accountA=String(boot.body.accountId),credentialA=String(boot.body.accountCredential);
  assert.notEqual(accountA,'Owner');assert.equal(JSON.stringify(boot.body).includes(bootstrapToken),false);assert.equal('credentialHash' in boot.body,false);assert.equal('tokenHash' in boot.body,false);

  const duplicate=await post('/api/v1/auth/bootstrap',{displayName:'Another'},bootstrapToken);assert.equal(duplicate.status,409);assert.equal(duplicate.body.error,'PROVISIONING_COMPLETE');
  const status1=await post('/api/v1/auth/status');assert.equal(status1.body.provisioningRequired,false);

  const invalidCredential=await post('/api/v1/auth/session',{accountId:accountA},'wrong-account-credential');assert.equal(invalidCredential.status,401);assert.equal(invalidCredential.body.error,'AUTH_INVALID');
  const issued=await post('/api/v1/auth/session',{accountId:accountA},credentialA);assert.equal(issued.status,201);assert.equal(issued.body.accountId,accountA);assert.deepEqual(issued.body.workspaces,['workspace-a']);assert.ok(issued.body.expiresAt>Date.now());
  const sessionA=String(issued.body.sessionToken);assert.ok(sessionA.length>=40);

  const identity=await post('/api/v1/auth/identity',{},sessionA);assert.equal(identity.status,200);assert.equal(identity.body.accountId,accountA);assert.deepEqual(identity.body.workspaces,['workspace-a']);assert.equal(JSON.stringify(identity.body).includes(sessionA),false);assert.equal(JSON.stringify(identity.body).includes(credentialA),false);assert.equal('tokenHash' in identity.body,false);assert.equal('credentialHash' in identity.body,false);
  const persisted1=await readFile(store,'utf8');assert.equal(persisted1.includes(credentialA),false);assert.equal(persisted1.includes(sessionA),false);assert.equal(persisted1.includes(bootstrapToken),false);

  await stop(child);child=start();await waitReady();
  const afterRestart=await post('/api/v1/auth/identity',{},sessionA);assert.equal(afterRestart.status,200);assert.equal(afterRestart.body.accountId,accountA);

  const provisionB=await post('/api/v1/auth/provision',{displayName:'Second account',workspaceId:'workspace-b',workspaceName:'Workspace B'},bootstrapToken);
  assert.equal(provisionB.status,201);assert.equal(provisionB.body.workspaceId,'workspace-b');
  const accountB=String(provisionB.body.accountId),credentialB=String(provisionB.body.accountCredential);
  const sessionBIssued=await post('/api/v1/auth/session',{accountId:accountB},credentialB);assert.equal(sessionBIssued.status,201);assert.deepEqual(sessionBIssued.body.workspaces,['workspace-b']);
  const sessionB=String(sessionBIssued.body.sessionToken);
  const identityB=await post('/api/v1/auth/identity',{},sessionB);assert.deepEqual(identityB.body.workspaces,['workspace-b']);assert.equal(identityB.body.accountId,accountB);

  const logout=await post('/api/v1/auth/logout',{},sessionA);assert.equal(logout.status,200);
  const revoked=await post('/api/v1/auth/identity',{},sessionA);assert.equal(revoked.status,401);assert.equal(revoked.body.error,'AUTH_REVOKED');

  const reissued=await post('/api/v1/auth/session',{accountId:accountA},credentialA);assert.equal(reissued.status,201);const expiringSession=String(reissued.body.sessionToken);
  const persisted=JSON.parse(await readFile(store,'utf8')) as any;const expiringHash=createHash('sha256').update(expiringSession).digest('hex');const sessionEntry=Object.values(persisted.sessions as Record<string,any>).find((s:any)=>s.tokenHash===expiringHash) as any;
  assert.ok(sessionEntry);sessionEntry.expiresAt=Date.now()-1;await writeFile(store,JSON.stringify(persisted),'utf8');
  const expired=await post('/api/v1/auth/identity',{},expiringSession);assert.equal(expired.status,401);assert.equal(expired.body.error,'AUTH_EXPIRED');

  const noSession=await post('/api/v1/auth/identity');assert.equal(noSession.status,401);assert.equal(noSession.body.error,'AUTH_REQUIRED');
  const invalidSession=await post('/api/v1/auth/identity',{},'not-a-session');assert.equal(invalidSession.status,401);assert.equal(invalidSession.body.error,'AUTH_INVALID');
 }finally{await stop(child);await rm(store,{force:true})}
});
