import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile,rm,writeFile} from 'node:fs/promises';

const port=18149,base=`http://127.0.0.1:${port}`,store='/tmp/hammam-sync-5g-test.json',A='workspace-a',B='workspace-b';
const deploymentToken='sync-bootstrap-token',tokenHash=createHash('sha256').update(deploymentToken).digest('hex');
const clone=<T>(v:T):T=>JSON.parse(JSON.stringify(v));
const env=(workspace:string,entityType:string,id:string,version:number,updatedAt:number,payload:any)=>({workspace,entityType,entityId:id,operation:'UPSERT',entityVersion:version,updatedAt,payload:{...payload,id,updatedAt,version}});
const teacher=(w:string,id='t1',v=1)=>env(w,'Teacher',id,v,2000+v,{fullName:'Dr One',normalizedName:'dr one',phone:null,whatsapp:null,email:null,preferredNotificationChannel:null,notificationsEnabled:true,createdAt:1000,archivedAt:null});
const policy=(w:string,id='p1',v=1)=>env(w,'AttendancePolicy',id,v,3000+v,{name:'Global Policy',scopeType:'GLOBAL',scopeId:null,fullAttendanceThreshold:.8,partialAttendanceThreshold:.5,lateAfterMinutes:10,earlyLeaveThresholdMinutes:5,absenceThreshold:.2,temporaryMissingGraceSeconds:30,minimumPresenceVerificationSeconds:60,confidenceThreshold:.7,createdAt:1000});
const university=(w:string,id='u1',v=1)=>env(w,'University',id,v,4000+v,{name:'University',archivedAt:null});
const year=(w:string,id='y1',v=1)=>env(w,'AcademicYear',id,v,5000+v,{name:'2026/27',startDate:'2026-09-01',endDate:'2027-06-30',isActive:true});
const faculty=(w:string,id='f1',u='u1',v=1)=>env(w,'Faculty',id,v,6000+v,{universityId:u,name:'Medicine',archivedAt:null});
const department=(w:string,id='d1',f='f1',v=1)=>env(w,'Department',id,v,7000+v,{facultyId:f,name:'Medicine',archivedAt:null});
const level=(w:string,id='lvl1',d='d1',v=1)=>env(w,'Level',id,v,8000+v,{departmentId:d,name:'Level 1',orderIndex:1,archivedAt:null});
const semester=(w:string,id='sem1',y='y1',v=1)=>env(w,'Semester',id,v,9000+v,{name:'Semester 1',academicYearId:y,startDate:'2026-09-01',endDate:'2027-01-31',status:'ACTIVE',createdAt:1000});
const batch=(w:string,id='b1',l='lvl1',y='y1',v=1)=>env(w,'Batch',id,v,10000+v,{levelId:l,academicYearId:y,name:'Batch A',archivedAt:null});
const section=(w:string,id='sec1',b='b1',v=1)=>env(w,'Section',id,v,11000+v,{batchId:b,name:'Section A',archivedAt:null});
const group=(w:string,id='g1',s='sec1',v=1)=>env(w,'Group',id,v,12000+v,{sectionId:s,name:'Group A',archivedAt:null});
const student=(w:string,id='stu1',v=1,number:string|null='2026001',refs:any={levelId:'lvl1',batchId:'b1',sectionId:'sec1',groupId:'g1'})=>env(w,'Student',id,v,13000+v,{universityNumber:number,fullName:'Student One',normalizedName:'student one',phoneNumber:null,whatsappNumber:null,...refs,status:'ACTIVE',createdAt:1000,archivedAt:null});
const subject=(w:string,id='sub1',v=1,refs:any={teacherId:'t1',levelId:'lvl1',semesterId:'sem1',groupId:'g1',attendancePolicyId:'p1'})=>env(w,'Subject',id,v,14000+v,{code:'MED101',name:'Medicine 101',...refs,status:'ACTIVE',archivedAt:null});
const lecture=(w:string,id='lec1',v=1,refs:any={subjectId:'sub1',teacherId:'t1',semesterId:'sem1',groupId:'g1'})=>env(w,'Lecture',id,v,15000+v,{...refs,scheduledStart:20000,scheduledEnd:21000,actualStart:null,actualEnd:null,room:'R1',status:'SCHEDULED',attendancePolicySnapshotJson:'{}',createdAt:1000});
const record=(w:string,id='rec1',v=1,refs:any={lectureId:'lec1',studentId:'stu1'})=>env(w,'AttendanceRecord',id,v,16000+v,{...refs,firstSeenAt:20010,lastSeenAt:20800,verifiedPresenceSeconds:790,lectureDurationSeconds:1000,attendancePercentage:.79,lateMinutes:0,earlyLeaveMinutes:0,confidenceScore:.9,finalStatus:'PRESENT',approvalStatus:'APPROVED',source:'MANUAL',notes:null,createdAt:1000});
const appeal=(w:string,id='app1',v=1,refs:any={studentId:'stu1',attendanceRecordId:'rec1',lectureId:'lec1',subjectId:'sub1'})=>env(w,'AttendanceAppeal',id,v,17000+v,{...refs,reasonType:'OTHER',description:'Review',attachmentRemoteUrl:null,status:'PENDING',submittedAt:16000,reviewedBy:null,reviewedAt:null,decisionNote:null});

