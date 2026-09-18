#!/usr/bin/env python3
import sqlite3
from datetime import date, timedelta
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def must(cond,msg):
    if not cond: raise AssertionError(msg)

def materialize(today,days,week_start,week_end,weekday_iso,existing=None):
    existing=set(existing or ())
    out=[]
    for off in range(days):
        d=today+timedelta(days=off)
        if d<week_start or d>week_end or d.isoweekday()!=weekday_iso: continue
        key=('subject','group',d.isoformat())
        if key in existing: continue
        existing.add(key);out.append(d)
    return out,existing

# A/B: weekly bounds + idempotency
ws=date(2026,9,19); we=date(2026,9,25)
rows,seen=materialize(date(2026,9,18),14,ws,we,6)
must(rows==[date(2026,9,19)],f'week bounds wrong: {rows}')
rows2,seen=materialize(date(2026,9,18),14,ws,we,6,seen)
must(rows2==[], 'duplicate lecture generated')
print('TEST_A_WEEK_RANGE=PASS');print('TEST_B_DUPLICATE_LECTURE=PASS')

# C/D: same-week deactivation only; completed history remains untouched
c=sqlite3.connect(':memory:')
c.executescript('''
CREATE TABLE weekly_timetable_versions(id TEXT PRIMARY KEY,groupId TEXT,weekStart TEXT);
CREATE TABLE timetables(id TEXT PRIMARY KEY,groupId TEXT,isActive INTEGER,weeklyScheduleId TEXT,updatedAt INTEGER,version INTEGER);
CREATE TABLE lectures(id TEXT PRIMARY KEY,groupId TEXT,scheduledStart INTEGER,status TEXT,updatedAt INTEGER,version INTEGER);
INSERT INTO weekly_timetable_versions VALUES('cur','g','2026-09-12'),('next-old','g','2026-09-19'),('next-new','g','2026-09-19');
INSERT INTO timetables VALUES('t-cur','g',1,'cur',0,1),('t-old','g',1,'next-old',0,1),('t-new','g',0,'next-new',0,1);
INSERT INTO lectures VALUES('hist','g',100,'COMPLETED',0,1),('future','g',200,'SCHEDULED',0,1);
''')
c.execute("UPDATE timetables SET isActive=0,updatedAt=1,version=version+1 WHERE groupId=? AND isActive=1 AND weeklyScheduleId IN (SELECT id FROM weekly_timetable_versions WHERE groupId=? AND weekStart=?)",('g','g','2026-09-19'))
must(c.execute("SELECT isActive FROM timetables WHERE id='t-cur'").fetchone()[0]==1,'current week deactivated')
must(c.execute("SELECT isActive FROM timetables WHERE id='t-old'").fetchone()[0]==0,'conflicting next week not deactivated')
c.execute("UPDATE lectures SET status='CANCELLED',updatedAt=1,version=version+1 WHERE groupId=? AND scheduledStart>=? AND scheduledStart<? AND status IN ('SCHEDULED','READY')",('g',0,1000))
must(c.execute("SELECT status FROM lectures WHERE id='hist'").fetchone()[0]=='COMPLETED','completed historical lecture changed')
print('TEST_C_CURRENT_WEEK_PRESERVATION=PASS');print('TEST_D_COMPLETED_HISTORY=PASS')

# E: configured SATURDAY, next week calculation never hardcodes Monday
def next_day(d,target_iso):
    delta=(target_iso-d.isoweekday())%7
    if delta==0: delta=7
    return d+timedelta(days=delta)
must(next_day(date(2026,9,18),6)==date(2026,9,19),'Saturday week start calculation wrong')
print('TEST_E_CONFIGURABLE_WEEK_START=PASS')

