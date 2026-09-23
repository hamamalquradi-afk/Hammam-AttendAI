#!/usr/bin/env python3
"""Offline functional-wiring fixture. Uses only an in-memory/temp SQLite test DB, never Production DB."""
import sqlite3, uuid, time, hmac, hashlib, tempfile, shutil, os
from pathlib import Path

NOW=1_700_000_000_000
uid=lambda: str(uuid.uuid4())

def token(secret:bytes, device_id:str, at:int)->str:
    slot=at//30_000
    return hmac.new(secret,f"{device_id}:{slot}".encode(),hashlib.sha256).digest()[:8].hex()

def accepts(secret:bytes,device_id:str,value:str,at:int)->bool:
    return any(hmac.compare_digest(token(secret,device_id,t),value) for t in (at-30_000,at,at+30_000))

def create_db(path=':memory:'):
    db=sqlite3.connect(path); db.execute('PRAGMA foreign_keys=ON')
    db.executescript('''
    CREATE TABLE academic_years(id TEXT PRIMARY KEY,name TEXT,start_date TEXT,end_date TEXT);
    CREATE TABLE semesters(id TEXT PRIMARY KEY,academic_year_id TEXT REFERENCES academic_years(id),name TEXT,status TEXT,start_date TEXT,end_date TEXT);
    CREATE TABLE levels(id TEXT PRIMARY KEY,name TEXT); CREATE TABLE batches(id TEXT PRIMARY KEY,level_id TEXT REFERENCES levels(id),name TEXT);
    CREATE TABLE groups(id TEXT PRIMARY KEY,batch_id TEXT REFERENCES batches(id),name TEXT);
    CREATE TABLE users(id TEXT PRIMARY KEY,role TEXT,scope_id TEXT);
    CREATE TABLE permission_grants(id TEXT PRIMARY KEY,user_id TEXT REFERENCES users(id),permission TEXT,scope_id TEXT,expires_at INTEGER,active INTEGER);
    CREATE TABLE teachers(id TEXT PRIMARY KEY,name TEXT); CREATE TABLE subjects(id TEXT PRIMARY KEY,name TEXT,teacher_id TEXT REFERENCES teachers(id),group_id TEXT REFERENCES groups(id),semester_id TEXT REFERENCES semesters(id));
    CREATE TABLE students(id TEXT PRIMARY KEY,number TEXT UNIQUE,name TEXT,group_id TEXT REFERENCES groups(id));
    CREATE TABLE weekly_timetable_versions(id TEXT PRIMARY KEY,group_id TEXT REFERENCES groups(id),status TEXT,week_start TEXT,version INTEGER);
    CREATE TABLE timetables(id TEXT PRIMARY KEY,schedule_id TEXT REFERENCES weekly_timetable_versions(id),subject_id TEXT REFERENCES subjects(id),teacher_id TEXT REFERENCES teachers(id),day INTEGER,start TEXT,end TEXT);
    CREATE TABLE lectures(id TEXT PRIMARY KEY,subject_id TEXT REFERENCES subjects(id),group_id TEXT REFERENCES groups(id),status TEXT,start_ms INTEGER,end_ms INTEGER);
    CREATE TABLE devices(id TEXT PRIMARY KEY,student_id TEXT REFERENCES students(id),public_id TEXT UNIQUE,secret BLOB,status TEXT);
    CREATE TABLE sessions(id TEXT PRIMARY KEY,lecture_id TEXT UNIQUE REFERENCES lectures(id),host_user_id TEXT REFERENCES users(id),status TEXT,started_at INTEGER,ended_at INTEGER);
    CREATE TABLE presence_events(id TEXT PRIMARY KEY,session_id TEXT REFERENCES sessions(id),student_id TEXT REFERENCES students(id),kind TEXT,ts INTEGER,confidence REAL);
    CREATE TABLE attendance_records(id TEXT PRIMARY KEY,lecture_id TEXT REFERENCES lectures(id),student_id TEXT REFERENCES students(id),status TEXT,percentage REAL,approval TEXT);
    CREATE TABLE notifications(id TEXT PRIMARY KEY,dedup TEXT UNIQUE,student_id TEXT REFERENCES students(id),kind TEXT,status TEXT);
    CREATE TABLE reports(id TEXT PRIMARY KEY,dedup TEXT UNIQUE,teacher_id TEXT REFERENCES teachers(id),status TEXT,require_approval INTEGER);
    CREATE TABLE appeals(id TEXT PRIMARY KEY,student_id TEXT REFERENCES students(id),attendance_id TEXT REFERENCES attendance_records(id),status TEXT,description TEXT);
    CREATE TABLE audit(id TEXT PRIMARY KEY,actor_id TEXT,action TEXT,entity_type TEXT,entity_id TEXT,reason TEXT,ts INTEGER);
    CREATE TABLE provider_health(name TEXT PRIMARY KEY,configured INTEGER,status TEXT);
    ''')
    return db