async function waitReady(){for(let i=0;i<60;i++){try{if((await fetch(`${base}/health`)).ok)return}catch{}await new Promise(r=>setTimeout(r,50))}throw new Error('server not ready')}
function start(){return spawn(process.execPath,['dist/server.js'],{env:{...process.env,PORT:String(port),CLOUD_SYNC_ENABLED:'true',SYNC_STORE_PATH:store,APP_ENV:'development',AUTH_TOKEN_HASH:tokenHash,SYNC_WORKSPACE_ID:A,SESSION_TTL_SECONDS:'3600'},stdio:'ignore'})}
async function request(path:string,value:any,auth:string|null=null,key='k'){const headers:Record<string,string>={'content-type':'application/json','idempotency-key':key};if(auth)headers.authorization=`Bearer ${auth}`;const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(value)});return{status:r.status,body:await r.json() as any}}
async function stop(c:ReturnType<typeof start>){if(!c.killed)c.kill();await new Promise(r=>setTimeout(r,120))}
async function sessions(){
 const a=await request('/api/v1/auth/bootstrap',{displayName:'A',workspaceId:A},deploymentToken,'boot-a');assert.equal(a.status,201);const sa=await request('/api/v1/auth/session',{accountId:a.body.accountId},a.body.accountCredential,'session-a');assert.equal(sa.status,201);
 const b=await request('/api/v1/auth/provision',{displayName:'B',workspaceId:B},deploymentToken,'boot-b');assert.equal(b.status,201);const sb=await request('/api/v1/auth/session',{accountId:b.body.accountId},b.body.accountCredential,'session-b');assert.equal(sb.status,201);
 return{a:String(sa.body.sessionToken),b:String(sb.body.sessionToken)};
}
async function push(r:any,auth:string,key=`${r.entityType}:${r.entityId}:${r.entityVersion}`){const x=await request('/api/v1/sync/push',r,auth,key);assert.equal(x.status,200,`${r.entityType}:${r.entityId}`);return x}
async function seedParents(w:string,auth:string,prefix=''){
 const ids={t:`${prefix}t1`,p:`${prefix}p1`,u:`${prefix}u1`,y:`${prefix}y1`,f:`${prefix}f1`,d:`${prefix}d1`,l:`${prefix}lvl1`,sem:`${prefix}sem1`,b:`${prefix}b1`,sec:`${prefix}sec1`,g:`${prefix}g1`};
 await push(teacher(w,ids.t),auth);await push(policy(w,ids.p),auth);await push(university(w,ids.u),auth);await push(year(w,ids.y),auth);await push(faculty(w,ids.f,ids.u),auth);await push(department(w,ids.d,ids.f),auth);await push(level(w,ids.l,ids.d),auth);await push(semester(w,ids.sem,ids.y),auth);await push(batch(w,ids.b,ids.l,ids.y),auth);await push(section(w,ids.sec,ids.b),auth);await push(group(w,ids.g,ids.sec),auth);return ids;
}