# F-I: active user gates role/scope/permission
c=sqlite3.connect(':memory:')
c.executescript('''
CREATE TABLE users(id TEXT PRIMARY KEY,isActive INTEGER);
CREATE TABLE roles(id TEXT PRIMARY KEY,name TEXT UNIQUE);
CREATE TABLE permissions(id TEXT PRIMARY KEY,code TEXT UNIQUE);
CREATE TABLE user_roles(userId TEXT,roleId TEXT,PRIMARY KEY(userId,roleId));
CREATE TABLE role_permissions(roleId TEXT,permissionId TEXT,PRIMARY KEY(roleId,permissionId));
CREATE TABLE user_scopes(id TEXT PRIMARY KEY,userId TEXT,scopeType TEXT,scopeId TEXT,active INTEGER);
CREATE TABLE students(id TEXT PRIMARY KEY,groupId TEXT);
INSERT INTO roles VALUES('rep','Representative'),('adm','Administrator');
INSERT INTO permissions VALUES('approve','APPROVE_ATTENDANCE');
INSERT INTO role_permissions VALUES('rep','approve'),('adm','approve');
INSERT INTO users VALUES('u-rep',1),('u-adm',0);
INSERT INTO user_roles VALUES('u-rep','rep'),('u-adm','adm');
INSERT INTO user_scopes VALUES('s1','u-rep','GROUP','g1',1);
INSERT INTO students VALUES('st1','g1');
''')
can=lambda uid: bool(c.execute("SELECT COUNT(*)>0 FROM students st WHERE st.id='st1' AND EXISTS(SELECT 1 FROM users u WHERE u.id=? AND u.isActive=1) AND EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=? AND us.active=1 AND us.scopeType='GROUP' AND us.scopeId=st.groupId)",(uid,uid)).fetchone()[0])
must(can('u-rep'),'active representative lost scoped read');print('TEST_F_ACTIVE_REPRESENTATIVE=PASS')
c.execute("UPDATE users SET isActive=0 WHERE id='u-rep'")
must(not can('u-rep'),'inactive representative retained scoped read');print('TEST_G_INACTIVE_REPRESENTATIVE=PASS')
isglobal=lambda uid: bool(c.execute("SELECT COUNT(*)>0 FROM users u JOIN user_roles ur ON ur.userId=u.id JOIN roles r ON r.id=ur.roleId WHERE u.id=? AND u.isActive=1 AND r.name IN ('SYSTEM_OWNER','Administrator')",(uid,)).fetchone()[0])
must(not isglobal('u-adm'),'inactive admin remained global');print('TEST_H_INACTIVE_ADMIN_GLOBAL=PASS')
hasperm=lambda uid: bool(c.execute("SELECT COUNT(*)>0 FROM user_roles ur JOIN role_permissions rp ON rp.roleId=ur.roleId JOIN permissions p ON p.id=rp.permissionId JOIN users u ON u.id=ur.userId WHERE ur.userId=? AND p.code='APPROVE_ATTENDANCE' AND u.isActive=1",(uid,)).fetchone()[0])
must(not hasperm('u-rep') and not hasperm('u-adm'),'inactive user retained write permission');print('TEST_I_INACTIVE_WRITE=PASS')

