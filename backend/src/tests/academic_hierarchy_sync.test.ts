import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile,rm,writeFile} from 'node:fs/promises';

const port=18139,base=`http://127.0.0.1:${port}`,store='/tmp/hammam-sync-hierarchy-test.json',workspaceA='workspace-a',workspaceB='workspace-b';
const deploymentToken='sync-bootstrap-token',tokenHash=createHash('sha256').update(deploymentToken).digest('hex');
const university=(id='u1',version=1,workspace=workspaceA)=>({workspace,entityType:'University',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:1000+version,payload:{id,name:'University',archivedAt:null,updatedAt:1000+version,version}});
const year=(id='y1',version=1,workspace=workspaceA)=>({workspace,entityType:'AcademicYear',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:2000+version,payload:{id,name:'2026/27',startDate:'2026-09-01',endDate:'2027-06-30',isActive:true,updatedAt:2000+version,version}});
const faculty=(id='f1',universityId='u1',version=1,workspace=workspaceA)=>({workspace,entityType:'Faculty',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:3000+version,payload:{id,universityId,name:'Medicine',archivedAt:null,updatedAt:3000+version,version}});
const department=(id='d1',facultyId='f1',version=1,workspace=workspaceA)=>({workspace,entityType:'Department',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:4000+version,payload:{id,facultyId,name:'Medicine',archivedAt:null,updatedAt:4000+version,version}});
const level=(id='l1',departmentId='d1',version=1,workspace=workspaceA)=>({workspace,entityType:'Level',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:5000+version,payload:{id,departmentId,name:'Level 1',orderIndex:1,archivedAt:null,updatedAt:5000+version,version}});
const semester=(id='sem1',academicYearId='y1',version=1,workspace=workspaceA)=>({workspace,entityType:'Semester',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:6000+version,payload:{id,name:'Semester 1',academicYearId,startDate:'2026-09-01',endDate:'2027-01-31',status:'ACTIVE',createdAt:1000,updatedAt:6000+version,version}});
const batch=(id='b1',levelId='l1',academicYearId='y1',version=1,workspace=workspaceA)=>({workspace,entityType:'Batch',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:7000+version,payload:{id,levelId,academicYearId,name:'Batch A',archivedAt:null,updatedAt:7000+version,version}});
const section=(id='s1',batchId='b1',version=1,workspace=workspaceA)=>({workspace,entityType:'Section',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:8000+version,payload:{id,batchId,name:'Section A',archivedAt:null,updatedAt:8000+version,version}});
const group=(id='g1',sectionId='s1',version=1,workspace=workspaceA)=>({workspace,entityType:'Group',entityId:id,operation:'UPSERT',entityVersion:version,updatedAt:9000+version,payload:{id,sectionId,name:'Group A',archivedAt:null,updatedAt:9000+version,version}});
const clone=<T>(v:T):T=>JSON.parse(JSON.stringify(v));
const all=():any[]=>[university(),year(),faculty(),department(),level(),semester(),batch(),section(),group()];
async function waitReady(){for(let i=0;i<60;i++){try{if((await fetch(`${base}/health`)).ok)return}catch{}await new Promise(r=>setTimeout(r,50))}throw new Error('server not ready')}
function start(){return spawn(process.execPath,['dist/server.js'],{env:{...process.env,PORT:String(port),CLOUD_SYNC_ENABLED:'true',SYNC_STORE_PATH:store,APP_ENV:'development',AUTH_TOKEN_HASH:tokenHash,SYNC_WORKSPACE_ID:workspaceA,SESSION_TTL_SECONDS:'3600'},stdio:'ignore'})}
async function request(path:string,value:any,auth:string|null=null,key='k'){const headers:Record<string,string>={'content-type':'application/json','idempotency-key':key};if(auth)headers.authorization=`Bearer ${auth}`;const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(value)});return{status:r.status,body:await r.json() as any}}
async function stop(c:ReturnType<typeof start>){if(!c.killed)c.kill();await new Promise(r=>setTimeout(r,120))}
async function sessions(){
 const a=await request('/api/v1/auth/bootstrap',{displayName:'A',workspaceId:workspaceA},deploymentToken,'boot-a');assert.equal(a.status,201);
 const sa=await request('/api/v1/auth/session',{accountId:a.body.accountId},a.body.accountCredential,'session-a');assert.equal(sa.status,201);
 const b=await request('/api/v1/auth/provision',{displayName:'B',workspaceId:workspaceB},deploymentToken,'boot-b');assert.equal(b.status,201);
 const sb=await request('/api/v1/auth/session',{accountId:b.body.accountId},b.body.accountCredential,'session-b');assert.equal(sb.status,201);
 return{a:String(sa.body.sessionToken),b:String(sb.body.sessionToken)};
}

function version2(v:any){const x=clone(v);x.entityVersion=2;x.updatedAt=Number(x.updatedAt)+1;x.payload.version=2;x.payload.updatedAt=Number(x.payload.updatedAt)+1;return x}

