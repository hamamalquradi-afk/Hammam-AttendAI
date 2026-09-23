import http from 'node:http';
import {createHash,randomBytes,randomUUID,timingSafeEqual} from 'node:crypto';
import {mkdir,readFile,rename,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';

type Json=Record<string,unknown>;
const env=(k:string,d='')=>String(process.env[k]??d);
const port=Number(env('PORT','8080'));
const limits=new Map<string,{count:number;reset:number}>();
const idempotency=new Map<string,{status:number;body:Json;expires:number}>();

type SyncMutation={workspace:string;entityType:string;entityId:string;operation:string;entityVersion:number;updatedAt:number;payload:Json;tombstone:boolean;serverVersion:number;sequence:number;serverUpdatedAt:number};
type SyncIdempotency={requestHash:string;status:number;body:Json};
type AccountRecord={id:string;displayName:string;credentialHash:string;status:'ACTIVE'|'DISABLED';createdAt:number;updatedAt:number};
type WorkspaceRecord={id:string;displayName:string;status:'ACTIVE'|'DISABLED';createdAt:number;updatedAt:number};
type MembershipRecord={id:string;accountId:string;workspaceId:string;status:'ACTIVE'|'DISABLED';createdAt:number;updatedAt:number};
type SessionRecord={id:string;accountId:string;tokenHash:string;createdAt:number;expiresAt:number;revokedAt:number|null};
type SyncStore={
 sequence:number;
 mutations:SyncMutation[];
 entities:Record<string,SyncMutation>;
 idempotency:Record<string,SyncIdempotency>;
 accounts:Record<string,AccountRecord>;
 workspaces:Record<string,WorkspaceRecord>;
 memberships:Record<string,MembershipRecord>;
 sessions:Record<string,SessionRecord>;
};

const syncStorePath=resolve(env('SYNC_STORE_PATH','./data/sync-store.json'));
let syncWrite:Promise<void>=Promise.resolve();
function emptySyncStore():SyncStore{return{sequence:0,mutations:[],entities:{},idempotency:{},accounts:{},workspaces:{},memberships:{},sessions:{}}}
async function loadSyncStore():Promise<SyncStore>{
 try{
  const raw=await readFile(syncStorePath,'utf8');const x=JSON.parse(raw);
  return{
   sequence:Number(x.sequence||0),mutations:Array.isArray(x.mutations)?x.mutations:[],
   entities:x.entities&&typeof x.entities==='object'?x.entities:{},
   idempotency:x.idempotency&&typeof x.idempotency==='object'?x.idempotency:{},
   accounts:x.accounts&&typeof x.accounts==='object'?x.accounts:{},
   workspaces:x.workspaces&&typeof x.workspaces==='object'?x.workspaces:{},
   memberships:x.memberships&&typeof x.memberships==='object'?x.memberships:{},
   sessions:x.sessions&&typeof x.sessions==='object'?x.sessions:{},
  };
 }catch(e:any){if(e?.code==='ENOENT')return emptySyncStore();throw e}
}
async function saveSyncStore(store:SyncStore){await mkdir(dirname(syncStorePath),{recursive:true});const tmp=`${syncStorePath}.tmp`;await writeFile(tmp,JSON.stringify(store),'utf8');await rename(tmp,syncStorePath)}
async function mutateSyncStore<T>(fn:(s:SyncStore)=>Promise<T>|T):Promise<T>{let result!:T;let failure:any;syncWrite=syncWrite.then(async()=>{try{const store=await loadSyncStore();result=await fn(store);await saveSyncStore(store)}catch(e){failure=e}});await syncWrite;if(failure)throw failure;return result}

function syncEntityKey(workspace:string,entityType:string,entityId:string){return `${workspace}|${entityType}|${entityId}`}
function syncIdempotencyKey(workspace:string,key:string){return `${workspace}|${key}`}
function stableHash(v:unknown){return createHash('sha256').update(JSON.stringify(v)).digest('hex')}
function tokenHash(v:string){return createHash('sha256').update(v).digest('hex')}
function secureToken(){return randomBytes(32).toString('base64url')}
function newId(prefix:string){return `${prefix}_${randomUUID()}`}
function plainObject(v:any){return !!v&&typeof v==='object'&&!Array.isArray(v)}
function nonEmptyString(v:any){return typeof v==='string'&&v.trim().length>0}
function positiveSafeInteger(v:any){return Number.isSafeInteger(v)&&v>0}
function nullableString(v:any){return v===null||typeof v==='string'}
function nullableNonEmptyString(v:any){return v===null||nonEmptyString(v)}
function nullablePositiveInteger(v:any){return v===null||positiveSafeInteger(v)}
function nonNegativeSafeInteger(v:any){return Number.isSafeInteger(v)&&v>=0}
function finiteNumber(v:any){return typeof v==='number'&&Number.isFinite(v)}
function probability(v:any){return finiteNumber(v)&&v>=0&&v<=1}
function exactKeys(v:any,keys:string[]){if(!plainObject(v))return false;const actual=Object.keys(v);return actual.length===keys.length&&keys.every(k=>Object.prototype.hasOwnProperty.call(v,k))}
function validIsoDate(v:any){if(typeof v!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(v))return false;const [y,m,d]=v.split('-').map(Number);const dt=new Date(Date.UTC(y,m-1,d));return dt.getUTCFullYear()===y&&dt.getUTCMonth()===m-1&&dt.getUTCDate()===d}
const appealStatuses=new Set(['PENDING','ACCEPTED','REJECTED','CANCELLED']);
const studentStatuses=new Set(['ACTIVE','INACTIVE','GRADUATED','SUSPENDED','ARCHIVED']);
const subjectStatuses=new Set(['ACTIVE','ARCHIVED']);
const lectureStatuses=new Set(['SCHEDULED','READY','ACTIVE','COMPLETED','CANCELLED','NEEDS_REVIEW','FROZEN']);
const finalAttendanceStatuses=new Set(['PRESENT','LATE','PARTIAL','LEFT_EARLY','ABSENT','EXCUSED','MANUAL_REVIEW']);
const approvalStatuses=new Set(['DRAFT','PENDING','APPROVED','FROZEN']);
const presenceSources=new Set(['BLE','QR','NFC','MANUAL']);
const syncEntityTypes=new Set(['Teacher','AttendancePolicy','University','AcademicYear','Faculty','Department','Level','Semester','Batch','Section','Group','Student','Subject','Lecture','AttendanceRecord','AttendanceAppeal']);
const syncWorkspacePattern=/^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
function validWorkspaceId(v:any){return typeof v==='string'&&syncWorkspacePattern.test(v.trim())}
function configuredSyncWorkspace(){const v=env('SYNC_WORKSPACE_ID').trim();return validWorkspaceId(v)?v:null}
function validTeacherPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','fullName','normalizedName','phone','whatsapp','email','preferredNotificationChannel','notificationsEnabled','createdAt','updatedAt','archivedAt','version'];
 if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.fullName)&&nonEmptyString(payload.normalizedName)&&nullableString(payload.phone)&&nullableString(payload.whatsapp)&&nullableString(payload.email)&&nullableString(payload.preferredNotificationChannel)&&
  typeof payload.notificationsEnabled==='boolean'&&positiveSafeInteger(payload.createdAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.createdAt&&
  nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validAttendancePolicyPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','name','scopeType','scopeId','fullAttendanceThreshold','partialAttendanceThreshold','lateAfterMinutes','earlyLeaveThresholdMinutes','absenceThreshold','temporaryMissingGraceSeconds','minimumPresenceVerificationSeconds','confidenceThreshold','createdAt','updatedAt','version'];
 if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.name)&&payload.scopeType==='GLOBAL'&&payload.scopeId===null&&
  probability(payload.fullAttendanceThreshold)&&probability(payload.partialAttendanceThreshold)&&probability(payload.absenceThreshold)&&probability(payload.confidenceThreshold)&&
  payload.fullAttendanceThreshold>=payload.partialAttendanceThreshold&&payload.partialAttendanceThreshold>=payload.absenceThreshold&&
  nonNegativeSafeInteger(payload.lateAfterMinutes)&&nonNegativeSafeInteger(payload.earlyLeaveThresholdMinutes)&&nonNegativeSafeInteger(payload.temporaryMissingGraceSeconds)&&nonNegativeSafeInteger(payload.minimumPresenceVerificationSeconds)&&
  positiveSafeInteger(payload.createdAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.createdAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validUniversityPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','name','archivedAt','updatedAt','version'];
 if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.name)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validAcademicYearPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','name','startDate','endDate','isActive','updatedAt','version'];
 if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.name)&&validIsoDate(payload.startDate)&&validIsoDate(payload.endDate)&&payload.startDate<=payload.endDate&&typeof payload.isActive==='boolean'&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validFacultyPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','universityId','name','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.universityId)&&nonEmptyString(payload.name)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validDepartmentPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','facultyId','name','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.facultyId)&&nonEmptyString(payload.name)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validLevelPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','departmentId','name','orderIndex','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.departmentId)&&nonEmptyString(payload.name)&&nonNegativeSafeInteger(payload.orderIndex)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
