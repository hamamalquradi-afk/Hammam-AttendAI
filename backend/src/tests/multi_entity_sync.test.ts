import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile,rm,writeFile} from 'node:fs/promises';

const port=18129,base=`http://127.0.0.1:${port}`,store='/tmp/hammam-sync-multi-entity-test.json',workspaceA='workspace-a',workspaceB='workspace-b';
const deploymentToken='sync-bootstrap-token',tokenHash=createHash('sha256').update(deploymentToken).digest('hex');
const teacher=(workspace:string,id:string,version=1,name='Dr One')=>({workspace,entityType:'Teacher',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:2000+version,payload:{id,fullName:name,normalizedName:name.toLowerCase(),phone:null,whatsapp:null,email:null,preferredNotificationChannel:null,notificationsEnabled:true,createdAt:1000,updatedAt:2000+version,archivedAt:null,version}});
const policy=(workspace:string,id:string,version=1,name='Global Policy')=>({workspace,entityType:'AttendancePolicy',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:3000+version,payload:{id,name,scopeType:'GLOBAL',scopeId:null,fullAttendanceThreshold:.8,partialAttendanceThreshold:.5,lateAfterMinutes:10,earlyLeaveThresholdMinutes:5,absenceThreshold:.2,temporaryMissingGraceSeconds:30,minimumPresenceVerificationSeconds:60,confidenceThreshold:.7,createdAt:1000,updatedAt:3000+version,version}});
const clone=<T>(v:T):T=>JSON.parse(JSON.stringify(v));
async function waitReady(){for(let i=0;i<60;i++){try{if((await fetch(`${base}/health`)).ok)return}catch{}await new Promise(r=>setTimeout(r,50))}throw new Error('server not ready')}
function start(){return spawn(process.execPath,['dist/server.js'],{env:{...process.env,PORT:String(port),CLOUD_SYNC_ENABLED:'true',SYNC_STORE_PATH:store,APP_ENV:'development',AUTH_TOKEN_HASH:tokenHash,SYNC_WORKSPACE_ID:workspaceA,SESSION_TTL_SECONDS:'3600'},stdio:'ignore'})}
async function request(path:string,value:any,auth:string|null=null,key='test-key'){const headers:Record<string,string>={'content-type':'application/json','idempotency-key':key};if(auth)headers.authorization=`Bearer ${auth}`;const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(value)});return{status:r.status,body:await r.json() as any}}
async function stop(child:ReturnType<typeof start>){if(!child.killed)child.kill();await new Promise(r=>setTimeout(r,120))}
async function provision(){
 const boot=await request('/api/v1/auth/bootstrap',{displayName:'A',workspaceId:workspaceA},deploymentToken,'boot-a');assert.equal(boot.status,201);
 const sa=await request('/api/v1/auth/session',{accountId:String(boot.body.accountId)},String(boot.body.accountCredential),'session-a');assert.equal(sa.status,201);
 const b=await request('/api/v1/auth/provision',{displayName:'B',workspaceId:workspaceB},deploymentToken,'boot-b');assert.equal(b.status,201);
 const sb=await request('/api/v1/auth/session',{accountId:String(b.body.accountId)},String(b.body.accountCredential),'session-b');assert.equal(sb.status,201);
 return{a:String(sa.body.sessionToken),b:String(sb.body.sessionToken)};
}

