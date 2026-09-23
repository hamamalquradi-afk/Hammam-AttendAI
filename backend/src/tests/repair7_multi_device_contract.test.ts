import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {rm} from 'node:fs/promises';

const port=18159,base=`http://127.0.0.1:${port}`,store='/tmp/hammam-repair7-multidevice.json';
const workspace='workspace-shared',deploymentToken='repair7-bootstrap-token',tokenHash=createHash('sha256').update(deploymentToken).digest('hex');
const teacher=(version:number,name:string)=>({workspace,entityType:'Teacher',entityId:'teacher-1',operation:'UPSERT',entityVersion:version,updatedAt:2000+version,payload:{id:'teacher-1',fullName:name,normalizedName:name.toLowerCase(),phone:null,whatsapp:null,email:null,preferredNotificationChannel:null,notificationsEnabled:true,createdAt:1000,updatedAt:2000+version,archivedAt:null,version}});
function start(){return spawn(process.execPath,['dist/server.js'],{env:{...process.env,PORT:String(port),CLOUD_SYNC_ENABLED:'true',SYNC_STORE_PATH:store,APP_ENV:'development',AUTH_TOKEN_HASH:tokenHash,SESSION_TTL_SECONDS:'3600'},stdio:'ignore'})}
async function stop(child:ReturnType<typeof start>){child.kill();await new Promise(r=>setTimeout(r,150))}
async function waitReady(){for(let i=0;i<60;i++){try{if((await fetch(`${base}/health`)).ok)return}catch{}await new Promise(r=>setTimeout(r,50))}throw new Error('server not ready')}
async function post(path:string,body:any,bearer:string|null,key:string){const headers:Record<string,string>={'content-type':'application/json','idempotency-key':key};if(bearer)headers.authorization=`Bearer ${bearer}`;const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(body)});return{status:r.status,body:await r.json() as any}}

async function bootstrap(){
 const b=await post('/api/v1/auth/bootstrap',{displayName:'Shared Account',workspaceId:workspace},deploymentToken,'bootstrap');assert.equal(b.status,201);
 const s=await post('/api/v1/auth/session',{accountId:String(b.body.accountId)},String(b.body.accountCredential),'session');assert.equal(s.status,201);
 return String(s.body.sessionToken)
}

test('Repair-7 multi-device contract keeps independent cursors, idempotency, conflicts and restart persistence',async()=>{
 await rm(store,{force:true});let child=start();await waitReady();
 try{
  const session=await bootstrap();
  let cursorA=0,cursorB=0;
  const a1=await post('/api/v1/sync/push',teacher(1,'Teacher One'),session,'device-a:teacher-1:v1');assert.equal(a1.status,200);assert.equal(a1.body.persisted,true);
  const bPull1=await post('/api/v1/sync/pull',{workspace,cursor:cursorB,limit:50},session,'device-b:pull:0');assert.equal(bPull1.status,200);assert.equal(bPull1.body.changes.length,1);assert.equal(bPull1.body.changes[0].payload.fullName,'Teacher One');cursorB=Number(bPull1.body.nextCursor);
  const aPull1=await post('/api/v1/sync/pull',{workspace,cursor:cursorA,limit:50},session,'device-a:pull:0');assert.equal(aPull1.status,200);assert.equal(aPull1.body.changes.length,1);cursorA=Number(aPull1.body.nextCursor);

  const replay=await post('/api/v1/sync/push',teacher(1,'Teacher One'),session,'device-a:teacher-1:v1');assert.equal(replay.status,200);assert.equal(replay.body.serverVersion,a1.body.serverVersion);

  const b2=await post('/api/v1/sync/push',teacher(2,'Teacher Two'),session,'device-b:teacher-1:v2');assert.equal(b2.status,200);
  const aPull2=await post('/api/v1/sync/pull',{workspace,cursor:cursorA,limit:50},session,`device-a:pull:${cursorA}`);assert.equal(aPull2.status,200);assert.equal(aPull2.body.changes.length,1);assert.equal(aPull2.body.changes[0].payload.fullName,'Teacher Two');cursorA=Number(aPull2.body.nextCursor);

  const staleCursor=await post('/api/v1/sync/pull',{workspace,cursor:0,limit:50},session,'device-b:stale-cursor');assert.equal(staleCursor.status,200);assert.equal(staleCursor.body.changes.length,2);assert.ok(staleCursor.body.changes[1].sequence>staleCursor.body.changes[0].sequence);
  const staleWrite=await post('/api/v1/sync/push',teacher(1,'Old Teacher'),session,'device-a:teacher-1:stale');assert.equal(staleWrite.status,409);assert.equal(staleWrite.body.error,'SYNC_VERSION_CONFLICT');
  const sameVersionConflict=await post('/api/v1/sync/push',teacher(2,'Conflicting Teacher'),session,'device-a:teacher-1:conflict-v2');assert.equal(sameVersionConflict.status,409);assert.equal(sameVersionConflict.body.error,'SYNC_CONFLICT');

  await stop(child);child=start();await waitReady();
  const bAfterReconnect=await post('/api/v1/sync/pull',{workspace,cursor:cursorB,limit:50},session,`device-b:reconnect:${cursorB}`);assert.equal(bAfterReconnect.status,200);assert.equal(bAfterReconnect.body.changes.length,1);assert.equal(bAfterReconnect.body.changes[0].payload.fullName,'Teacher Two');
  assert.equal(Number(bAfterReconnect.body.nextCursor),cursorA);
 }finally{await stop(child);await rm(store,{force:true})}
});