const semesterStatuses=new Set(['UPCOMING','ACTIVE','COMPLETED','ARCHIVED']);
function validSemesterPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','name','academicYearId','startDate','endDate','status','createdAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.name)&&nonEmptyString(payload.academicYearId)&&validIsoDate(payload.startDate)&&validIsoDate(payload.endDate)&&payload.startDate<=payload.endDate&&semesterStatuses.has(payload.status)&&positiveSafeInteger(payload.createdAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.createdAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validBatchPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','levelId','academicYearId','name','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.levelId)&&nonEmptyString(payload.academicYearId)&&nonEmptyString(payload.name)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validSectionPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','batchId','name','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.batchId)&&nonEmptyString(payload.name)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validGroupPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','sectionId','name','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.sectionId)&&nonEmptyString(payload.name)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validStudentPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','universityNumber','fullName','normalizedName','phoneNumber','whatsappNumber','levelId','batchId','sectionId','groupId','status','createdAt','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 const refs=[payload.levelId,payload.batchId,payload.sectionId,payload.groupId];const allNull=refs.every(x=>x===null),allPresent=refs.every(nonEmptyString);if(!allNull&&!allPresent)return false;
 return payload.id===entityId&&nullableNonEmptyString(payload.universityNumber)&&nonEmptyString(payload.fullName)&&nonEmptyString(payload.normalizedName)&&nullableString(payload.phoneNumber)&&nullableString(payload.whatsappNumber)&&
  nullableNonEmptyString(payload.levelId)&&nullableNonEmptyString(payload.batchId)&&nullableNonEmptyString(payload.sectionId)&&nullableNonEmptyString(payload.groupId)&&studentStatuses.has(payload.status)&&
  positiveSafeInteger(payload.createdAt)&&nullablePositiveInteger(payload.archivedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.createdAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validSubjectPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','code','name','teacherId','levelId','semesterId','groupId','attendancePolicyId','status','archivedAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 return payload.id===entityId&&nonEmptyString(payload.code)&&nonEmptyString(payload.name)&&nonEmptyString(payload.teacherId)&&nonEmptyString(payload.levelId)&&nonEmptyString(payload.semesterId)&&nonEmptyString(payload.groupId)&&
  nonEmptyString(payload.attendancePolicyId)&&subjectStatuses.has(payload.status)&&nullablePositiveInteger(payload.archivedAt)&&((payload.status==='ARCHIVED')===(payload.archivedAt!==null))&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validLecturePayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','subjectId','teacherId','semesterId','groupId','scheduledStart','scheduledEnd','actualStart','actualEnd','room','status','attendancePolicySnapshotJson','createdAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 if(payload.id!==entityId||!nonEmptyString(payload.subjectId)||!nonEmptyString(payload.teacherId)||!nonEmptyString(payload.semesterId)||!nonEmptyString(payload.groupId)||!positiveSafeInteger(payload.scheduledStart)||!positiveSafeInteger(payload.scheduledEnd)||payload.scheduledEnd<=payload.scheduledStart)return false;
 if(!nullablePositiveInteger(payload.actualStart)||!nullablePositiveInteger(payload.actualEnd)||!nullableString(payload.room)||!lectureStatuses.has(payload.status)||typeof payload.attendancePolicySnapshotJson!=='string')return false;
 if(payload.actualStart!==null&&payload.actualEnd!==null&&payload.actualEnd<payload.actualStart)return false;
 return positiveSafeInteger(payload.createdAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.createdAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validAttendanceRecordPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 const keys=['id','lectureId','studentId','firstSeenAt','lastSeenAt','verifiedPresenceSeconds','lectureDurationSeconds','attendancePercentage','lateMinutes','earlyLeaveMinutes','confidenceScore','finalStatus','approvalStatus','source','notes','createdAt','updatedAt','version'];if(!exactKeys(payload,keys))return false;
 if(payload.id!==entityId||!nonEmptyString(payload.lectureId)||!nonEmptyString(payload.studentId)||!nullablePositiveInteger(payload.firstSeenAt)||!nullablePositiveInteger(payload.lastSeenAt))return false;
 if(payload.firstSeenAt!==null&&payload.lastSeenAt!==null&&payload.lastSeenAt<payload.firstSeenAt)return false;
 return nonNegativeSafeInteger(payload.verifiedPresenceSeconds)&&positiveSafeInteger(payload.lectureDurationSeconds)&&payload.verifiedPresenceSeconds<=payload.lectureDurationSeconds&&probability(payload.attendancePercentage)&&nonNegativeSafeInteger(payload.lateMinutes)&&nonNegativeSafeInteger(payload.earlyLeaveMinutes)&&probability(payload.confidenceScore)&&
  finalAttendanceStatuses.has(payload.finalStatus)&&approvalStatuses.has(payload.approvalStatus)&&presenceSources.has(payload.source)&&nullableString(payload.notes)&&positiveSafeInteger(payload.createdAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.createdAt&&positiveSafeInteger(payload.version)&&payload.version===entityVersion;
}
function validAttendanceAppealPayload(payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 if(!plainObject(payload))return false;
 const requiredNullable=['reviewedAt','reviewedBy','decisionNote','attachmentRemoteUrl'];
 if(requiredNullable.some(k=>!Object.prototype.hasOwnProperty.call(payload,k)))return false;
 return payload.id===entityId&&positiveSafeInteger(payload.version)&&payload.version===entityVersion&&
  nonEmptyString(payload.studentId)&&nonEmptyString(payload.attendanceRecordId)&&nonEmptyString(payload.lectureId)&&nonEmptyString(payload.subjectId)&&
  nonEmptyString(payload.reasonType)&&nonEmptyString(payload.description)&&appealStatuses.has(payload.status)&&
  positiveSafeInteger(payload.submittedAt)&&positiveSafeInteger(payload.updatedAt)&&payload.updatedAt===envelopeUpdatedAt&&payload.updatedAt>=payload.submittedAt&&
  nullablePositiveInteger(payload.reviewedAt)&&nullableString(payload.reviewedBy)&&nullableString(payload.decisionNote)&&nullableString(payload.attachmentRemoteUrl);
}
function validEntityPayload(entityType:string,payload:any,entityId:string,entityVersion:number,envelopeUpdatedAt:number){
 if(entityType==='Teacher')return validTeacherPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='AttendancePolicy')return validAttendancePolicyPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='University')return validUniversityPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='AcademicYear')return validAcademicYearPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Faculty')return validFacultyPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Department')return validDepartmentPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Level')return validLevelPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Semester')return validSemesterPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Batch')return validBatchPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Section')return validSectionPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Group')return validGroupPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Student')return validStudentPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Subject')return validSubjectPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='Lecture')return validLecturePayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='AttendanceRecord')return validAttendanceRecordPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 if(entityType==='AttendanceAppeal')return validAttendanceAppealPayload(payload,entityId,entityVersion,envelopeUpdatedAt);
 return false;
}
function validSyncEnvelope(d:any){return plainObject(d)&&validWorkspaceId(d.workspace)&&syncEntityTypes.has(d.entityType)&&d.operation==='UPSERT'&&nonEmptyString(d.entityId)&&positiveSafeInteger(d.entityVersion)&&positiveSafeInteger(d.updatedAt)&&validEntityPayload(d.entityType,d.payload,d.entityId,d.entityVersion,d.updatedAt)}
function validStoredSyncMutation(m:any,workspace:string){return plainObject(m)&&m.workspace===workspace&&syncEntityTypes.has(m.entityType)&&m.operation==='UPSERT'&&
 nonEmptyString(m.entityId)&&positiveSafeInteger(m.entityVersion)&&positiveSafeInteger(m.updatedAt)&&m.tombstone===false&&
 positiveSafeInteger(m.serverVersion)&&positiveSafeInteger(m.sequence)&&positiveSafeInteger(m.serverUpdatedAt)&&
 validEntityPayload(m.entityType,m.payload,m.entityId,m.entityVersion,m.updatedAt)}