test('5G Student/Subject/Lecture/AttendanceRecord graph is strict, parent-closed, idempotent and workspace isolated',async()=>{
 await rm(store,{force:true});let child=start();await waitReady();
 try{
  const auth=await sessions();const ids=await seedParents(A,auth.a);
  const s=student(A,'stu1',1,'2026001',{levelId:ids.l,batchId:ids.b,sectionId:ids.sec,groupId:ids.g});
  const sub=subject(A,'sub1',1,{teacherId:ids.t,levelId:ids.l,semesterId:ids.sem,groupId:ids.g,attendancePolicyId:ids.p});
  const lec=lecture(A,'lec1',1,{subjectId:'sub1',teacherId:ids.t,semesterId:ids.sem,groupId:ids.g});
  const rec=record(A,'rec1',1,{lectureId:'lec1',studentId:'stu1'});

  // Batch A: strict Student validation and parent closure, including optional-null legacy imports.
  const nullRefs=student(A,'legacy-null-student',1,'2026002',{levelId:null,batchId:null,sectionId:null,groupId:null});assert.equal((await push(nullRefs,auth.a)).status,200);
  const studentBefore=JSON.parse(await readFile(store,'utf8')) as any;const seqBefore=Number(studentBefore.sequence);
  const studentMissing=clone(s) as any;delete studentMissing.payload.fullName;assert.equal((await request('/api/v1/sync/push',studentMissing,auth.a,'student-missing')).status,400);
  const studentExtra=clone(s) as any;studentExtra.payload.registeredDeviceId='must-not-sync';assert.equal((await request('/api/v1/sync/push',studentExtra,auth.a,'student-extra')).status,400);
  const studentBadId=clone(s) as any;studentBadId.payload.id='wrong';assert.equal((await request('/api/v1/sync/push',studentBadId,auth.a,'student-id')).status,400);
  const studentBadVer=clone(s) as any;studentBadVer.payload.version=2;assert.equal((await request('/api/v1/sync/push',studentBadVer,auth.a,'student-version')).status,400);
  const studentBadTime=clone(s) as any;studentBadTime.payload.updatedAt=0;assert.equal((await request('/api/v1/sync/push',studentBadTime,auth.a,'student-time')).status,400);
  const missingParent=student(A,'missing-parent-stu',1,'2026003',{levelId:'no-level',batchId:'no-batch',sectionId:'no-section',groupId:'no-group'});const mp=await request('/api/v1/sync/push',missingParent,auth.a,'student-parent');assert.equal(mp.status,409);assert.equal(mp.body.error,'SYNC_PARENT_MISSING');
  const afterInvalidStudent=JSON.parse(await readFile(store,'utf8')) as any;assert.equal(afterInvalidStudent.sequence,seqBefore);assert.equal(Object.keys(afterInvalidStudent.idempotency).some((k:string)=>k.includes('student-missing')),false);
  const s1=await push(s,auth.a,'student:stu1:1');const replay=await push(s,auth.a,'student:stu1:1');assert.equal(replay.body.serverVersion,s1.body.serverVersion);
  const duplicateNumber=student(A,'stu-duplicate',1,'2026001',{levelId:ids.l,batchId:ids.b,sectionId:ids.sec,groupId:ids.g});const dup=await request('/api/v1/sync/push',duplicateNumber,auth.a,'student-duplicate');assert.equal(dup.status,409);assert.equal(dup.body.error,'SYNC_UNIQUE_CONFLICT');

  // Batch B: Subject requires the synchronized graph and GLOBAL policy only.
  const beforeSubject=JSON.parse(await readFile(store,'utf8')) as any;const badSubject=clone(sub) as any;delete badSubject.payload.code;assert.equal((await request('/api/v1/sync/push',badSubject,auth.a,'subject-missing')).status,400);
  const extraSubject=clone(sub) as any;extraSubject.payload.unexpected='x';assert.equal((await request('/api/v1/sync/push',extraSubject,auth.a,'subject-extra')).status,400);
  const noTeacher=subject(A,'sub-no-teacher',1,{teacherId:'missing-teacher',levelId:ids.l,semesterId:ids.sem,groupId:ids.g,attendancePolicyId:ids.p});assert.equal((await request('/api/v1/sync/push',noTeacher,auth.a,'subject-parent')).body.error,'SYNC_PARENT_MISSING');
  const afterBadSubject=JSON.parse(await readFile(store,'utf8')) as any;assert.equal(afterBadSubject.sequence,beforeSubject.sequence);
  await push(sub,auth.a,'subject:sub1:1');

  // Batch C: no bridge entity is required; Lecture carries all server parents directly.
  // Batch D: Lecture strict contract and parent chain.
  const orphanLecture=lecture(A,'lec-orphan',1,{subjectId:'missing-subject',teacherId:ids.t,semesterId:ids.sem,groupId:ids.g});const ol=await request('/api/v1/sync/push',orphanLecture,auth.a,'lecture-orphan');assert.equal(ol.status,409);assert.equal(ol.body.error,'SYNC_PARENT_MISSING');
  const badLecture=clone(lec) as any;badLecture.payload.scheduledEnd=badLecture.payload.scheduledStart;assert.equal((await request('/api/v1/sync/push',badLecture,auth.a,'lecture-time')).status,400);
  await push(lec,auth.a,'lecture:lec1:1');

  // Batch E: AttendanceRecord requires Student + Lecture and unique (lecture,student).
  const orphanRecord=record(A,'rec-orphan',1,{lectureId:'missing-lecture',studentId:'stu1'});const orr=await request('/api/v1/sync/push',orphanRecord,auth.a,'record-orphan');assert.equal(orr.status,409);assert.equal(orr.body.error,'SYNC_PARENT_MISSING');
  const badRecord=clone(rec) as any;badRecord.payload.attendancePercentage=1.1;assert.equal((await request('/api/v1/sync/push',badRecord,auth.a,'record-range')).status,400);
  await push(rec,auth.a,'record:rec1:1');
  const duplicatePair=record(A,'rec-duplicate',1,{lectureId:'lec1',studentId:'stu1'});const dr=await request('/api/v1/sync/push',duplicatePair,auth.a,'record-duplicate');assert.equal(dr.status,409);assert.equal(dr.body.error,'SYNC_UNIQUE_CONFLICT');

  // Appeal closure now validates real parent consistency.
  await push(appeal(A,'app1'),auth.a,'appeal:app1:1');
  const wrongAppeal=appeal(A,'app-wrong',1,{studentId:'stu1',attendanceRecordId:'rec1',lectureId:'lec1',subjectId:'wrong-subject'});const wa=await request('/api/v1/sync/push',wrongAppeal,auth.a,'appeal-wrong');assert.equal(wa.status,409);assert.equal(wa.body.error,'SYNC_PARENT_MISSING');

  // Idempotency mismatch/conflict/version protection remains workspace scoped.
  const idem=student(A,'idem-student',1,'IDEM',{levelId:null,batchId:null,sectionId:null,groupId:null});await push(idem,auth.a,'shared-student-key');const idemChanged=clone(idem) as any;idemChanged.payload.fullName='Changed';const im=await request('/api/v1/sync/push',idemChanged,auth.a,'shared-student-key');assert.equal(im.status,409);assert.equal(im.body.error,'SYNC_IDEMPOTENCY_MISMATCH');
  const idemB=student(B,'idem-student-b',1,'IDEM-B',{levelId:null,batchId:null,sectionId:null,groupId:null});assert.equal((await request('/api/v1/sync/push',idemB,auth.b,'shared-student-key')).status,200);
  const s2=clone(s) as any;s2.entityVersion=2;s2.updatedAt++;s2.payload.version=2;s2.payload.updatedAt++;s2.payload.fullName='Student Updated';await push(s2,auth.a,'student:stu1:2');const stale=await request('/api/v1/sync/push',s,auth.a,'student:stale');assert.equal(stale.status,409);assert.equal(stale.body.error,'SYNC_VERSION_CONFLICT');const sameVer=clone(s2) as any;sameVer.payload.fullName='Other';const cf=await request('/api/v1/sync/push',sameVer,auth.a,'student:conflict');assert.equal(cf.status,409);assert.equal(cf.body.error,'SYNC_CONFLICT');

  // Cross-workspace reference cannot be satisfied by a parent existing only in B.
  const bIds=await seedParents(B,auth.b,'b-');
  const crossStudent=student(A,'cross-student',1,'CROSS',{levelId:bIds.l,batchId:bIds.b,sectionId:bIds.sec,groupId:bIds.g});const cs=await request('/api/v1/sync/push',crossStudent,auth.a,'cross-student');assert.equal(cs.status,409);assert.equal(cs.body.error,'SYNC_PARENT_WORKSPACE_MISMATCH');

  const pull=await request('/api/v1/sync/pull',{workspace:A,cursor:0,limit:100},auth.a,'pull-all');assert.equal(pull.status,200);for(const type of ['Student','Subject','Lecture','AttendanceRecord','AttendanceAppeal'])assert.ok(pull.body.changes.some((x:any)=>x.entityType===type),type);
  for(let i=1;i<pull.body.changes.length;i++)assert.ok(pull.body.changes[i].sequence>pull.body.changes[i-1].sequence);

  await stop(child);child=start();await waitReady();const restarted=await request('/api/v1/sync/pull',{workspace:A,cursor:0,limit:100},auth.a,'restart');assert.equal(restarted.status,200);assert.ok(restarted.body.changes.some((x:any)=>x.entityId==='rec1'));

  // Poison protection for every new 5G type.
  await stop(child);const baseline=JSON.parse(await readFile(store,'utf8')) as any;
  const poisonCases:[string,any,string][]=[['Student',s,'fullName'],['Subject',sub,'code'],['Lecture',lec,'subjectId'],['AttendanceRecord',rec,'studentId']];
  for(const [type,baseValue,field] of poisonCases){
   const raw=clone(baseline) as any;const seq=Number(raw.sequence)+1;const poison:any={...clone(baseValue),tombstone:false,serverVersion:seq,sequence:seq,serverUpdatedAt:Date.now()};delete poison.payload[field];raw.sequence=seq;raw.mutations.push(poison);await writeFile(store,JSON.stringify(raw),'utf8');child=start();await waitReady();const pr=await request('/api/v1/sync/pull',{workspace:A,cursor:seq-1,limit:10},auth.a,`poison-${type}`);assert.equal(pr.status,409,type);assert.equal(pr.body.error,'SYNC_STORED_MUTATION_INVALID',type);await stop(child);
  }
  await writeFile(store,JSON.stringify(baseline),'utf8');

  // Legacy Appeal created before its parents is re-anchored after parent backfill, not deleted.
  const legacyRaw=clone(baseline) as any;const oldSeq=Number(legacyRaw.sequence)+1;const legacy=appeal(B,'legacy-app',1,{studentId:'legacy-stu',attendanceRecordId:'legacy-rec',lectureId:'legacy-lec',subjectId:'legacy-sub'});const oldMutation:any={...legacy,tombstone:false,serverVersion:oldSeq,sequence:oldSeq,serverUpdatedAt:Date.now()};legacyRaw.sequence=oldSeq;legacyRaw.mutations.push(oldMutation);legacyRaw.entities[`${B}|AttendanceAppeal|legacy-app`]=oldMutation;await writeFile(store,JSON.stringify(legacyRaw),'utf8');child=start();await waitReady();
  const lIds=await seedParents(B,auth.b,'legacy-');
  await push(student(B,'legacy-stu',1,'LEGACY',{levelId:lIds.l,batchId:lIds.b,sectionId:lIds.sec,groupId:lIds.g}),auth.b,'legacy-student');
  await push(subject(B,'legacy-sub',1,{teacherId:lIds.t,levelId:lIds.l,semesterId:lIds.sem,groupId:lIds.g,attendancePolicyId:lIds.p}),auth.b,'legacy-subject');
  await push(lecture(B,'legacy-lec',1,{subjectId:'legacy-sub',teacherId:lIds.t,semesterId:lIds.sem,groupId:lIds.g}),auth.b,'legacy-lecture');
  await push(record(B,'legacy-rec',1,{lectureId:'legacy-lec',studentId:'legacy-stu'}),auth.b,'legacy-record');
  const legacyPull=await request('/api/v1/sync/pull',{workspace:B,cursor:0,limit:100},auth.b,'legacy-pull');assert.equal(legacyPull.status,200);const legacyAppeals=legacyPull.body.changes.filter((x:any)=>x.entityId==='legacy-app');assert.equal(legacyAppeals.length,1);assert.ok(legacyAppeals[0].sequence>oldSeq);const legacyRec=legacyPull.body.changes.find((x:any)=>x.entityId==='legacy-rec');assert.ok(legacyRec&&legacyRec.sequence<legacyAppeals[0].sequence);
 }finally{await stop(child);await rm(store,{force:true})}
});