test('5E syncs only strict Teacher and GLOBAL AttendancePolicy roots while preserving workspace/idempotency/cursor safety',async()=>{
 await rm(store,{force:true});let child=start();await waitReady();
 try{
  const sessions=await provision();
  const badTeacher=clone(teacher(workspaceA,'bad-t'));delete (badTeacher.payload as any).fullName;
  const badTeacherResult=await request('/api/v1/sync/push',badTeacher,sessions.a,'invalid-teacher');assert.equal(badTeacherResult.status,400);assert.equal(badTeacherResult.body.error,'SYNC_INVALID_PAYLOAD');
  const extraTeacher=clone(teacher(workspaceA,'extra-t'));(extraTeacher.payload as any).unexpected='x';assert.equal((await request('/api/v1/sync/push',extraTeacher,sessions.a,'invalid-teacher-extra')).status,400);
  const badTeacherId=clone(teacher(workspaceA,'id-a'));badTeacherId.payload.id='id-b';assert.equal((await request('/api/v1/sync/push',badTeacherId,sessions.a,'invalid-teacher-id')).status,400);
  const badTeacherVersion=clone(teacher(workspaceA,'ver-a'));badTeacherVersion.payload.version=2;assert.equal((await request('/api/v1/sync/push',badTeacherVersion,sessions.a,'invalid-teacher-version')).status,400);
  const badTeacherTimestamp=clone(teacher(workspaceA,'ts-a'));badTeacherTimestamp.payload.updatedAt=0;assert.equal((await request('/api/v1/sync/push',badTeacherTimestamp,sessions.a,'invalid-teacher-ts')).status,400);

  const scoped:any=clone(policy(workspaceA,'bad-scope'));scoped.payload.scopeType='SUBJECT';scoped.payload.scopeId='subject-x';assert.equal((await request('/api/v1/sync/push',scoped,sessions.a,'invalid-policy-scope')).status,400);
  const badThreshold=clone(policy(workspaceA,'bad-threshold'));badThreshold.payload.fullAttendanceThreshold=.1;assert.equal((await request('/api/v1/sync/push',badThreshold,sessions.a,'invalid-policy-threshold')).status,400);
  const badPolicyVersion=clone(policy(workspaceA,'bad-policy-version'));badPolicyVersion.payload.version=2;assert.equal((await request('/api/v1/sync/push',badPolicyVersion,sessions.a,'invalid-policy-version')).status,400);
  const afterInvalid=JSON.parse(await readFile(store,'utf8')) as any;assert.equal(afterInvalid.sequence,0);assert.equal(afterInvalid.mutations.length,0);assert.equal(Object.keys(afterInvalid.idempotency).some((k:string)=>k.includes('invalid-')),false);

  const t1=await request('/api/v1/sync/push',teacher(workspaceA,'t1'),sessions.a,'teacher:t1:1');assert.equal(t1.status,200);assert.equal(t1.body.persisted,true);
  const replay=await request('/api/v1/sync/push',teacher(workspaceA,'t1'),sessions.a,'teacher:t1:1');assert.equal(replay.status,200);assert.equal(replay.body.serverVersion,t1.body.serverVersion);
  const idemMismatch=await request('/api/v1/sync/push',teacher(workspaceA,'t1',1,'Changed'),sessions.a,'teacher:t1:1');assert.equal(idemMismatch.status,409);assert.equal(idemMismatch.body.error,'SYNC_IDEMPOTENCY_MISMATCH');
  const p1=await request('/api/v1/sync/push',policy(workspaceA,'p1'),sessions.a,'policy:p1:1');assert.equal(p1.status,200);

  const sameA=await request('/api/v1/sync/push',teacher(workspaceA,'shared',1,'A'),sessions.a,'same-key');assert.equal(sameA.status,200);
  const sameB=await request('/api/v1/sync/push',teacher(workspaceB,'shared',1,'B'),sessions.b,'same-key');assert.equal(sameB.status,200);assert.notEqual(sameA.body.serverVersion,sameB.body.serverVersion);
  const crossPull=await request('/api/v1/sync/pull',{workspace:workspaceB,cursor:0,limit:10},sessions.a,'cross-pull');assert.equal(crossPull.status,403);assert.equal(crossPull.body.error,'MEMBERSHIP_REQUIRED');
  const crossPush=await request('/api/v1/sync/push',teacher(workspaceB,'forbidden'),sessions.a,'cross-push');assert.equal(crossPush.status,403);assert.equal(crossPush.body.error,'MEMBERSHIP_REQUIRED');

  const page1=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:2},sessions.a,'page1');assert.equal(page1.status,200);assert.equal(page1.body.changes.length,2);assert.equal(page1.body.hasMore,true);assert.ok(page1.body.changes[1].sequence>page1.body.changes[0].sequence);
  const page2=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:page1.body.nextCursor,limit:10},sessions.a,'page2');assert.equal(page2.status,200);assert.ok(page2.body.changes.every((x:any)=>x.workspace===workspaceA));assert.ok(page2.body.changes[0].sequence>page1.body.changes[1].sequence);

  const t2=await request('/api/v1/sync/push',teacher(workspaceA,'t1',2),sessions.a,'teacher:t1:2');assert.equal(t2.status,200);
  const stale=await request('/api/v1/sync/push',teacher(workspaceA,'t1',1),sessions.a,'teacher:t1:stale');assert.equal(stale.status,409);assert.equal(stale.body.error,'SYNC_VERSION_CONFLICT');
  const sameVersionConflict=await request('/api/v1/sync/push',teacher(workspaceA,'t1',2,'Different'),sessions.a,'teacher:t1:2-other');assert.equal(sameVersionConflict.status,409);assert.equal(sameVersionConflict.body.error,'SYNC_CONFLICT');

  await stop(child);child=start();await waitReady();
  const persistedPull=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:100},sessions.a,'restart-pull');assert.equal(persistedPull.status,200);assert.ok(persistedPull.body.changes.some((x:any)=>x.entityType==='Teacher'));assert.ok(persistedPull.body.changes.some((x:any)=>x.entityType==='AttendancePolicy'));

  await stop(child);const persisted=JSON.parse(await readFile(store,'utf8')) as any;const seq=Number(persisted.sequence)+1;
  const poisoned:any={workspace:workspaceA,entityType:'Teacher',entityId:'poisoned-teacher',operation:'UPSERT',entityVersion:1,updatedAt:2001,payload:teacher(workspaceA,'poisoned-teacher').payload,tombstone:false,serverVersion:seq,sequence:seq,serverUpdatedAt:Date.now()};delete poisoned.payload.normalizedName;persisted.sequence=seq;persisted.mutations.push(poisoned);await writeFile(store,JSON.stringify(persisted),'utf8');
  child=start();await waitReady();const poisonPull=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:seq-1,limit:10},sessions.a,'poison-pull');assert.equal(poisonPull.status,409);assert.equal(poisonPull.body.error,'SYNC_STORED_MUTATION_INVALID');assert.equal(poisonPull.body.entityId,'poisoned-teacher');assert.equal(poisonPull.body.sequence,seq);
 }finally{await stop(child);await rm(store,{force:true})}
});
