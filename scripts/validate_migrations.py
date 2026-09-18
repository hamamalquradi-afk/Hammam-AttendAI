#!/usr/bin/env python3
"""Representative SQLite migration validation for Hammam AttendAI v1->v4.
This validates migration SQL/data preservation without Android/Room runtime; Room compilation remains a later Gradle check.
"""
import sqlite3, sys

M12=["ALTER TABLE teacher_report_settings ADD COLUMN sendIfNoLecture INTEGER NOT NULL DEFAULT 0"]
M34=[
'''CREATE TABLE IF NOT EXISTS user_scopes (id TEXT NOT NULL PRIMARY KEY,userId TEXT NOT NULL,scopeType TEXT NOT NULL,scopeId TEXT,active INTEGER NOT NULL,createdAt INTEGER NOT NULL,createdBy TEXT,FOREIGN KEY(userId) REFERENCES users(id) ON UPDATE NO ACTION ON DELETE RESTRICT)''',
"CREATE INDEX IF NOT EXISTS index_user_scopes_userId ON user_scopes(userId)",
"CREATE INDEX IF NOT EXISTS index_user_scopes_scopeType_scopeId ON user_scopes(scopeType,scopeId)",
"CREATE INDEX IF NOT EXISTS index_user_scopes_active ON user_scopes(active)",
'''CREATE TABLE IF NOT EXISTS user_permission_grants (id TEXT NOT NULL PRIMARY KEY,userId TEXT NOT NULL,permissionCode TEXT NOT NULL,scopeType TEXT,scopeId TEXT,startsAt INTEGER,expiresAt INTEGER,grantedBy TEXT NOT NULL,createdAt INTEGER NOT NULL,reason TEXT NOT NULL,active INTEGER NOT NULL,revokedAt INTEGER,FOREIGN KEY(userId) REFERENCES users(id) ON UPDATE NO ACTION ON DELETE RESTRICT)''',
"CREATE INDEX IF NOT EXISTS index_user_permission_grants_userId ON user_permission_grants(userId)",
"CREATE INDEX IF NOT EXISTS index_user_permission_grants_permissionCode ON user_permission_grants(permissionCode)",
"CREATE INDEX IF NOT EXISTS index_user_permission_grants_scopeType_scopeId ON user_permission_grants(scopeType,scopeId)",
"CREATE INDEX IF NOT EXISTS index_user_permission_grants_active ON user_permission_grants(active)",
"CREATE INDEX IF NOT EXISTS index_user_permission_grants_expiresAt ON user_permission_grants(expiresAt)",
'''CREATE TABLE IF NOT EXISTS device_replacement_requests (id TEXT NOT NULL PRIMARY KEY,studentId TEXT NOT NULL,oldDeviceId TEXT,newDevicePublicId TEXT NOT NULL,newPublicKey TEXT,newPresenceSecretCiphertext TEXT,status TEXT NOT NULL,requestedAt INTEGER NOT NULL,reviewedBy TEXT,reviewedAt INTEGER,decisionNote TEXT,version INTEGER NOT NULL,FOREIGN KEY(studentId) REFERENCES students(id) ON UPDATE NO ACTION ON DELETE RESTRICT)''',
"CREATE INDEX IF NOT EXISTS index_device_replacement_requests_studentId ON device_replacement_requests(studentId)",
"CREATE INDEX IF NOT EXISTS index_device_replacement_requests_status ON device_replacement_requests(status)",
"CREATE INDEX IF NOT EXISTS index_device_replacement_requests_requestedAt ON device_replacement_requests(requestedAt)",
'''CREATE TABLE IF NOT EXISTS weekly_timetable_versions (id TEXT NOT NULL PRIMARY KEY,groupId TEXT NOT NULL,weekStart TEXT NOT NULL,weekEnd TEXT NOT NULL,versionNumber INTEGER NOT NULL,status TEXT NOT NULL,sourceType TEXT NOT NULL,sourceUri TEXT,importedBy TEXT NOT NULL,approvedBy TEXT,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,approvedAt INTEGER,reason TEXT)''',
"CREATE INDEX IF NOT EXISTS index_weekly_timetable_versions_groupId ON weekly_timetable_versions(groupId)",
"CREATE INDEX IF NOT EXISTS index_weekly_timetable_versions_weekStart ON weekly_timetable_versions(weekStart)",
"CREATE INDEX IF NOT EXISTS index_weekly_timetable_versions_status ON weekly_timetable_versions(status)",
"CREATE UNIQUE INDEX IF NOT EXISTS index_weekly_timetable_versions_groupId_weekStart_versionNumber ON weekly_timetable_versions(groupId,weekStart,versionNumber)",
"ALTER TABLE timetables ADD COLUMN weeklyScheduleId TEXT",
"CREATE INDEX IF NOT EXISTS index_timetables_weeklyScheduleId ON timetables(weeklyScheduleId)",
"ALTER TABLE audit_logs ADD COLUMN actorRole TEXT",
"CREATE INDEX IF NOT EXISTS index_audit_logs_entityType_entityId_timestamp ON audit_logs(entityType,entityId,timestamp)",
]