function parentReferenceError(store:SyncStore,workspace:string,parentType:string,parentId:string){
 if(store.entities[syncEntityKey(workspace,parentType,parentId)])return null;
 const elsewhere=Object.values(store.entities).some(x=>x.entityType===parentType&&x.entityId===parentId&&x.workspace!==workspace);
 return elsewhere?'SYNC_PARENT_WORKSPACE_MISMATCH':'SYNC_PARENT_MISSING';
}
function syncReferenceError(store:SyncStore,workspace:string,entityType:string,payload:any):string|null{
 if(entityType==='Faculty')return parentReferenceError(store,workspace,'University',String(payload.universityId||''));
 if(entityType==='Department')return parentReferenceError(store,workspace,'Faculty',String(payload.facultyId||''));
 if(entityType==='Level')return parentReferenceError(store,workspace,'Department',String(payload.departmentId||''));
 if(entityType==='Semester'){
  const e=parentReferenceError(store,workspace,'AcademicYear',String(payload.academicYearId||''));if(e)return e;
  const parent=store.entities[syncEntityKey(workspace,'AcademicYear',String(payload.academicYearId||''))];
  if(parent&&(payload.startDate<(parent.payload as any).startDate||payload.endDate>(parent.payload as any).endDate))return'SYNC_PARENT_RANGE_INVALID';
 }
 if(entityType==='Batch'){const a=parentReferenceError(store,workspace,'Level',String(payload.levelId||''));if(a)return a;return parentReferenceError(store,workspace,'AcademicYear',String(payload.academicYearId||''));}
 if(entityType==='Section')return parentReferenceError(store,workspace,'Batch',String(payload.batchId||''));
 if(entityType==='Group')return parentReferenceError(store,workspace,'Section',String(payload.sectionId||''));
 if(entityType==='Student'){
  for(const [t,k] of [['Level','levelId'],['Batch','batchId'],['Section','sectionId'],['Group','groupId']] as const){const id=payload[k];if(id!==null){const e=parentReferenceError(store,workspace,t,String(id));if(e)return e}}
  const batch=payload.batchId?store.entities[syncEntityKey(workspace,'Batch',String(payload.batchId))]:null;if(batch&&payload.levelId!==null&&(batch.payload as any).levelId!==payload.levelId)return'SYNC_PARENT_CHAIN_MISMATCH';
  const section=payload.sectionId?store.entities[syncEntityKey(workspace,'Section',String(payload.sectionId))]:null;if(section&&payload.batchId!==null&&(section.payload as any).batchId!==payload.batchId)return'SYNC_PARENT_CHAIN_MISMATCH';
  const group=payload.groupId?store.entities[syncEntityKey(workspace,'Group',String(payload.groupId))]:null;if(group&&payload.sectionId!==null&&(group.payload as any).sectionId!==payload.sectionId)return'SYNC_PARENT_CHAIN_MISMATCH';
 }
 if(entityType==='Subject'){
  for(const [t,k] of [['Level','levelId'],['Semester','semesterId'],['Group','groupId']] as const){const e=parentReferenceError(store,workspace,t,String(payload[k]||''));if(e)return e}
  if(payload.teacherId!==null){const e=parentReferenceError(store,workspace,'Teacher',String(payload.teacherId));if(e)return e}
  if(payload.attendancePolicyId!==null){const e=parentReferenceError(store,workspace,'AttendancePolicy',String(payload.attendancePolicyId));if(e)return e}
  const group=store.entities[syncEntityKey(workspace,'Group',String(payload.groupId))];const section=group?store.entities[syncEntityKey(workspace,'Section',String((group.payload as any).sectionId||''))]:null;const batch=section?store.entities[syncEntityKey(workspace,'Batch',String((section.payload as any).batchId||''))]:null;
  if(batch&&(batch.payload as any).levelId!==payload.levelId)return'SYNC_PARENT_CHAIN_MISMATCH';
 }
 if(entityType==='Lecture'){
  for(const [t,k] of [['Subject','subjectId'],['Teacher','teacherId'],['Semester','semesterId'],['Group','groupId']] as const){const e=parentReferenceError(store,workspace,t,String(payload[k]||''));if(e)return e}
  const subject=store.entities[syncEntityKey(workspace,'Subject',String(payload.subjectId))];if(subject&&((subject.payload as any).semesterId!==payload.semesterId||(subject.payload as any).groupId!==payload.groupId))return'SYNC_PARENT_CHAIN_MISMATCH';
 }
 if(entityType==='AttendanceRecord'){
  for(const [t,k] of [['Student','studentId'],['Lecture','lectureId']] as const){const e=parentReferenceError(store,workspace,t,String(payload[k]||''));if(e)return e}
  const student=store.entities[syncEntityKey(workspace,'Student',String(payload.studentId))];const lecture=store.entities[syncEntityKey(workspace,'Lecture',String(payload.lectureId))];
  if(student&&lecture&&(student.payload as any).groupId!==null&&(student.payload as any).groupId!==(lecture.payload as any).groupId)return'SYNC_PARENT_CHAIN_MISMATCH';
 }
 if(entityType==='AttendanceAppeal'){
  for(const [t,k] of [['Student','studentId'],['AttendanceRecord','attendanceRecordId'],['Lecture','lectureId'],['Subject','subjectId']] as const){const e=parentReferenceError(store,workspace,t,String(payload[k]||''));if(e)return e}
  const record=store.entities[syncEntityKey(workspace,'AttendanceRecord',String(payload.attendanceRecordId))];const lecture=store.entities[syncEntityKey(workspace,'Lecture',String(payload.lectureId))];
  if(record&&((record.payload as any).studentId!==payload.studentId||(record.payload as any).lectureId!==payload.lectureId))return'SYNC_PARENT_CHAIN_MISMATCH';
  if(lecture&&(lecture.payload as any).subjectId!==payload.subjectId)return'SYNC_PARENT_CHAIN_MISMATCH';
 }
 return null;
}