test('5F academic hierarchy sync is strict, persistent, workspace isolated and parent closed',async()=>{
 await rm(store,{force:true});let child=start();await waitReady();
 try{
  const auth=await sessions();

  // Parent references are enforced before sequence/idempotency persistence.
  const pre=JSON.parse(await readFile(store,'utf8')) as any;const preSeq=Number(pre.sequence);
  const missingCases=[faculty('bad-f','missing-u'),department('bad-d','missing-f'),level('bad-l','missing-d'),semester('bad-sem','missing-y'),batch('bad-b','missing-l','missing-y'),section('bad-s','missing-b'),group('bad-g','missing-s')];
  for(const [i,v] of missingCases.entries()){
   const r=await request('/api/v1/sync/push',v,auth.a,`missing-parent-${i}`);assert.equal(r.status,409);assert.equal(r.body.error,'SYNC_PARENT_MISSING');
  }
  const afterMissing=JSON.parse(await readFile(store,'utf8')) as any;assert.equal(afterMissing.sequence,preSeq);assert.equal(afterMissing.mutations.length,0);assert.equal(Object.keys(afterMissing.idempotency).some((k:string)=>k.includes('missing-parent-')),false);

  // Seed the complete topological closure and verify exact replay idempotency for every type.
  const firstResponses:any[]=[];
  for(const [i,v] of all().entries()){
   const key=`hierarchy:${v.entityType}:${v.entityId}:1`;
   const r=await request('/api/v1/sync/push',v,auth.a,key);assert.equal(r.status,200,`${v.entityType} valid push`);assert.equal(r.body.persisted,true);firstResponses.push(r.body);
   const replay=await request('/api/v1/sync/push',v,auth.a,key);assert.equal(replay.status,200);assert.equal(replay.body.serverVersion,r.body.serverVersion,`${v.entityType} replay`);
   assert.equal(i+1,r.body.serverVersion);
  }

  // Strict validation matrix: missing/extra/id/version/timestamp for every hierarchy entity.
  for(const [i,baseValue] of all().entries()){
   const cases:any[]=[];
   const missing=clone(baseValue);delete missing.payload.name;cases.push(missing);
   const extra=clone(baseValue);extra.payload.unexpected='x';cases.push(extra);
   const badId=clone(baseValue);badId.payload.id=`wrong-${i}`;cases.push(badId);
   const badVersion=clone(baseValue);badVersion.payload.version=99;cases.push(badVersion);
   const badTime=clone(baseValue);badTime.payload.updatedAt=0;cases.push(badTime);
   for(const [j,v] of cases.entries()){
    const r=await request('/api/v1/sync/push',v,auth.a,`invalid-${i}-${j}`);assert.equal(r.status,400,`${baseValue.entityType} invalid ${j}`);assert.equal(r.body.error,'SYNC_INVALID_PAYLOAD');
   }
  }
  const blankId:any=university('blank-id');blankId.entityId='';blankId.payload.id='';assert.equal((await request('/api/v1/sync/push',blankId,auth.a,'blank-id')).status,400);
  const badArchive:any=university('bad-archive');badArchive.payload.archivedAt=0;assert.equal((await request('/api/v1/sync/push',badArchive,auth.a,'bad-archive')).status,400);
  const badYear:any=year('bad-date');badYear.payload.startDate='2026-99-01';assert.equal((await request('/api/v1/sync/push',badYear,auth.a,'bad-date')).status,400);
  const reversedYear:any=year('reversed-year');reversedYear.payload.startDate='2028-01-01';assert.equal((await request('/api/v1/sync/push',reversedYear,auth.a,'reversed-year')).status,400);
  const badStatus:any=semester('bad-status');badStatus.payload.status='UNKNOWN';assert.equal((await request('/api/v1/sync/push',badStatus,auth.a,'bad-status')).status,400);
  const outside:any=semester('outside-year');outside.payload.endDate='2028-01-01';const outsideResult=await request('/api/v1/sync/push',outside,auth.a,'bad-range');assert.equal(outsideResult.status,409);assert.equal(outsideResult.body.error,'SYNC_PARENT_RANGE_INVALID');

  // Same idempotency key cannot mean different request in one workspace.
  const idemBase=university('idem-u');const idem1=await request('/api/v1/sync/push',idemBase,auth.a,'same-workspace-key');assert.equal(idem1.status,200);
  const idemChanged=clone(idemBase);idemChanged.payload.name='Changed';const idemMismatch=await request('/api/v1/sync/push',idemChanged,auth.a,'same-workspace-key');assert.equal(idemMismatch.status,409);assert.equal(idemMismatch.body.error,'SYNC_IDEMPOTENCY_MISMATCH');

  // The same idempotency key is isolated across workspaces, and a parent in B cannot satisfy a child in A.
  const sharedA=await request('/api/v1/sync/push',university('shared-root',1,workspaceA),auth.a,'cross-workspace-key');assert.equal(sharedA.status,200);
  const sharedB=await request('/api/v1/sync/push',university('shared-root',1,workspaceB),auth.b,'cross-workspace-key');assert.equal(sharedB.status,200);assert.notEqual(sharedA.body.serverVersion,sharedB.body.serverVersion);
  const crossParentRoot=await request('/api/v1/sync/push',university('cross-only-b',1,workspaceB),auth.b,'cross-root');assert.equal(crossParentRoot.status,200);
  const beforeCross=JSON.parse(await readFile(store,'utf8')) as any;const cross=await request('/api/v1/sync/push',faculty('cross-child','cross-only-b',1,workspaceA),auth.a,'cross-parent');assert.equal(cross.status,409);assert.equal(cross.body.error,'SYNC_PARENT_WORKSPACE_MISMATCH');
  const afterCross=JSON.parse(await readFile(store,'utf8')) as any;assert.equal(afterCross.sequence,beforeCross.sequence);assert.equal(Object.keys(afterCross.idempotency).some((k:string)=>k.includes('cross-parent')),false);

  // Membership still gates workspace access regardless of request body.
  const forbiddenPull=await request('/api/v1/sync/pull',{workspace:workspaceB,cursor:0,limit:10},auth.a,'forbidden-pull');assert.equal(forbiddenPull.status,403);assert.equal(forbiddenPull.body.error,'MEMBERSHIP_REQUIRED');
  const forbiddenPush=await request('/api/v1/sync/push',university('forbidden',1,workspaceB),auth.a,'forbidden-push');assert.equal(forbiddenPush.status,403);assert.equal(forbiddenPush.body.error,'MEMBERSHIP_REQUIRED');

  // Explicit conflict semantics for every hierarchy type: v2 accepted, stale rejected, same-v2 different payload conflicts.
  for(const [i,v1] of all().entries()){
   const v2=version2(v1);const r2=await request('/api/v1/sync/push',v2,auth.a,`v2-${i}`);assert.equal(r2.status,200,`${v1.entityType} v2`);
   const stale=await request('/api/v1/sync/push',v1,auth.a,`stale-${i}`);assert.equal(stale.status,409);assert.equal(stale.body.error,'SYNC_VERSION_CONFLICT');
   const diff=clone(v2);diff.payload.name=`Different ${i}`;const conflict=await request('/api/v1/sync/push',diff,auth.a,`conflict-${i}`);assert.equal(conflict.status,409);assert.equal(conflict.body.error,'SYNC_CONFLICT');
  }

  // Unified stream pagination preserves monotonic sequence and contains the full parent closure.
  const page1=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:4},auth.a,'page1');assert.equal(page1.status,200);assert.equal(page1.body.changes.length,4);assert.equal(page1.body.hasMore,true);
  const page2=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:page1.body.nextCursor,limit:100},auth.a,'page2');assert.equal(page2.status,200);assert.ok(page2.body.changes.every((x:any)=>x.workspace===workspaceA));assert.ok(page2.body.changes[0].sequence>page1.body.changes.at(-1).sequence);
  const types=new Set([...page1.body.changes,...page2.body.changes].map((x:any)=>x.entityType));for(const t of ['University','AcademicYear','Faculty','Department','Level','Semester','Batch','Section','Group'])assert.equal(types.has(t),true,t);

  // Account/session, entities and mutations survive backend restart.
  await stop(child);child=start();await waitReady();
  const persistedPull=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:0,limit:100},auth.a,'restart-pull');assert.equal(persistedPull.status,200);assert.ok(persistedPull.body.changes.some((x:any)=>x.entityType==='Group'));

  // A malformed stored hierarchy mutation poisons the stream and cannot be skipped.
  await stop(child);const persisted=JSON.parse(await readFile(store,'utf8')) as any;const seq=Number(persisted.sequence)+1;
  const poisoned:any={workspace:workspaceA,entityType:'Group',entityId:'poisoned-group',operation:'UPSERT',entityVersion:1,updatedAt:9001,payload:group('poisoned-group','s1').payload,tombstone:false,serverVersion:seq,sequence:seq,serverUpdatedAt:Date.now()};delete poisoned.payload.sectionId;persisted.sequence=seq;persisted.mutations.push(poisoned);await writeFile(store,JSON.stringify(persisted),'utf8');
  child=start();await waitReady();const poisonPull=await request('/api/v1/sync/pull',{workspace:workspaceA,cursor:seq-1,limit:10},auth.a,'poison-pull');assert.equal(poisonPull.status,409);assert.equal(poisonPull.body.error,'SYNC_STORED_MUTATION_INVALID');assert.equal(poisonPull.body.entityId,'poisoned-group');assert.equal(poisonPull.body.sequence,seq);
 }finally{await stop(child);await rm(store,{force:true})}
});