def cols(db,table): return [r[1] for r in db.execute(f'PRAGMA table_info({table})')]
def idx(db,table): return {r[1] for r in db.execute(f'PRAGMA index_list({table})')}

def create_base(version:int,with_data=True):
    db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
    db.executescript('''
    CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT UNIQUE,displayName TEXT,isActive INTEGER,createdAt INTEGER,updatedAt INTEGER,version INTEGER);
    CREATE TABLE roles(id TEXT PRIMARY KEY,name TEXT UNIQUE,description TEXT);
    CREATE TABLE permissions(id TEXT PRIMARY KEY,code TEXT UNIQUE,description TEXT);
    CREATE TABLE user_roles(userId TEXT,roleId TEXT,PRIMARY KEY(userId,roleId),FOREIGN KEY(userId) REFERENCES users(id),FOREIGN KEY(roleId) REFERENCES roles(id));
    CREATE TABLE role_permissions(roleId TEXT,permissionId TEXT,PRIMARY KEY(roleId,permissionId),FOREIGN KEY(roleId) REFERENCES roles(id),FOREIGN KEY(permissionId) REFERENCES permissions(id));
    CREATE TABLE students(id TEXT PRIMARY KEY,universityNumber TEXT UNIQUE,fullName TEXT);
    CREATE TABLE subjects(id TEXT PRIMARY KEY,name TEXT);
    CREATE TABLE lectures(id TEXT PRIMARY KEY,subjectId TEXT NOT NULL,FOREIGN KEY(subjectId) REFERENCES subjects(id));
    CREATE TABLE attendance_records(id TEXT PRIMARY KEY,lectureId TEXT NOT NULL,studentId TEXT NOT NULL,finalStatus TEXT,attendancePercentage REAL,FOREIGN KEY(lectureId) REFERENCES lectures(id),FOREIGN KEY(studentId) REFERENCES students(id));
    CREATE TABLE report_jobs(id TEXT PRIMARY KEY,status TEXT,deduplicationKey TEXT UNIQUE);
    CREATE TABLE app_settings(key TEXT PRIMARY KEY,valueCiphertext TEXT,updatedAt INTEGER);
    CREATE TABLE timetables(id TEXT PRIMARY KEY,subjectId TEXT,teacherId TEXT,groupId TEXT,dayOfWeek INTEGER,startTime TEXT,endTime TEXT);
    CREATE TABLE audit_logs(id TEXT PRIMARY KEY,actorId TEXT,action TEXT,entityType TEXT,entityId TEXT,oldData TEXT,newData TEXT,reason TEXT,timestamp INTEGER);
    ''')
    if version==1:
        db.execute('''CREATE TABLE teacher_report_settings(id TEXT PRIMARY KEY,teacherId TEXT,subjectId TEXT,enabled INTEGER,frequency TEXT,sendTime TEXT,weeklyDay INTEGER,monthlyDay INTEGER,semesterReportEnabled INTEGER,customRule TEXT,timezone TEXT,channel TEXT,reportFormat TEXT,includeStudentDetails INTEGER,requireApproval INTEGER,aiSummaryEnabled INTEGER,updatedAt INTEGER,version INTEGER)''')
    else:
        db.execute('''CREATE TABLE teacher_report_settings(id TEXT PRIMARY KEY,teacherId TEXT,subjectId TEXT,enabled INTEGER,frequency TEXT,sendTime TEXT,weeklyDay INTEGER,monthlyDay INTEGER,semesterReportEnabled INTEGER,customRule TEXT,timezone TEXT,channel TEXT,reportFormat TEXT,includeStudentDetails INTEGER,requireApproval INTEGER,aiSummaryEnabled INTEGER,sendIfNoLecture INTEGER NOT NULL DEFAULT 0,updatedAt INTEGER,version INTEGER)''')
    if version<=2:
        db.execute('''CREATE TABLE attendance_appeals(id TEXT PRIMARY KEY,studentId TEXT NOT NULL,attendanceRecordId TEXT NOT NULL,reason TEXT NOT NULL,attachmentPath TEXT,status TEXT NOT NULL,submittedAt INTEGER NOT NULL,reviewedBy TEXT,reviewedAt INTEGER,decisionNote TEXT)''')
    else:
        db.execute('''CREATE TABLE attendance_appeals(id TEXT NOT NULL PRIMARY KEY,studentId TEXT NOT NULL,attendanceRecordId TEXT NOT NULL,lectureId TEXT NOT NULL,subjectId TEXT NOT NULL,reasonType TEXT NOT NULL,description TEXT NOT NULL,attachmentLocalUri TEXT,attachmentRemoteUrl TEXT,status TEXT NOT NULL,submittedAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,reviewedBy TEXT,reviewedAt INTEGER,decisionNote TEXT,syncStatus TEXT NOT NULL,version INTEGER NOT NULL,FOREIGN KEY(studentId) REFERENCES students(id) ON DELETE RESTRICT,FOREIGN KEY(attendanceRecordId) REFERENCES attendance_records(id) ON DELETE RESTRICT,FOREIGN KEY(lectureId) REFERENCES lectures(id) ON DELETE RESTRICT,FOREIGN KEY(subjectId) REFERENCES subjects(id) ON DELETE RESTRICT)''')
        for n,c in [('studentId','studentId'),('attendanceRecordId','attendanceRecordId'),('lectureId','lectureId'),('subjectId','subjectId'),('status','status'),('syncStatus','syncStatus'),('submittedAt','submittedAt')]: db.execute(f'CREATE INDEX index_attendance_appeals_{n} ON attendance_appeals({c})')
    if with_data:
        db.execute("INSERT INTO users VALUES('u1','owner','Owner',1,1,1,1)");db.execute("INSERT INTO roles VALUES('r1','SYSTEM_OWNER',NULL)");db.execute("INSERT INTO permissions VALUES('p1','VIEW_STUDENTS',NULL)");db.execute("INSERT INTO user_roles VALUES('u1','r1')");db.execute("INSERT INTO role_permissions VALUES('r1','p1')")
        db.execute("INSERT INTO students VALUES('s1','2026001','Student One')");db.execute("INSERT INTO subjects VALUES('sub1','Anatomy')");db.execute("INSERT INTO lectures VALUES('l1','sub1')");db.execute("INSERT INTO attendance_records VALUES('ar1','l1','s1','PRESENT',100.0)")
        db.execute("INSERT INTO report_jobs VALUES('rp1','SENT','dedup-1')");db.execute("INSERT INTO app_settings VALUES('setting1','ciphertext',100)");db.execute("INSERT INTO timetables VALUES('tt1','sub1','t1','g1',1,'08:00','09:00')");db.execute("INSERT INTO audit_logs VALUES('au1','u1','TEST','Student','s1',NULL,NULL,NULL,100)")
        if version==1: db.execute("INSERT INTO teacher_report_settings VALUES('trs1','t1','sub1',1,'WEEKLY','18:00',7,NULL,0,NULL,'Asia/Aden','EMAIL','PDF',1,1,0,100,1)")
        else: db.execute("INSERT INTO teacher_report_settings VALUES('trs1','t1','sub1',1,'WEEKLY','18:00',7,NULL,0,NULL,'Asia/Aden','EMAIL','PDF',1,1,0,0,100,1)")
        if version<=2: db.execute("INSERT INTO attendance_appeals VALUES('ap1','s1','ar1','Incorrect status',NULL,'PENDING',100,NULL,NULL,NULL)")
        else: db.execute("INSERT INTO attendance_appeals VALUES('ap1','s1','ar1','l1','sub1','OTHER','Incorrect status',NULL,NULL,'PENDING',100,100,NULL,NULL,NULL,'LOCAL_ONLY',1)")
    db.commit(); return db

