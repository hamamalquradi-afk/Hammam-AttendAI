import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile,rm,writeFile} from 'node:fs/promises';

const port=18119,base=`http://127.0.0.1:${port}`,store='/tmp/hammam-sync-node-test.json',workspaceA='workspace-a',workspaceB='workspace-b';
const deploymentToken='sync-bootstrap-token',tokenHash=createHash('sha256').update(deploymentToken).digest('hex');
const appeal=(workspace:string,id:string,version:number,description=`appeal-${id}`)=>({workspace,entityType:'AttendanceAppeal',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:1000+version,payload:{id,studentId:'s1',attendanceRecordId:'r1',lectureId:'l1',subjectId:'sub1',reasonType:'OTHER',description,attachmentRemoteUrl:null,status:'PENDING',submittedAt:900,updatedAt:1000+version,reviewedBy:null,reviewedAt:null,decisionNote:null,version}});
const clone=<T>(v:T):T=>JSON.parse(JSON.stringify(v));
async function waitReady(){for(let i=0;i<60;i++){try{if((await fetch(`${base}/health`)).ok)return}catch{}await new Promise(r=>setTimeout(r,50))}throw new Error('server not ready')}
function start(){return spawn(process.execPath,['dist/server.js'],{env:{...process.env,PORT:String(port),CLOUD_SYNC_ENABLED:'true',SYNC_STORE_PATH:store,APP_ENV:'development',AUTH_TOKEN_HASH:tokenHash,SYNC_WORKSPACE_ID:workspaceA,SESSION_TTL_SECONDS:'3600'},stdio:'ignore'})}
async function request(path:string,value:any,auth:string|null=null,key='test-key'){const headers:Record<string,string>={'content-type':'application/json','idempotency-key':key};if(auth!==null)headers.authorization=`Bearer ${auth}`;const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(value)});return{status:r.status,body:await r.json() as any}}
async function stop(child:ReturnType<typeof start>){child.kill();await new Promise(r=>setTimeout(r,150))}

async function seedLegacyAppealParents(workspaces:string[]){
 const persisted=JSON.parse(await readFile(store,'utf8')) as any;
 for(const workspace of workspaces){
  const mk=(entityType:string,entityId:string,payload:any)=>({workspace,entityType,entityId,operation:'UPSERT',entityVersion:1,updatedAt:1,payload,tombstone:false,serverVersion:1,sequence:1,serverUpdatedAt:1});
  persisted.entities[`${workspace}|Student|s1`]=mk('Student','s1',{id:'s1',groupId:null});
  persisted.entities[`${workspace}|Subject|sub1`]=mk('Subject','sub1',{id:'sub1'});
  persisted.entities[`${workspace}|Lecture|l1`]=mk('Lecture','l1',{id:'l1',subjectId:'sub1',groupId:'g1'});
  persisted.entities[`${workspace}|AttendanceRecord|r1`]=mk('AttendanceRecord','r1',{id:'r1',studentId:'s1',lectureId:'l1'});
 }
 await writeFile(store,JSON.stringify(persisted),'utf8');
}
async function provision(){
 const boot=await request('/api/v1/auth/bootstrap',{displayName:'A',workspaceId:workspaceA},deploymentToken,'auth-bootstrap');assert.equal(boot.status,201);
 const acctA=String(boot.body.accountId),credA=String(boot.body.accountCredential);const sA=await request('/api/v1/auth/session',{accountId:acctA},credA,'auth-session-a');assert.equal(sA.status,201);
 const b=await request('/api/v1/auth/provision',{displayName:'B',workspaceId:workspaceB},deploymentToken,'auth-provision-b');assert.equal(b.status,201);
 const acctB=String(b.body.accountId),credB=String(b.body.accountCredential);const sB=await request('/api/v1/auth/session',{accountId:acctB},credB,'auth-session-b');assert.equal(sB.status,201);
 return{sessionA:String(sA.body.sessionToken),sessionB:String(sB.body.sessionToken)};
}