function syncUniquenessError(store:SyncStore,workspace:string,entityType:string,entityId:string,payload:any):string|null{
 if(entityType==='Student'&&payload.universityNumber!==null){const n=String(payload.universityNumber);if(Object.values(store.entities).some(x=>x.workspace===workspace&&x.entityType==='Student'&&x.entityId!==entityId&&(x.payload as any).universityNumber===n))return'SYNC_UNIQUE_CONFLICT'}
 if(entityType==='AttendanceRecord'){if(Object.values(store.entities).some(x=>x.workspace===workspace&&x.entityType==='AttendanceRecord'&&x.entityId!==entityId&&(x.payload as any).lectureId===payload.lectureId&&(x.payload as any).studentId===payload.studentId))return'SYNC_UNIQUE_CONFLICT'}
 return null;
}

function hasParentMutationBefore(store:SyncStore,workspace:string,entityType:string,entityId:string,beforeSequence:number){return store.mutations.some(m=>m.workspace===workspace&&m.entityType===entityType&&m.entityId===entityId&&m.sequence<beforeSequence)}
function legacyAppealClosureReady(store:SyncStore,appeal:SyncMutation){
 const p=appeal.payload as any;const refs:[string,string][]=[['Student',String(p.studentId||'')],['AttendanceRecord',String(p.attendanceRecordId||'')],['Lecture',String(p.lectureId||'')],['Subject',String(p.subjectId||'')]];
 if(refs.some(([t,id])=>!id||!store.entities[syncEntityKey(appeal.workspace,t,id)]))return false;
 const record=store.entities[syncEntityKey(appeal.workspace,'AttendanceRecord',String(p.attendanceRecordId))];const lecture=store.entities[syncEntityKey(appeal.workspace,'Lecture',String(p.lectureId))];
 if((record.payload as any).studentId!==p.studentId||(record.payload as any).lectureId!==p.lectureId||(lecture.payload as any).subjectId!==p.subjectId)return false;
 return refs.every(([t,id])=>hasParentMutationBefore(store,appeal.workspace,t,id,appeal.sequence));
}
function reanchorReadyLegacyAppeals(store:SyncStore,workspace:string){
 const appeals=Object.values(store.entities).filter(x=>x.workspace===workspace&&x.entityType==='AttendanceAppeal');
 for(const appeal of appeals){
  if(!validAttendanceAppealPayload(appeal.payload,appeal.entityId,appeal.entityVersion,appeal.updatedAt))continue;
  if(legacyAppealClosureReady(store,appeal))continue;
  const p=appeal.payload as any;const refs:[string,string][]=[['Student',String(p.studentId||'')],['AttendanceRecord',String(p.attendanceRecordId||'')],['Lecture',String(p.lectureId||'')],['Subject',String(p.subjectId||'')]];
  if(refs.some(([t,id])=>!id||!store.entities[syncEntityKey(workspace,t,id)]))continue;
  const record=store.entities[syncEntityKey(workspace,'AttendanceRecord',String(p.attendanceRecordId))];const lecture=store.entities[syncEntityKey(workspace,'Lecture',String(p.lectureId))];
  if((record.payload as any).studentId!==p.studentId||(record.payload as any).lectureId!==p.lectureId||(lecture.payload as any).subjectId!==p.subjectId)continue;
  const sequence=++store.sequence;const replay:SyncMutation={...appeal,serverVersion:sequence,sequence,serverUpdatedAt:Date.now()};store.entities[syncEntityKey(workspace,'AttendanceAppeal',appeal.entityId)]=replay;store.mutations.push(replay);
 }
}
function closureReplayAppealIds(store:SyncStore,workspace:string){
 const ids=new Set<string>();
 for(const latest of Object.values(store.entities)){
  if(latest.workspace!==workspace||latest.entityType!=='AttendanceAppeal')continue;
  const duplicate=store.mutations.some(m=>m.workspace===workspace&&m.entityType==='AttendanceAppeal'&&m.entityId===latest.entityId&&m.sequence<latest.sequence&&m.entityVersion===latest.entityVersion&&stableHash(m.payload)===stableHash(latest.payload));
  if(duplicate)ids.add(latest.entityId);
 }
 return ids;
}