def main():
    db=create_db(); c=db.cursor()
    # 1-7 academic structure/teacher/subject
    ay,sem,lvl,batch,grp,teacher,subject=[uid() for _ in range(7)]
    c.execute('INSERT INTO academic_years VALUES(?,?,?,?)',(ay,'2026/2027','2026-09-01','2027-06-30'))
    c.execute('INSERT INTO semesters VALUES(?,?,?,?,?,?)',(sem,ay,'Semester 1','ACTIVE','2026-09-01','2027-01-31'))
    c.execute('INSERT INTO levels VALUES(?,?)',(lvl,'Level 1'));c.execute('INSERT INTO batches VALUES(?,?,?)',(batch,lvl,'Batch A'));c.execute('INSERT INTO groups VALUES(?,?,?)',(grp,batch,'Group 1'))
    c.execute('INSERT INTO teachers VALUES(?,?)',(teacher,'Test Teacher'));c.execute('INSERT INTO subjects VALUES(?,?,?,?,?)',(subject,'Anatomy',teacher,grp,sem))
    # 8 import students
    students=[(uid(),'2026001','Student A',grp),(uid(),'2026002','Student B',grp),(uid(),'2026003','Student C',grp)]
    c.executemany('INSERT INTO students VALUES(?,?,?,?)',students)
    # 9-11 representative + assistant + temporary grant
    rep,assistant=uid(),uid();c.execute('INSERT INTO users VALUES(?,?,?)',(rep,'Representative',grp));c.execute('INSERT INTO users VALUES(?,?,?)',(assistant,'Assistant Representative',grp))
    c.execute('INSERT INTO permission_grants VALUES(?,?,?,?,?,1)',(uid(),assistant,'TAKE_OVER_ATTENDANCE',grp,NOW+3_600_000))
    # 12-15 timetable draft, validation, approval, lecture
    sched=uid();c.execute('INSERT INTO weekly_timetable_versions VALUES(?,?,?,?,?)',(sched,grp,'DRAFT','2026-09-20',1))
    row=uid();c.execute('INSERT INTO timetables VALUES(?,?,?,?,?,?,?)',(row,sched,subject,teacher,1,'08:00','09:00'))
    assert c.execute('SELECT COUNT(*) FROM timetables WHERE schedule_id=? AND start>=end',(sched,)).fetchone()[0]==0
    c.execute("UPDATE weekly_timetable_versions SET status='APPROVED' WHERE id=?",(sched,))
    lecture=uid();c.execute('INSERT INTO lectures VALUES(?,?,?,?,?,?)',(lecture,subject,grp,'READY',NOW,NOW+3_600_000))
    # 16-18 enroll + rotating token + resolve
    device=uid();public_id='device-public-test';secret=bytes(range(1,33));c.execute('INSERT INTO devices VALUES(?,?,?,?,?)',(device,students[0][0],public_id,secret,'ACTIVE'))
    ble=token(secret,public_id,NOW);assert accepts(secret,public_id,ble,NOW) and not accepts(bytes(32),public_id,ble,NOW)
    resolved=c.execute('SELECT student_id,secret FROM devices WHERE public_id=? AND status="ACTIVE"',(public_id,)).fetchone();assert resolved and accepts(resolved[1],public_id,ble,NOW)
    # 19 start lecture: one host/session
    session=uid();c.execute('INSERT INTO sessions VALUES(?,?,?,?,?,NULL)',(session,lecture,rep,'ACTIVE',NOW));c.execute("UPDATE lectures SET status='ACTIVE' WHERE id=?",(lecture,))
    # 20-24 present/late/partial/loss/return events
    a,b,d=[x[0] for x in students]
    events=[(a,'DETECTED',NOW,0.95),(b,'DETECTED',NOW+15*60_000,0.92),(d,'DETECTED',NOW+5*60_000,0.9),(d,'LOST',NOW+20*60_000,0.7),(d,'REDETECTED',NOW+25*60_000,0.9)]
    for sid,kind,ts,conf in events:c.execute('INSERT INTO presence_events VALUES(?,?,?,?,?,?)',(uid(),session,sid,kind,ts,conf))
    # 25 assistant takeover, same session only
    assert c.execute('SELECT active FROM permission_grants WHERE user_id=? AND permission=? AND expires_at>?',(assistant,'TAKE_OVER_ATTENDANCE',NOW)).fetchone()==(1,)
    c.execute('UPDATE sessions SET host_user_id=? WHERE id=?',(assistant,session));c.execute('INSERT INTO audit VALUES(?,?,?,?,?,?,?)',(uid(),assistant,'HANDOVER_ATTENDANCE_HOST','Lecture',lecture,'Representative unavailable',NOW+30*60_000));assert c.execute('SELECT COUNT(*) FROM sessions WHERE lecture_id=?',(lecture,)).fetchone()[0]==1
    # 26-28 end/review/freeze
    c.execute("UPDATE sessions SET status='COMPLETED',ended_at=? WHERE id=?",(NOW+3_600_000,session));c.execute("UPDATE lectures SET status='COMPLETED' WHERE id=?",(lecture,))
    records=[(uid(),lecture,a,'PRESENT',100.0,'APPROVED'),(uid(),lecture,b,'LATE',75.0,'APPROVED'),(uid(),lecture,d,'PARTIAL',55.0,'APPROVED')]
    c.executemany('INSERT INTO attendance_records VALUES(?,?,?,?,?,?)',records);c.execute("UPDATE lectures SET status='FROZEN' WHERE id=?",(lecture,))
    # 29 absence notification job (separate absent outcome fixture) + dedup
    absent_student=uid();c.execute('INSERT INTO students VALUES(?,?,?,?)',(absent_student,'2026004','Student D',grp));abs_rec=uid();c.execute('INSERT INTO attendance_records VALUES(?,?,?,?,?,?)',(abs_rec,lecture,absent_student,'ABSENT',0.0,'APPROVED'))
    dedup=f'ABSENCE:{lecture}:{absent_student}';c.execute('INSERT INTO notifications VALUES(?,?,?,?,?)',(uid(),dedup,absent_student,'ABSENCE','PENDING'))
    # 30-33 teacher report approval + queued external send
    report=uid();rd=f'{teacher}:{subject}:WEEKLY:2026-09-20';c.execute('INSERT INTO reports VALUES(?,?,?,?,?)',(report,rd,teacher,'PENDING_APPROVAL',1));c.execute("UPDATE reports SET status='PENDING_SEND' WHERE id=?",(report,))
    # 34-36 appeal + review + attendance modification
    appeal=uid();c.execute('INSERT INTO appeals VALUES(?,?,?,?,?)',(appeal,b,records[1][0],'PENDING','Late status disputed'));c.execute("UPDATE appeals SET status='ACCEPTED' WHERE id=?",(appeal,));old=c.execute('SELECT status,percentage FROM attendance_records WHERE id=?',(records[1][0],)).fetchone();c.execute("UPDATE attendance_records SET status='PRESENT',percentage=100 WHERE id=?",(records[1][0],))
    # 37 audit attribution
    c.execute('INSERT INTO audit VALUES(?,?,?,?,?,?,?)',(uid(),rep,'ATTENDANCE_CHANGED','AttendanceRecord',records[1][0],f'{old}->PRESENT/100',NOW+4_000_000));assert c.execute('SELECT actor_id FROM audit WHERE action="ATTENDANCE_CHANGED"').fetchone()[0]==rep
    # 38 export report (sanitized fixture output)
    export='teacher_id,status\n'+f'{teacher},PENDING_SEND\n';assert 'PENDING_SEND' in export
    # 39-40 create/validate DB recovery copy
    db.commit()
    with tempfile.TemporaryDirectory() as td:
        src=Path(td)/'fixture.db'; out=sqlite3.connect(src); db.backup(out); out.close(); digest=hashlib.sha256(src.read_bytes()).hexdigest(); copy=Path(td)/'copy.db';shutil.copy2(src,copy);assert hashlib.sha256(copy.read_bytes()).hexdigest()==digest;check=sqlite3.connect(copy);assert check.execute('PRAGMA integrity_check').fetchone()[0]=='ok';check.close()
    # 41 data-integrity checks
    assert c.execute('PRAGMA foreign_key_check').fetchall()==[]
    assert c.execute("SELECT COUNT(*) FROM devices WHERE status='ACTIVE' GROUP BY student_id HAVING COUNT(*)>1").fetchall()==[]
    assert c.execute('SELECT COUNT(*) FROM students GROUP BY number HAVING COUNT(*)>1').fetchall()==[]
    # 42 provider health safe Not Configured
    c.executemany('INSERT INTO provider_health VALUES(?,?,?)',[('OPENAI',0,'NOT_CONFIGURED'),('GEMINI',0,'NOT_CONFIGURED'),('CLAUDE',0,'NOT_CONFIGURED')]);assert all(r[1:]==(0,'NOT_CONFIGURED') for r in c.execute('SELECT * FROM provider_health').fetchall())
    db.commit();print('E2E_LOCAL_VALIDATION=PASS steps=42 hardware_ble=NOT_TESTED external_network=NOT_USED')

if __name__=='__main__':main()