test('sync keeps 5B/5B.1 protections while enforcing 5D sessions, memberships and idempotency isolation',async()=>{
 await rm(store,{force:true});let child=start();await waitReady();
 try{
  const {sessionA,sessionB}=await provision();
  // 5G now requires Appeal parent closure. Seed legacy current-state parents without mutation-log entries
  // so this regression test can keep its original 5B sequence/idempotency assertions unchanged.
  await seedLegacyAppealParents([workspaceA,workspaceB]);

  const noSession=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:10},null,'pull:no-session');assert.equal(noSession.status,401);assert.equal(noSession.body.error,'AUTH_REQUIRED');
  const deploymentBypass=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:10},deploymentToken,'pull:deployment-token');assert.equal(deploymentBypass.status,401);assert.equal(deploymentBypass.body.error,'AUTH_INVALID');
  const crossPush=await request('/api/v1/sync/push',appeal(workspaceB,'cross-a-to-b',1),sessionA,'reject:cross-push');assert.equal(crossPush.status,403);assert.equal(crossPush.body.error,'MEMBERSHIP_REQUIRED');
  const crossPull=await request('/api/v1/sync/pull',{workspace:workspaceB,cursor:0,limit:10},sessionA,'reject:cross-pull');assert.equal(crossPull.status,403);assert.equal(crossPull.body.error,'MEMBERSHIP_REQUIRED');
  const reverseCross=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:10},sessionB,'reject:reverse-cross');assert.equal(reverseCross.status,403);assert.equal(reverseCross.body.error,'MEMBERSHIP_REQUIRED');
  const emptyAfterAuthRejects=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:100},sessionA,'pull:after-auth-rejects');assert.equal(emptyAfterAuthRejects.status,200);assert.equal(emptyAfterAuthRejects.body.nextCursor,0);assert.deepEqual(emptyAfterAuthRejects.body.changes,[]);
  const persistedAfterRejects=JSON.parse(await readFile(store,'utf8')) as any;assert.equal(persistedAfterRejects.sequence,0);assert.equal(persistedAfterRejects.mutations.length,0);assert.equal(Object.keys(persistedAfterRejects.idempotency).some((k:string)=>k.includes('reject:')),false);

  const invalidCases:[string,(v:any)=>void][]=[
   ['missing-student',v=>{delete v.payload.studentId}],['missing-record',v=>{delete v.payload.attendanceRecordId}],['invalid-status',v=>{v.payload.status='NOT_A_STATUS'}],
   ['invalid-reason',v=>{v.payload.reasonType='   '}],['invalid-version',v=>{v.payload.version=2}],['invalid-submitted-at',v=>{v.payload.submittedAt=0}],['invalid-updated-at',v=>{v.payload.updatedAt=0}],
  ];
  for(const [name,mutate] of invalidCases){const value=clone(appeal(workspaceA,`bad-${name}`,1));mutate(value);const r=await request('/api/v1/sync/push',value,sessionA,`invalid:${name}`);assert.equal(r.status,400,name);assert.equal(r.body.error,'SYNC_INVALID_PAYLOAD',name)}
  const emptyAfterInvalid=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:100},sessionA,'pull:after-invalid');assert.equal(emptyAfterInvalid.status,200);assert.equal(emptyAfterInvalid.body.nextCursor,0);assert.deepEqual(emptyAfterInvalid.body.changes,[]);

  const fixable=clone(appeal(workspaceA,'fixable',1));delete (fixable.payload as any).studentId;
  const rejected=await request('/api/v1/sync/push',fixable,sessionA,'appeal:fixable:1');assert.equal(rejected.status,400);
  const fixed=await request('/api/v1/sync/push',appeal(workspaceA,'fixable',1),sessionA,'appeal:fixable:1');assert.equal(fixed.status,200);assert.equal(fixed.body.serverVersion,1);

  const first=await request('/api/v1/sync/push',appeal(workspaceA,'a1',1),sessionA,'appeal:a1:1');assert.equal(first.status,200);assert.equal(first.body.persisted,true);const firstVersion=first.body.serverVersion;
  const replay=await request('/api/v1/sync/push',appeal(workspaceA,'a1',1),sessionA,'appeal:a1:1');assert.equal(replay.body.serverVersion,firstVersion);
  const mismatch=await request('/api/v1/sync/push',appeal(workspaceA,'a1',1,'different'),sessionA,'appeal:a1:1');assert.equal(mismatch.status,409);assert.equal(mismatch.body.error,'SYNC_IDEMPOTENCY_MISMATCH');

  const sameKeyA=await request('/api/v1/sync/push',appeal(workspaceA,'same-id',1,'workspace-a-value'),sessionA,'shared-idempotency-key');assert.equal(sameKeyA.status,200);
  const sameKeyB=await request('/api/v1/sync/push',appeal(workspaceB,'same-id',1,'workspace-b-value'),sessionB,'shared-idempotency-key');assert.equal(sameKeyB.status,200);assert.notEqual(sameKeyA.body.serverVersion,sameKeyB.body.serverVersion);
  const pullAIsolation=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:100},sessionA,'pull:isolation-a');assert.ok(pullAIsolation.body.changes.some((x:any)=>x.entityId==='same-id'&&x.payload.description==='workspace-a-value'));assert.equal(pullAIsolation.body.changes.some((x:any)=>x.payload.description==='workspace-b-value'),false);
  const pullBIsolation=await request('/api/v1/sync/pull',{workspace:workspaceB,cursor:0,limit:100},sessionB,'pull:isolation-b');assert.equal(pullBIsolation.body.changes.length,1);assert.equal(pullBIsolation.body.changes[0].payload.description,'workspace-b-value');

  await request('/api/v1/sync/push',appeal(workspaceA,'a2',1),sessionA,'appeal:a2:1');await request('/api/v1/sync/push',appeal(workspaceA,'a3',1),sessionA,'appeal:a3:1');
  const page1=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:2},sessionA,'pull:0');assert.equal(page1.body.changes.length,2);assert.equal(page1.body.hasMore,true);
  const page2=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:page1.body.nextCursor,limit:2},sessionA,`pull:${page1.body.nextCursor}`);assert.equal(page2.body.changes.length,2);assert.ok(page2.body.changes[0].sequence>page1.body.changes[1].sequence);
  const v2=await request('/api/v1/sync/push',appeal(workspaceA,'a1',2),sessionA,'appeal:a1:2');assert.equal(v2.status,200);
  const stale=await request('/api/v1/sync/push',appeal(workspaceA,'a1',1),sessionA,'appeal:a1:stale');assert.equal(stale.status,409);assert.equal(stale.body.error,'SYNC_VERSION_CONFLICT');

  await stop(child);child=start();await waitReady();
  const afterRestart=await request('/api/v1/sync/push',appeal(workspaceA,'a1',2),sessionA,'appeal:a1:2');assert.equal(afterRestart.status,200);assert.equal(afterRestart.body.serverVersion,v2.body.serverVersion);
  const pullAfterRestart=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:100},sessionA,'pull:restart');assert.ok(pullAfterRestart.body.changes.length>=5);

  await stop(child);const persisted=JSON.parse(await readFile(store,'utf8')) as any;const poisonSequence=Number(persisted.sequence)+1;
  const poison={workspace:workspaceA,entityType:'AttendanceAppeal',entityId:'poisoned',operation:'UPSERT',entityVersion:1,updatedAt:1001,payload:appeal(workspaceA,'poisoned',1).payload,tombstone:false,serverVersion:poisonSequence,sequence:poisonSequence,serverUpdatedAt:Date.now()};
  poison.payload.status='NOT_A_STATUS';persisted.sequence=poisonSequence;persisted.mutations.push(poison);await writeFile(store,JSON.stringify(persisted),'utf8');
  child=start();await waitReady();const poisonedPull=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:poisonSequence-1,limit:10},sessionA,'pull:poison');assert.equal(poisonedPull.status,409);assert.equal(poisonedPull.body.error,'SYNC_STORED_MUTATION_INVALID');assert.equal(poisonedPull.body.sequence,poisonSequence);assert.equal(poisonedPull.body.entityId,'poisoned');
 }finally{await stop(child);await rm(store,{force:true})}
});