function json(res:any,status:number,body:Json){res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(JSON.stringify(body));}
function requestId(req:any){return String(req.headers['x-request-id']||randomBytes(8).toString('hex'));}
function secureLog(event:string,data:Json){console.log(JSON.stringify({ts:new Date().toISOString(),event,...data}));}
function bearerToken(req:any){const raw=String(req.headers.authorization||'');const m=/^Bearer\s+(.+)$/i.exec(raw);return m?.[1]?.trim()||''}
function tokenMatchesExpected(token:string,expected:string){if(!token||!/^[a-fA-F0-9]{64}$/.test(expected))return false;const actual=Buffer.from(tokenHash(token),'hex');const exp=Buffer.from(expected,'hex');return exp.length===actual.length&&timingSafeEqual(exp,actual)}
function tokenMatchesHash(req:any,expected:string){return tokenMatchesExpected(bearerToken(req),expected)}
function authorized(req:any){const expected=env('AUTH_TOKEN_HASH').trim();if(!expected)return env('APP_ENV','development').toLowerCase()!=='production';return tokenMatchesHash(req,expected)}
function bootstrapAuth(req:any):'OK'|'PROVISIONING_NOT_CONFIGURED'|'AUTH_REQUIRED'|'AUTH_INVALID'{const expected=env('AUTH_TOKEN_HASH').trim();if(!expected)return'PROVISIONING_NOT_CONFIGURED';const token=bearerToken(req);if(!token)return'AUTH_REQUIRED';return tokenMatchesExpected(token,expected)?'OK':'AUTH_INVALID'}
function rateLimit(req:any){const key=String(req.socket.remoteAddress||'unknown');const now=Date.now();const v=limits.get(key);if(!v||v.reset<now){limits.set(key,{count:1,reset:now+60_000});return true}v.count++;return v.count<=120}
async function body(req:any):Promise<Json>{let raw='';for await(const c of req){raw+=c;if(raw.length>8_000_000)throw new Error('PAYLOAD_TOO_LARGE')}if(!raw)return{};const value=JSON.parse(raw);if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('INVALID_JSON');return value}
async function relay(url:string,token:string,payload:Json){if(!url)return{ok:false,status:503,error:'PROVIDER_NOT_CONFIGURED'};const r=await fetch(url,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},body:JSON.stringify(payload)});const text=await r.text();return{ok:r.ok,status:r.status,body:text.slice(0,2000)}}

function sessionTtlMs(){const raw=Number(env('SESSION_TTL_SECONDS','604800'));const seconds=Number.isFinite(raw)&&raw>=60?Math.min(raw,31_536_000):604800;return Math.floor(seconds*1000)}
type SessionAuthResult={ok:true;session:SessionRecord;account:AccountRecord}|{ok:false;error:string};
function authenticateSession(store:SyncStore,req:any):SessionAuthResult{
 if(Object.keys(store.accounts).length===0)return{ok:false,error:'PROVISIONING_REQUIRED'};
 const token=bearerToken(req);if(!token)return{ok:false,error:'AUTH_REQUIRED'};
 const session=Object.values(store.sessions).find(s=>tokenMatchesExpected(token,s.tokenHash));
 if(!session)return{ok:false,error:'AUTH_INVALID'};
 if(session.revokedAt!==null)return{ok:false,error:'AUTH_REVOKED'};
 if(session.expiresAt<=Date.now())return{ok:false,error:'AUTH_EXPIRED'};
 const account=store.accounts[session.accountId];
 if(!account||account.status!=='ACTIVE')return{ok:false,error:'ACCOUNT_NOT_FOUND'};
 return{ok:true,session,account};
}
function workspaceAuthorization(store:SyncStore,accountId:string,workspaceId:string):string{
 const workspace=store.workspaces[workspaceId];if(!workspace||workspace.status!=='ACTIVE')return'WORKSPACE_NOT_FOUND';
 const member=Object.values(store.memberships).find(m=>m.accountId===accountId&&m.workspaceId===workspaceId&&m.status==='ACTIVE');
 return member?'OK':'MEMBERSHIP_REQUIRED';
}
function authStatus(error:string){return error==='PROVISIONING_REQUIRED'||error==='PROVISIONING_NOT_CONFIGURED'?503:error==='WORKSPACE_NOT_FOUND'?404:error==='MEMBERSHIP_REQUIRED'?403:error==='AUTH_REQUIRED'||error==='AUTH_INVALID'||error==='AUTH_EXPIRED'||error==='AUTH_REVOKED'||error==='ACCOUNT_NOT_FOUND'?401:403}
function activeMemberships(store:SyncStore,accountId:string){return Object.values(store.memberships).filter(m=>m.accountId===accountId&&m.status==='ACTIVE'&&store.workspaces[m.workspaceId]?.status==='ACTIVE').map(m=>m.workspaceId)}

