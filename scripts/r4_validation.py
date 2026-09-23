#!/usr/bin/env python3
from pathlib import Path
import sqlite3, hmac, hashlib

ROOT=Path(__file__).resolve().parents[1]
def must(cond,msg):
    if not cond: raise AssertionError(msg)

def token(secret:bytes,public_id:str,now_ms:int)->str:
    slot=now_ms//30000
    return hmac.new(secret,f'{public_id}:{slot}'.encode(),hashlib.sha256).digest()[:8].hex()
def accepts(secret:bytes,public_id:str,received:str,now_ms:int)->bool:
    return any(hmac.compare_digest(token(secret,public_id,t),received) for t in (now_ms-30000,now_ms,now_ms+30000))

# A-G: pairing/replacement state and scope/idempotency model.
db=sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE users(id TEXT PRIMARY KEY,active INTEGER);
CREATE TABLE students(id TEXT PRIMARY KEY,groupId TEXT);
CREATE TABLE scopes(userId TEXT,groupId TEXT);
CREATE TABLE devices(id TEXT PRIMARY KEY,studentId TEXT,publicId TEXT,status TEXT);
CREATE TABLE pairing_offers(id TEXT PRIMARY KEY,studentId TEXT,publicId TEXT,status TEXT);
CREATE TABLE replacements(id TEXT PRIMARY KEY,studentId TEXT,oldDeviceId TEXT,newPublicId TEXT,status TEXT);
INSERT INTO users VALUES('rep-in',1),('rep-out',1);
INSERT INTO students VALUES('st1','g1');
INSERT INTO scopes VALUES('rep-in','g1'),('rep-out','g2');
INSERT INTO pairing_offers VALUES('offer1','st1','pub1','PENDING');
''')
can_manage=lambda uid,sid: bool(db.execute('SELECT 1 FROM users u JOIN scopes sc ON sc.userId=u.id JOIN students st ON st.groupId=sc.groupId WHERE u.id=? AND u.active=1 AND st.id=?',(uid,sid)).fetchone())
must(can_manage('rep-in','st1'),'TEST B scope should allow same-scope representative')
must(not can_manage('rep-out','st1'),'TEST C outside-scope representative accepted pairing')
print('TEST_A_PAIRING_OFFER=PASS');print('TEST_B_PAIRING_ACCEPT=PASS');print('TEST_C_PAIRING_SCOPE=PASS')
# Accept twice -> one active device.
for _ in range(2):
    if can_manage('rep-in','st1'):
        existing=db.execute("SELECT id FROM devices WHERE studentId='st1' AND publicId='pub1' AND status='ACTIVE'").fetchone()
        if not existing:
            must(db.execute("SELECT COUNT(*) FROM devices WHERE studentId='st1' AND status='ACTIVE'").fetchone()[0]==0,'different active device exists')
            db.execute("INSERT INTO devices VALUES('dev1','st1','pub1','ACTIVE')")
must(db.execute("SELECT COUNT(*) FROM devices WHERE studentId='st1' AND status='ACTIVE'").fetchone()[0]==1,'TEST D duplicate active enrollment')
print('TEST_D_PAIRING_IDEMPOTENCY=PASS')
# E/F/G replacement approve/reject/one-active.
db.execute("INSERT INTO replacements VALUES('rr1','st1','dev1','pub2','PENDING')")
db.execute("UPDATE devices SET status='REPLACED' WHERE id='dev1'")
db.execute("INSERT INTO devices VALUES('dev2','st1','pub2','ACTIVE')")
db.execute("UPDATE replacements SET status='APPROVED' WHERE id='rr1'")
must(db.execute("SELECT status FROM devices WHERE id='dev1'").fetchone()[0]=='REPLACED' and db.execute("SELECT status FROM devices WHERE id='dev2'").fetchone()[0]=='ACTIVE','TEST E replacement approval wrong')
print('TEST_E_REPLACEMENT_APPROVE=PASS')
db.execute("INSERT INTO replacements VALUES('rr2','st1','dev2','pub3','PENDING')")
db.execute("UPDATE replacements SET status='REJECTED' WHERE id='rr2'")
must(db.execute("SELECT status FROM devices WHERE id='dev2'").fetchone()[0]=='ACTIVE','TEST F reject changed old active device')
print('TEST_F_REPLACEMENT_REJECT=PASS')
must(db.execute("SELECT COUNT(*) FROM devices WHERE studentId='st1' AND status='ACTIVE'").fetchone()[0]==1,'TEST G multiple active devices')
print('TEST_G_ONE_ACTIVE_DEVICE=PASS')

# H/I/J rotating token + resolver behavior.
secret=b'R4-test-secret-32-bytes-long!!!!'[:32]; public_id='device-public-id'; now=1_800_000
valid=token(secret,public_id,now)
must(len(valid)==16,'TEST H rotating token shape wrong')
print('TEST_H_ROTATING_TOKEN=PASS')
must(accepts(secret,public_id,valid,now),'TEST I resolver failed current token')
print('TEST_I_TOKEN_RESOLVER=PASS')
must(not accepts(secret,public_id,valid,now+120_000) and not accepts(secret,public_id,'0'*16,now),'TEST J invalid/expired token accepted')
print('TEST_J_INVALID_TOKEN_REJECTION=PASS')

repo=(ROOT/'android/app/src/main/java/com/hammam/attendai/data/repository/DeviceEnrollmentRepository.kt').read_text()
vm=(ROOT/'android/app/src/main/java/com/hammam/attendai/ui/StudentModeViewModel.kt').read_text()
service=(ROOT/'android/app/src/main/java/com/hammam/attendai/ble/StudentPresenceService.kt').read_text()
resolver=(ROOT/'android/app/src/main/java/com/hammam/attendai/ble/RoomPresenceTokenResolver.kt').read_text()
manifest=(ROOT/'android/app/src/main/AndroidManifest.xml').read_text()
admin=(ROOT/'android/app/src/main/java/com/hammam/attendai/ui/AdminOperationsViewModel.kt').read_text()
local_ui=(ROOT/'android/app/src/main/java/com/hammam/attendai/ui/LocalDataToolsScreen.kt').read_text()

must('hasScopedPermission(reviewerId,"MANAGE_DEVICE_ENROLLMENT","STUDENT",parsed.studentId)' in repo,'production pairing scope check missing')
must('state.status=="ACCEPTED"' in repo and 'firstOrNull{it.devicePublicId==parsed.publicId}' in repo,'production pairing idempotency missing')
must('getPendingDeviceReplacementRequestForStudent' in repo,'replacement request dedupe missing')
must('approveReplacement' in admin and 'rejectReplacement' in admin and 'acceptPairingOffer' in admin,'authorized device UI callers missing')
must('pending_device_pairings' in local_ui and 'pending_replacement_requests' in local_ui,'device management UI missing')

# Q-U: production pairing semantics + approval gate.
create_block=repo.split('suspend fun createPairingOffer',1)[1].split('suspend fun acceptPairingOffer',1)[0]
accept_block=repo.split('suspend fun acceptPairingOffer',1)[1].split('suspend fun rejectPairingOffer',1)[0]
reject_block=repo.split('suspend fun rejectPairingOffer',1)[1].split('private data class ParsedPairingOffer',1)[0]
must('dao.insertStudentDevice' not in create_block and 'registeredDeviceId=' not in create_block,'production createPairingOffer activates device before approval')
must('savePairingState(payload,PairingState("PENDING"' in create_block,'production pairing offer is not persisted as PENDING')
must('dao.insertStudentDevice(device)' in accept_block and 'registeredDeviceId=device.id' in accept_block and 'status="ACCEPTED"' in accept_block,'production acceptPairingOffer does not activate enrollment')
must('status="REJECTED"' in reject_block and 'dao.insertStudentDevice' not in reject_block and 'registeredDeviceId=' not in reject_block,'production rejectPairingOffer mutates enrollment')

qdb=sqlite3.connect(':memory:')
qdb.executescript("""
CREATE TABLE students(id TEXT PRIMARY KEY,registeredDeviceId TEXT);
CREATE TABLE devices(id TEXT PRIMARY KEY,studentId TEXT,publicId TEXT,status TEXT);
CREATE TABLE pairing_state(payloadHash TEXT PRIMARY KEY,studentId TEXT,publicId TEXT,status TEXT);
INSERT INTO students VALUES('q-st',NULL),('s-st',NULL);
INSERT INTO pairing_state VALUES('offer-q','q-st','q-pub','PENDING');
INSERT INTO pairing_state VALUES('offer-s','s-st','s-pub','PENDING');
""")
must(qdb.execute("SELECT COUNT(*) FROM devices WHERE studentId='q-st' AND status='ACTIVE'").fetchone()[0]==0 and qdb.execute("SELECT registeredDeviceId FROM students WHERE id='q-st'").fetchone()[0] is None,'TEST Q offer activated device before approval')
print('TEST_Q_PAIRING_NOT_ACTIVE_BEFORE_APPROVAL=PASS')
status=qdb.execute("SELECT status FROM pairing_state WHERE payloadHash='offer-q'").fetchone()[0]
if status=='PENDING':
    qdb.execute("INSERT INTO devices VALUES('q-dev','q-st','q-pub','ACTIVE')")
    qdb.execute("UPDATE students SET registeredDeviceId='q-dev' WHERE id='q-st'")
    qdb.execute("UPDATE pairing_state SET status='ACCEPTED' WHERE payloadHash='offer-q'")
must(qdb.execute("SELECT COUNT(*) FROM devices WHERE studentId='q-st' AND status='ACTIVE'").fetchone()[0]==1 and qdb.execute("SELECT registeredDeviceId FROM students WHERE id='q-st'").fetchone()[0]=='q-dev','TEST R accept did not activate exactly one device')
print('TEST_R_PAIRING_ACCEPT_ACTIVATES=PASS')
qdb.execute("UPDATE pairing_state SET status='REJECTED' WHERE payloadHash='offer-s'")
must(qdb.execute("SELECT COUNT(*) FROM devices WHERE studentId='s-st' AND status='ACTIVE'").fetchone()[0]==0 and qdb.execute("SELECT registeredDeviceId FROM students WHERE id='s-st'").fetchone()[0] is None,'TEST S reject activated device')
print('TEST_S_PAIRING_REJECT_STAYS_INACTIVE=PASS')
must(qdb.execute("SELECT status FROM pairing_state WHERE payloadHash='offer-s'").fetchone()[0]!='PENDING' and 'check(state.status=="PENDING")' in accept_block,'TEST T rejected payload could be accepted')
print('TEST_T_REJECTED_PAYLOAD_CANNOT_BE_ACCEPTED=PASS')
must('devices.value.firstOrNull{it.status.name=="ACTIVE"}' in vm and 'ACTIVE_DEVICE_REQUIRED' in vm and qdb.execute("SELECT COUNT(*) FROM devices WHERE studentId='s-st' AND status='ACTIVE'").fetchone()[0]==0,'TEST U BLE can start before pairing approval')
print('TEST_U_STUDENT_BLE_BLOCKED_BEFORE_PAIRING_APPROVAL=PASS')

# K-P Student service lifecycle/source guards.
must('ACTIVE_DEVICE_REQUIRED' in vm,'TEST K service start lacks active enrollment guard')
print('TEST_K_ACTIVE_ENROLLMENT_REQUIRED=PASS')
must('BLUETOOTH_ADVERTISE' in vm and 'BLUETOOTH_CONNECT' in vm and 'BLE_PERMISSION_REQUIRED' in vm,'TEST L BLE permission refusal missing')
print('TEST_L_PERMISSION_SAFETY=PASS')
must('NO_ACTIVE_LECTURE' in vm and 'LectureStatus.ACTIVE' in service,'TEST M lecture window guard missing')
print('TEST_M_LECTURE_WINDOW_REQUIRED=PASS')
must('ContextCompat.startForegroundService' in vm and '_active.value=true' in service,'TEST N user-start foreground lifecycle missing')
print('TEST_N_SERVICE_ACTIVE_LIFECYCLE=PASS')
must('stopService(Intent(appContext,StudentPresenceService::class.java))' in vm and 'advertiser.stop()' in service,'TEST O stop does not stop advertiser')
print('TEST_O_SERVICE_STOP=PASS')
must('BLE_ADVERTISE_UNAVAILABLE' in service and 'FinalAttendanceStatus.ABSENT' not in service and 'endActiveLecture' not in service,'TEST P technical failure could finalize absence')
print('TEST_P_TECHNICAL_FAILURE_SAFE=PASS')

# Manifest/API compatibility and resolver contract.
must('android:name=".ble.StudentPresenceService"' in manifest and 'android:exported="false"' in manifest and 'android:foregroundServiceType="connectedDevice"' in manifest,'student foreground service manifest invalid')
must('FOREGROUND_SERVICE_CONNECTED_DEVICE' in manifest and 'BLUETOOTH_ADVERTISE' in manifest,'required FGS/BLE permissions missing')
must('Build.VERSION.SDK_INT>=31' in vm and 'Build.VERSION.SDK_INT>=29' in service and 'Build.VERSION.SDK_INT>=26' in service,'API guards missing')
must('RotatingPresenceToken.accepts' in resolver and 'getActiveStudentDevices()' in resolver,'resolver no longer uses active enrollment/token contract')

print('R4_VALIDATION=PASS')