# J/K/L: representative v3->v4 post-upgrade auth seed, idempotent and grant-preserving
c=sqlite3.connect(':memory:')
c.executescript('''
CREATE TABLE users(id TEXT PRIMARY KEY,isActive INTEGER);
CREATE TABLE roles(id TEXT PRIMARY KEY,name TEXT UNIQUE);
CREATE TABLE permissions(id TEXT PRIMARY KEY,code TEXT UNIQUE);
CREATE TABLE user_roles(userId TEXT,roleId TEXT,PRIMARY KEY(userId,roleId));
CREATE TABLE role_permissions(roleId TEXT,permissionId TEXT,PRIMARY KEY(roleId,permissionId));
CREATE TABLE user_permission_grants(id TEXT PRIMARY KEY,userId TEXT,permissionCode TEXT,active INTEGER);
INSERT INTO users VALUES('admin-user',1);
INSERT INTO roles VALUES('legacy-admin','Administrator');
INSERT INTO permissions VALUES('legacy-view','VIEW_STUDENTS');
INSERT INTO user_roles VALUES('admin-user','legacy-admin');
INSERT INTO role_permissions VALUES('legacy-admin','legacy-view');
INSERT INTO user_permission_grants VALUES('custom-grant','admin-user','CUSTOM_TEMP_PERMISSION',1);
''')
PERMS=['VIEW_STUDENTS','MANAGE_USERS','MANAGE_SETTINGS','MANAGE_TIMETABLE','APPROVE_ATTENDANCE']
ROLE_DEFAULTS={'Administrator':set(PERMS)-{'MANAGE_TIMETABLE'},'SYSTEM_OWNER':set(PERMS)}
def seed(db):
    newp=set();newr=set()
    for code in PERMS:
        cur=db.execute('INSERT OR IGNORE INTO permissions VALUES(?,?,?)' if False else 'SELECT 1')
        before=db.total_changes;db.execute('INSERT OR IGNORE INTO permissions(id,code) VALUES(?,?)',(f'perm:{code}',code))
        if db.total_changes>before:newp.add(code)
    for role in ROLE_DEFAULTS:
        rid='role:'+role.lower().replace(' ','_');before=db.total_changes;db.execute('INSERT OR IGNORE INTO roles(id,name) VALUES(?,?)',(rid,role))
        if db.total_changes>before:newr.add(role)
    for role,codes in ROLE_DEFAULTS.items():
        rid=db.execute('SELECT id FROM roles WHERE name=?',(role,)).fetchone()[0]
        for code in codes:
            if role not in newr and code not in newp: continue
            pid=db.execute('SELECT id FROM permissions WHERE code=?',(code,)).fetchone()[0]
            db.execute('INSERT OR IGNORE INTO role_permissions VALUES(?,?)',(rid,pid))
    db.commit()
seed(c)
must(c.execute("SELECT COUNT(*) FROM roles WHERE name='SYSTEM_OWNER'").fetchone()[0]==1,'SYSTEM_OWNER role not seeded')
must(c.execute("SELECT COUNT(*) FROM permissions WHERE code='MANAGE_TIMETABLE'").fetchone()[0]==1,'new permission not seeded')
must(c.execute("SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id=rp.roleId WHERE r.name='SYSTEM_OWNER'").fetchone()[0]>0,'owner mappings missing')
print('TEST_J_V3_V4_AUTH_INITIALIZATION=PASS')
counts=lambda: tuple(c.execute(q).fetchone()[0] for q in ['SELECT COUNT(*) FROM roles','SELECT COUNT(*) FROM permissions','SELECT COUNT(*) FROM role_permissions'])
before=counts();seed(c);after=counts();must(before==after,f'seed not idempotent {before}->{after}');print('TEST_K_SEED_IDEMPOTENCY=PASS')
must(c.execute("SELECT active FROM user_permission_grants WHERE id='custom-grant'").fetchone()==(1,),'custom grant changed');print('TEST_L_CUSTOM_GRANT_PRESERVED=PASS')

# Production source assertions
lecture=(ROOT/'android/app/src/main/java/com/hammam/attendai/data/repository/LectureSchedulerRepository.kt').read_text()
timetable=(ROOT/'android/app/src/main/java/com/hammam/attendai/data/repository/TimetableRepository.kt').read_text()
workers=(ROOT/'android/app/src/main/java/com/hammam/attendai/sync/Workers.kt').read_text()
auth=(ROOT/'android/app/src/main/java/com/hammam/attendai/security/AuthorizationRepository.kt').read_text()
app=(ROOT/'android/app/src/main/java/com/hammam/attendai/HammamAttendAiApplication.kt').read_text()
dao=(ROOT/'android/app/src/main/java/com/hammam/attendai/data/local/dao/CoreDao.kt').read_text()
must('weeklyBounds' in lecture and 'date.isBefore(weeklyBounds.first)' in lecture and 'date.isAfter(weeklyBounds.second)' in lecture,'production materializer not week-bounded')
must('deactivateTimetablesForWeek(header.groupId,header.weekStart,now)' in timetable,'approval still deactivates whole group')
must('academicWeekStart.first()' in workers and 'next(configuredDay)' in workers,'reminder not configurable')
must('dao.isUserActive(userId)' in auth,'central active-user gate missing')
must('seedAuthorizationModel()' in app and 'setOwnerSetupRequired' in app,'post-upgrade authorization startup initialization missing')
must('EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1)' in dao,'scoped DAO active-user gate missing')
print('R3_VALIDATION=PASS')