def m12(db):
    for s in M12: db.execute(s)

def m23(db):
    legacy=db.execute('SELECT COUNT(*) FROM attendance_appeals').fetchone()[0]
    resolvable=db.execute('''SELECT COUNT(*) FROM attendance_appeals aa JOIN attendance_records ar ON ar.id=aa.attendanceRecordId JOIN lectures l ON l.id=ar.lectureId JOIN subjects s ON s.id=l.subjectId JOIN students st ON st.id=aa.studentId''').fetchone()[0]
    assert legacy==resolvable,'orphan legacy appeal would be lost'
    db.execute('ALTER TABLE attendance_appeals RENAME TO attendance_appeals_legacy')
    db.execute('''CREATE TABLE attendance_appeals(id TEXT NOT NULL PRIMARY KEY,studentId TEXT NOT NULL,attendanceRecordId TEXT NOT NULL,lectureId TEXT NOT NULL,subjectId TEXT NOT NULL,reasonType TEXT NOT NULL,description TEXT NOT NULL,attachmentLocalUri TEXT,attachmentRemoteUrl TEXT,status TEXT NOT NULL,submittedAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,reviewedBy TEXT,reviewedAt INTEGER,decisionNote TEXT,syncStatus TEXT NOT NULL,version INTEGER NOT NULL,FOREIGN KEY(studentId) REFERENCES students(id) ON DELETE RESTRICT,FOREIGN KEY(attendanceRecordId) REFERENCES attendance_records(id) ON DELETE RESTRICT,FOREIGN KEY(lectureId) REFERENCES lectures(id) ON DELETE RESTRICT,FOREIGN KEY(subjectId) REFERENCES subjects(id) ON DELETE RESTRICT)''')
    db.execute('''INSERT INTO attendance_appeals SELECT aa.id,aa.studentId,aa.attendanceRecordId,ar.lectureId,l.subjectId,'OTHER',aa.reason,aa.attachmentPath,NULL,aa.status,aa.submittedAt,COALESCE(aa.reviewedAt,aa.submittedAt),aa.reviewedBy,aa.reviewedAt,aa.decisionNote,'LOCAL_ONLY',1 FROM attendance_appeals_legacy aa JOIN attendance_records ar ON ar.id=aa.attendanceRecordId JOIN lectures l ON l.id=ar.lectureId JOIN subjects s ON s.id=l.subjectId JOIN students st ON st.id=aa.studentId''')
    db.execute('DROP TABLE attendance_appeals_legacy')
    for n in ['studentId','attendanceRecordId','lectureId','subjectId','status','syncStatus','submittedAt']: db.execute(f'CREATE INDEX IF NOT EXISTS index_attendance_appeals_{n} ON attendance_appeals({n})')