function aiKey(provider:string){const p=provider.toUpperCase();return p==='OPENAI'?env('OPENAI_API_KEY'):p==='GEMINI'?env('GEMINI_API_KEY'):p==='CLAUDE'?env('ANTHROPIC_API_KEY'):''}
function textCandidate(id:string){const x=id.toLowerCase();return !['embedding','moderation','image','dall-e','tts','transcribe','whisper','realtime','audio','sora'].some(v=>x.includes(v))}
function extractText(value:any):string{
 if(!value)return'';if(typeof value==='string')return value;
 if(Array.isArray(value))return value.map(extractText).filter(Boolean).join('\n');
 if(typeof value==='object'){if(typeof value.output_text==='string')return value.output_text;if(value.type==='text'&&typeof value.text==='string')return value.text;if(typeof value.text==='string')return value.text;return Object.entries(value).filter(([k])=>!['id','model','usage'].includes(k)).map(([,v])=>extractText(v)).filter(Boolean).join('\n')}
 return''
}
async function fetchJson(url:string,init:any){const r=await fetch(url,init);const text=await r.text();let data:any={};try{data=JSON.parse(text)}catch{}return{ok:r.ok,status:r.status,text,data}}
async function providerModels(provider:string){
 const p=provider.toUpperCase(),key=aiKey(p);if(!key)return{ok:false,status:503,error:'PROVIDER_NOT_CONFIGURED',models:[] as string[]};
 if(p==='OPENAI'){const r=await fetchJson('https://api.openai.com/v1/models',{headers:{authorization:`Bearer ${key}`}});return{ok:r.ok,status:r.status,error:r.ok?undefined:`OPENAI_${r.status}`,models:Array.isArray(r.data?.data)?r.data.data.map((m:any)=>String(m.id||'')).filter(textCandidate):[]}}
 if(p==='GEMINI'){const r=await fetchJson('https://generativelanguage.googleapis.com/v1beta/models',{headers:{'x-goog-api-key':key}});return{ok:r.ok,status:r.status,error:r.ok?undefined:`GEMINI_${r.status}`,models:Array.isArray(r.data?.models)?r.data.models.filter((m:any)=>!Array.isArray(m.supportedGenerationMethods)||m.supportedGenerationMethods.includes('generateContent')).map((m:any)=>String(m.name||'')).filter(Boolean):[]}}
 if(p==='CLAUDE'){const r=await fetchJson('https://api.anthropic.com/v1/models',{headers:{'x-api-key':key,'anthropic-version':'2023-06-01'}});return{ok:r.ok,status:r.status,error:r.ok?undefined:`CLAUDE_${r.status}`,models:Array.isArray(r.data?.data)?r.data.data.map((m:any)=>String(m.id||'')).filter(Boolean):[]}}
 return{ok:false,status:400,error:'UNSUPPORTED_AI_PROVIDER',models:[] as string[]}
}
async function providerGenerate(provider:string,model:string,prompt:string,vision?:{mime:string;base64:string}){
 const p=provider.toUpperCase(),key=aiKey(p);if(!key)return{ok:false,status:503,error:'PROVIDER_NOT_CONFIGURED',text:''};if(!model)return{ok:false,status:400,error:'MODEL_REQUIRED',text:''};
 if(p==='OPENAI'){const content:any[]= [{type:'input_text',text:prompt}];if(vision)content.push(vision.mime==='application/pdf'?{type:'input_file',filename:'timetable.pdf',file_data:`data:application/pdf;base64,${vision.base64}`}:{type:'input_image',image_url:`data:${vision.mime};base64,${vision.base64}`});const payload=vision?{model,input:[{role:'user',content}]}:{model,input:prompt};const r=await fetchJson('https://api.openai.com/v1/responses',{method:'POST',headers:{authorization:`Bearer ${key}`,'content-type':'application/json'},body:JSON.stringify(payload)});return{ok:r.ok,status:r.status,error:r.ok?undefined:`OPENAI_${r.status}`,text:extractText(r.data)}}
 if(p==='GEMINI'){const parts:any[]=[{text:prompt}];if(vision)parts.push({inline_data:{mime_type:vision.mime,data:vision.base64}});const r=await fetchJson(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model.replace(/^models\//,''))}:generateContent`,{method:'POST',headers:{'x-goog-api-key':key,'content-type':'application/json'},body:JSON.stringify({contents:[{parts}]})});return{ok:r.ok,status:r.status,error:r.ok?undefined:`GEMINI_${r.status}`,text:extractText(r.data?.candidates?.[0]?.content?.parts)}}
 if(p==='CLAUDE'){const content:any[]=vision?[{type:vision.mime==='application/pdf'?'document':'image',source:{type:'base64',media_type:vision.mime,data:vision.base64}},{type:'text',text:prompt}]:[{type:'text',text:prompt}];const r=await fetchJson('https://api.anthropic.com/v1/messages',{method:'POST',headers:{'x-api-key':key,'anthropic-version':'2023-06-01','content-type':'application/json'},body:JSON.stringify({model,max_tokens:2048,messages:[{role:'user',content}]})});return{ok:r.ok,status:r.status,error:r.ok?undefined:`CLAUDE_${r.status}`,text:extractText(r.data?.content)}}
 return{ok:false,status:400,error:'UNSUPPORTED_AI_PROVIDER',text:''}
}

const server=http.createServer(async(req:any,res:any)=>{
 const rid=requestId(req);res.setHeader('x-request-id',rid);try{
  if(!rateLimit(req))return json(res,429,{error:'RATE_LIMITED',request_id:rid});
  const url=new URL(req.url||'/',`http://${req.headers.host||'localhost'}`);
  if(url.pathname==='/health')return json(res,200,{ok:true,service:'Hammam AttendAI Backend'});
  if(!url.pathname.startsWith('/api/v1/'))return json(res,404,{error:'NOT_FOUND',request_id:rid});
  if(req.method!=='POST')return json(res,405,{error:'METHOD_NOT_ALLOWED',request_id:rid});

  if(url.pathname==='/api/v1/auth/status'){
   const store=await loadSyncStore();const provisioningRequired=Object.keys(store.accounts).length===0;
   secureLog('request',{request_id:rid,path:url.pathname,status:200});return json(res,200,{ok:true,provisioningRequired});
  }

  if(url.pathname==='/api/v1/auth/bootstrap'||url.pathname==='/api/v1/auth/provision'){
   const bootstrap=bootstrapAuth(req);if(bootstrap!=='OK'){const status=authStatus(bootstrap);secureLog('request',{request_id:rid,path:url.pathname,status});return json(res,status,{ok:false,error:bootstrap,request_id:rid})}
   const data=await body(req);const displayName=String((data as any).displayName||'').trim();const requestedWorkspace=String((data as any).workspaceId||'').trim();const workspaceName=String((data as any).workspaceName||'').trim();
   if(!displayName||displayName.length>120)return json(res,400,{ok:false,error:'ACCOUNT_INPUT_INVALID',request_id:rid});
   if(requestedWorkspace&&!validWorkspaceId(requestedWorkspace))return json(res,400,{ok:false,error:'WORKSPACE_INVALID',request_id:rid});
   const isBootstrap=url.pathname.endsWith('/bootstrap');const configured=configuredSyncWorkspace();
   if(isBootstrap&&configured&&requestedWorkspace&&requestedWorkspace!==configured)return json(res,403,{ok:false,error:'WORKSPACE_BOOTSTRAP_MISMATCH',request_id:rid});
   const accountId=newId('acct'),credential=secureToken(),credentialHash=tokenHash(credential),now=Date.now();
   const result=await mutateSyncStore(store=>{
    const hasAccounts=Object.keys(store.accounts).length>0;
    if(isBootstrap&&hasAccounts)return{status:409,body:{ok:false,error:'PROVISIONING_COMPLETE'} as Json};
    if(!isBootstrap&&!hasAccounts)return{status:409,body:{ok:false,error:'PROVISIONING_REQUIRED'} as Json};
    const workspaceId=(isBootstrap&&configured)||requestedWorkspace||`ws-${randomUUID()}`;
    let workspace=store.workspaces[workspaceId];
    if(!workspace){workspace={id:workspaceId,displayName:(workspaceName||'Hammam AttendAI Workspace').slice(0,120),status:'ACTIVE',createdAt:now,updatedAt:now};store.workspaces[workspaceId]=workspace}
    else if(workspace.status!=='ACTIVE')return{status:409,body:{ok:false,error:'WORKSPACE_DISABLED'} as Json};
    const account:AccountRecord={id:accountId,displayName,status:'ACTIVE',credentialHash,createdAt:now,updatedAt:now};store.accounts[accountId]=account;
    const membership:MembershipRecord={id:newId('mbr'),accountId,workspaceId,status:'ACTIVE',createdAt:now,updatedAt:now};store.memberships[membership.id]=membership;
    return{status:isBootstrap?201:201,body:{ok:true,accountId,workspaceId,accountCredential:credential} as Json};
   });
   secureLog('request',{request_id:rid,path:url.pathname,status:result.status});return json(res,result.status,result.body);
  }

  if(url.pathname==='/api/v1/auth/session'){
   const data=await body(req);const accountId=String((data as any).accountId||'').trim();const credential=bearerToken(req);const store=await loadSyncStore();
   if(Object.keys(store.accounts).length===0)return json(res,409,{ok:false,error:'PROVISIONING_REQUIRED',request_id:rid});
   if(!accountId||!credential)return json(res,401,{ok:false,error:'AUTH_REQUIRED',request_id:rid});
   const account=store.accounts[accountId];if(!account||account.status!=='ACTIVE'||!tokenMatchesExpected(credential,account.credentialHash))return json(res,401,{ok:false,error:'AUTH_INVALID',request_id:rid});
   const sessionToken=secureToken(),sessionId=newId('ses'),now=Date.now(),expiresAt=now+sessionTtlMs();
   const issued=await mutateSyncStore(current=>{
    const latest=current.accounts[accountId];if(!latest||latest.status!=='ACTIVE'||!tokenMatchesExpected(credential,latest.credentialHash))return{status:401,body:{ok:false,error:'AUTH_INVALID'} as Json};
    const session:SessionRecord={id:sessionId,accountId,tokenHash:tokenHash(sessionToken),createdAt:now,expiresAt,revokedAt:null};current.sessions[sessionId]=session;
    return{status:201,body:{ok:true,accountId,sessionToken,expiresAt,workspaces:activeMemberships(current,accountId)} as Json};
   });
   secureLog('request',{request_id:rid,path:url.pathname,status:issued.status});return json(res,issued.status,issued.body);
  }

  if(url.pathname==='/api/v1/auth/identity'||url.pathname==='/api/v1/auth/logout'){
   const store=await loadSyncStore();const auth=authenticateSession(store,req);
   if(!auth.ok){const status=authStatus(auth.error);secureLog('request',{request_id:rid,path:url.pathname,status});return json(res,status,{ok:false,error:auth.error,request_id:rid})}
   if(url.pathname==='/api/v1/auth/identity'){
    const workspaces=activeMemberships(store,auth.account.id);secureLog('request',{request_id:rid,path:url.pathname,status:200});return json(res,200,{ok:true,accountId:auth.account.id,displayName:auth.account.displayName,workspaces,sessionExpiresAt:auth.session.expiresAt});
   }
   const revoked=await mutateSyncStore(current=>{const s=current.sessions[auth.session.id];if(!s)return{status:401,body:{ok:false,error:'AUTH_INVALID'} as Json};if(s.revokedAt===null)s.revokedAt=Date.now();return{status:200,body:{ok:true} as Json}});
   secureLog('request',{request_id:rid,path:url.pathname,status:revoked.status});return json(res,revoked.status,revoked.body);
  }

  const durableSync=url.pathname==='/api/v1/sync/push'||url.pathname==='/api/v1/sync/pull';
  if(!durableSync&&!authorized(req))return json(res,401,{error:'UNAUTHORIZED',request_id:rid});
  const key=String(req.headers['idempotency-key']||'');if(!key)return json(res,400,{error:'IDEMPOTENCY_KEY_REQUIRED',request_id:rid});
  const cached=durableSync?undefined:idempotency.get(key);if(cached&&cached.expires>Date.now())return json(res,cached.status,cached.body);
  const data=await body(req);let status=200;let out:Json={ok:true};

  if(url.pathname==='/api/v1/notifications/whatsapp'){
   const r=await relay(env('WHATSAPP_PROVIDER_URL'),env('WHATSAPP_ACCESS_TOKEN'),data);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_message_id:createHash('sha256').update(key).digest('hex').slice(0,20)}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/notifications/email'){
   const r=await relay(env('EMAIL_PROVIDER_URL'),env('EMAIL_API_KEY'),data);status=r.ok?200:r.status;out=r.ok?{ok:true}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/reports/send'){
   const channel=String((data as any).channel||'').toUpperCase();
   const providerUrl=channel==='WHATSAPP'?env('WHATSAPP_PROVIDER_URL'):channel==='EMAIL'?env('EMAIL_PROVIDER_URL'):env('REPORT_PROVIDER_URL');
   const providerToken=channel==='WHATSAPP'?env('WHATSAPP_ACCESS_TOKEN'):channel==='EMAIL'?env('EMAIL_API_KEY'):env('REPORT_PROVIDER_TOKEN');
   const r=await relay(providerUrl,providerToken,data);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_message_id:createHash('sha256').update(key).digest('hex').slice(0,20)}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/ai/providers/models'){
   const provider=String((data as any).provider||'').toUpperCase();const r=await providerModels(provider);status=r.ok?200:r.status;out=r.ok?{ok:true,models:r.models}:{ok:false,error:r.error};
  }else if(url.pathname==='/api/v1/ai/providers/summarize'){
   const provider=String((data as any).provider||'').toUpperCase(),model=String((data as any).model||''),prompt=String((data as any).prompt||''),facts=(data as any).facts;
   if(!facts||typeof facts!=='object')return json(res,400,{error:'FACTS_REQUIRED',request_id:rid});const grounded=`Authoritative facts: ${JSON.stringify(facts)}\nTask: ${prompt}\nDo not invent numbers. State when data is insufficient.`;const r=await providerGenerate(provider,model,grounded);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_response:r.text}:{ok:false,error:r.error};
  }else if(url.pathname==='/api/v1/ai/providers/vision'){
   const provider=String((data as any).provider||'').toUpperCase(),model=String((data as any).model||''),prompt=String((data as any).prompt||''),mime=String((data as any).mime||''),image=String((data as any).image_base64||'');if(!mime||!image)return json(res,400,{error:'IMAGE_REQUIRED',request_id:rid});const r=await providerGenerate(provider,model,prompt,{mime,base64:image});status=r.ok?200:r.status;out=r.ok?{ok:true,provider_response:r.text}:{ok:false,error:r.error};
  }else if(url.pathname==='/api/v1/ai/grounded-summary'){
   const facts=(data as any).facts;if(!facts||typeof facts!=='object')return json(res,400,{error:'FACTS_REQUIRED',request_id:rid});
   const r=await relay(env('AI_PROVIDER_URL'),env('AI_API_KEY'),data);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_response:r.body}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/sync/push'){
   if(env('CLOUD_SYNC_ENABLED','false')!=='true'){status=503;out={ok:false,error:'CLOUD_SYNC_DISABLED'}}
   else {
    const workspace=String((data as any).workspace||'');
    if(!validWorkspaceId(workspace)){status=400;out={ok:false,error:'WORKSPACE_INVALID'}}
    else {
     const store=await loadSyncStore(),auth=authenticateSession(store,req);
     if(!auth.ok){status=authStatus(auth.error);out={ok:false,error:auth.error}}
     else {
      const access=workspaceAuthorization(store,auth.account.id,workspace);
      if(access!=='OK'){status=authStatus(access);out={ok:false,error:access}}
      else if(!validSyncEnvelope(data)){status=400;out={ok:false,error:'SYNC_INVALID_PAYLOAD'}}
      else try{
       const requestHash=stableHash(data);const entityType=String((data as any).entityType),entityId=String((data as any).entityId),entityVersion=Number((data as any).entityVersion),updatedAt=Number((data as any).updatedAt),payload=(data as any).payload as Json;
       const result=await mutateSyncStore(current=>{
        const liveAuth=authenticateSession(current,req);if(!liveAuth.ok)return{status:authStatus(liveAuth.error),body:{ok:false,error:liveAuth.error} as Json};
        const liveAccess=workspaceAuthorization(current,liveAuth.account.id,workspace);if(liveAccess!=='OK')return{status:authStatus(liveAccess),body:{ok:false,error:liveAccess} as Json};
        const referenceError=syncReferenceError(current,workspace,entityType,payload);if(referenceError)return{status:409,body:{ok:false,error:referenceError} as Json};
        const uniquenessError=syncUniquenessError(current,workspace,entityType,entityId,payload);if(uniquenessError)return{status:409,body:{ok:false,error:uniquenessError} as Json};
        const scopedKey=syncIdempotencyKey(workspace,key);const legacyKey=configuredSyncWorkspace()===workspace?key:'';const priorIdem=current.idempotency[scopedKey]||(legacyKey?current.idempotency[legacyKey]:undefined);
        if(priorIdem){if(priorIdem.requestHash!==requestHash)return{status:409,body:{ok:false,error:'SYNC_IDEMPOTENCY_MISMATCH'} as Json};if(!current.idempotency[scopedKey])current.idempotency[scopedKey]=priorIdem;return{status:priorIdem.status,body:priorIdem.body}}
        const ek=syncEntityKey(workspace,entityType,entityId),existing=current.entities[ek];
        if(existing&&entityVersion<existing.entityVersion){const response={ok:false,error:'SYNC_VERSION_CONFLICT',conflict:true,serverVersion:existing.serverVersion,cursor:current.sequence};current.idempotency[scopedKey]={requestHash,status:409,body:response};return{status:409,body:response}}
        if(existing&&entityVersion===existing.entityVersion&&stableHash(existing.payload)!==stableHash(payload)){const response={ok:false,error:'SYNC_CONFLICT',conflict:true,serverVersion:existing.serverVersion,cursor:current.sequence};current.idempotency[scopedKey]={requestHash,status:409,body:response};return{status:409,body:response}}
        if(existing&&entityVersion===existing.entityVersion&&stableHash(existing.payload)===stableHash(payload)){const response={ok:true,persisted:true,noOp:true,serverVersion:existing.serverVersion,cursor:current.sequence};current.idempotency[scopedKey]={requestHash,status:200,body:response};return{status:200,body:response}}
        const sequence=++current.sequence;const mutation:SyncMutation={workspace,entityType,entityId,operation:'UPSERT',entityVersion,updatedAt,payload,tombstone:false,serverVersion:sequence,sequence,serverUpdatedAt:Date.now()};current.entities[ek]=mutation;current.mutations.push(mutation);
        if(entityType in {Student:1,Subject:1,Lecture:1,AttendanceRecord:1})reanchorReadyLegacyAppeals(current,workspace);
        const response={ok:true,persisted:true,serverVersion:sequence,cursor:current.sequence};current.idempotency[scopedKey]={requestHash,status:200,body:response};return{status:200,body:response};
       });status=result.status;out=result.body;
      }catch{status=503;out={ok:false,error:'SYNC_STORAGE_FAILURE'}}
     }
    }
   }
  }else if(url.pathname==='/api/v1/sync/pull'){
   if(env('CLOUD_SYNC_ENABLED','false')!=='true'){status=503;out={ok:false,error:'CLOUD_SYNC_DISABLED'}}
   else {
    const workspace=String((data as any).workspace||''),cursor=Number((data as any).cursor??0),limit=Math.min(100,Math.max(1,Number((data as any).limit??50)));
    if(!validWorkspaceId(workspace)){status=400;out={ok:false,error:'WORKSPACE_INVALID'}}
    else if(!Number.isInteger(cursor)||cursor<0||!Number.isInteger(limit)){status=400;out={ok:false,error:'SYNC_CURSOR_INVALID'}}
    else try{
     const store=await loadSyncStore(),auth=authenticateSession(store,req);
     if(!auth.ok){status=authStatus(auth.error);out={ok:false,error:auth.error}}
     else {
      const access=workspaceAuthorization(store,auth.account.id,workspace);
      if(access!=='OK'){status=authStatus(access);out={ok:false,error:access}}
      else if(cursor>store.sequence){status=400;out={ok:false,error:'SYNC_CURSOR_INVALID'}}
      else {
       const workspaceMutations=store.mutations.filter((x:any)=>x?.workspace===workspace);
       const unplaceable=workspaceMutations.find((x:any)=>!positiveSafeInteger(x?.sequence));
       if(unplaceable){status=409;out={ok:false,error:'SYNC_STORED_MUTATION_INVALID',sequence:null,entityId:typeof (unplaceable as any)?.entityId==='string'?(unplaceable as any).entityId:null}}
       else {
        const rawPending=workspaceMutations.filter(x=>x.sequence>cursor).sort((a,b)=>a.sequence-b.sequence);const malformed=rawPending.find(x=>!validStoredSyncMutation(x,workspace));
        if(malformed){status=409;out={ok:false,error:'SYNC_STORED_MUTATION_INVALID',sequence:malformed.sequence,entityId:typeof malformed.entityId==='string'?malformed.entityId:null}}
        else {const replayIds=closureReplayAppealIds(store,workspace);const pending=rawPending.filter(x=>!(x.entityType==='AttendanceAppeal'&&replayIds.has(x.entityId)&&x.sequence<(store.entities[syncEntityKey(workspace,'AttendanceAppeal',x.entityId)]?.sequence||0)));const changes=pending.slice(0,limit);const nextCursor=changes.length?changes[changes.length-1].sequence:(rawPending.length&&pending.length===0?rawPending[rawPending.length-1].sequence:cursor);out={ok:true,changes,nextCursor,hasMore:pending.length>changes.length}}
       }
      }
     }
    }catch{status=503;out={ok:false,error:'SYNC_STORAGE_FAILURE'}}
   }
  }else if(url.pathname==='/api/v1/backups/upload'){
   if(env('REMOTE_BACKUP_ENABLED','false')!=='true'){status=503;out={ok:false,error:'REMOTE_BACKUP_DISABLED'}} else out={ok:true,accepted:true};
  }else{status=404;out={error:'NOT_FOUND',request_id:rid}}

  if(!durableSync)idempotency.set(key,{status,body:out,expires:Date.now()+24*3600_000});secureLog('request',{request_id:rid,path:url.pathname,status});return json(res,status,out);
 }catch(e:any){const code=e?.message==='PAYLOAD_TOO_LARGE'?413:400;secureLog('error',{request_id:rid,code:e?.message||'UNKNOWN'});return json(res,code,{error:'REQUEST_FAILED',request_id:rid})}
});
server.listen(port,()=>secureLog('startup',{port,env:env('APP_ENV','development')}));