def m34(db):
    for s in M34: db.execute(s)

def verify(db,label):
    # Historical rows/settings preserved
    assert db.execute("SELECT universityNumber FROM students WHERE id='s1'").fetchone()==('2026001',)
    assert db.execute("SELECT finalStatus,attendancePercentage FROM attendance_records WHERE id='ar1'").fetchone()==('PRESENT',100.0)
    ap=db.execute("SELECT lectureId,subjectId,description,status,syncStatus,version FROM attendance_appeals WHERE id='ap1'").fetchone();assert ap==('l1','sub1','Incorrect status','PENDING','LOCAL_ONLY',1),ap
    assert db.execute("SELECT status FROM report_jobs WHERE id='rp1'").fetchone()==('SENT',)
    assert db.execute("SELECT name FROM roles WHERE id='r1'").fetchone()==('SYSTEM_OWNER',)
    assert db.execute("SELECT code FROM permissions WHERE id='p1'").fetchone()==('VIEW_STUDENTS',)
    assert db.execute("SELECT valueCiphertext FROM app_settings WHERE key='setting1'").fetchone()==('ciphertext',)
    assert db.execute("SELECT sendIfNoLecture FROM teacher_report_settings WHERE id='trs1'").fetchone()==(0,)
    assert 'weeklyScheduleId' in cols(db,'timetables') and db.execute("SELECT weeklyScheduleId FROM timetables WHERE id='tt1'").fetchone()==(None,)
    assert 'actorRole' in cols(db,'audit_logs') and db.execute("SELECT actorRole FROM audit_logs WHERE id='au1'").fetchone()==(None,)
    for table in ['user_scopes','user_permission_grants','device_replacement_requests','weekly_timetable_versions']: assert table in {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    expected_indexes={
        'user_scopes':{'index_user_scopes_userId','index_user_scopes_scopeType_scopeId','index_user_scopes_active'},
        'user_permission_grants':{'index_user_permission_grants_userId','index_user_permission_grants_permissionCode','index_user_permission_grants_scopeType_scopeId','index_user_permission_grants_active','index_user_permission_grants_expiresAt'},
        'device_replacement_requests':{'index_device_replacement_requests_studentId','index_device_replacement_requests_status','index_device_replacement_requests_requestedAt'},
        'weekly_timetable_versions':{'index_weekly_timetable_versions_groupId','index_weekly_timetable_versions_weekStart','index_weekly_timetable_versions_status','index_weekly_timetable_versions_groupId_weekStart_versionNumber'},
        'timetables':{'index_timetables_weeklyScheduleId'},'audit_logs':{'index_audit_logs_entityType_entityId_timestamp'}}
    for table,want in expected_indexes.items(): assert want<=idx(db,table),(label,table,want-idx(db,table))
    assert db.execute('PRAGMA foreign_key_check').fetchall()==[],(label,db.execute('PRAGMA foreign_key_check').fetchall())
    assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'

def run_path(start):
    db=create_base(start,True)
    if start==1:m12(db);m23(db);m34(db)
    elif start==2:m23(db);m34(db)
    elif start==3:m34(db)
    else:raise ValueError(start)
    db.commit();verify(db,f'v{start}->v4');db.close();print(f'MIGRATION_PATH_{start}_TO_4=PASS')

def fresh_v4():
    # A fresh representative v4 database uses the current final column/table/index set directly.
    db=create_base(3,False);m34(db);db.commit()
    # Seed only validation rows after schema exists, then verify FK acceptance/defaults.
    db.execute("INSERT INTO users VALUES('u1','owner','Owner',1,1,1,1)");db.execute("INSERT INTO students VALUES('s1','2026001','Student One')")
    db.execute("INSERT INTO user_scopes VALUES('us1','u1','STUDENT','s1',1,1,'u1')")
    db.execute("INSERT INTO device_replacement_requests VALUES('dr1','s1',NULL,'new-device',NULL,NULL,'PENDING',1,NULL,NULL,NULL,1)")
    db.commit();assert db.execute('PRAGMA foreign_key_check').fetchall()==[];assert 'weeklyScheduleId' in cols(db,'timetables');assert 'actorRole' in cols(db,'audit_logs');db.close();print('FRESH_V4_REPRESENTATIVE_SCHEMA=PASS')

def main():
    fresh_v4()
    for start in (1,2,3):run_path(start)
    print('DATABASE_FINAL_VALIDATION=PASS room_runtime_compile=DEFERRED_TO_GRADLE')
if __name__=='__main__':main()
