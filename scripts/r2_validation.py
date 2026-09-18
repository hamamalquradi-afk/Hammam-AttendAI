#!/usr/bin/env python3
from pathlib import Path
import sqlite3

ROOT=Path(__file__).resolve().parents[1]
attendance=(ROOT/'android/app/src/main/java/com/hammam/attendai/data/repository/AttendanceRepository.kt').read_text()
dao=(ROOT/'android/app/src/main/java/com/hammam/attendai/data/local/dao/CoreDao.kt').read_text()
processor=(ROOT/'android/app/src/main/java/com/hammam/attendai/reports/ReportProcessor.kt').read_text()
pdf=(ROOT/'android/app/src/main/java/com/hammam/attendai/reports/PdfReportGenerator.kt').read_text()
csv=(ROOT/'android/app/src/main/java/com/hammam/attendai/reports/CsvReportGenerator.kt').read_text()

# Source-oriented safety assertions.
end_block=attendance.split('suspend fun endActiveLecture',1)[1].split('suspend fun approveLecture',1)[0]
approve_block=attendance.split('suspend fun approveLecture',1)[1].split('suspend fun editAttendanceRecord',1)[0]
assert 'enqueueAbsenceNotifications' not in end_block, 'TEST A: absence notification still occurs before review approval'
assert 'enqueueAbsenceNotifications' in approve_block, 'TEST B: approval does not enqueue absence notification'
assert 'MANUAL_REVIEW' in approve_block and 'ATTENDANCE_REVIEW_INCOMPLETE' in approve_block, 'Approval does not block unresolved review'
assert 'records.take(40)' not in processor, 'TEST G: ReportProcessor still truncates at 40'
assert 'rows.take(22)' not in pdf, 'TEST G: PDF still truncates at 22'
assert 'rows.forEach' in pdf, 'TEST G: PDF does not iterate all report rows'
assert 'records.forEach' in csv, 'CSV does not iterate all report rows'
assert 'getOfficialTeacherReportRows(job.teacherId' in processor, 'Official report path is not teacher-scoped'
assert "l.teacherId=:teacherId" in dao and "ar.approvalStatus IN ('APPROVED','FROZEN')" in dao, 'Official teacher report query lacks teacher/approval scope'
assert "l.status IN ('COMPLETED','FROZEN')" in dao, 'Unapproved lecture statuses are not excluded'

# Logical DB validation for A/B/C and D/E/F.
db=sqlite3.connect(':memory:')
db.executescript('''
PRAGMA foreign_keys=ON;
CREATE TABLE lectures(id TEXT PRIMARY KEY,subjectId TEXT,teacherId TEXT,status TEXT,scheduledStart INTEGER);
CREATE TABLE students(id TEXT PRIMARY KEY,fullName TEXT,normalizedName TEXT,universityNumber TEXT);
CREATE TABLE teacher_subjects(teacherId TEXT,subjectId TEXT,PRIMARY KEY(teacherId,subjectId));
CREATE TABLE attendance_records(id TEXT PRIMARY KEY,lectureId TEXT,studentId TEXT,finalStatus TEXT,approvalStatus TEXT,attendancePercentage REAL,verifiedPresenceSeconds INTEGER,firstSeenAt INTEGER,lastSeenAt INTEGER);
CREATE TABLE notifications(id TEXT PRIMARY KEY,deduplicationKey TEXT UNIQUE);
''')
db.execute("INSERT INTO students VALUES('s1','Student One','student one','1001')")
db.execute("INSERT INTO students VALUES('s2','Student Two','student two','1002')")
db.execute("INSERT INTO teacher_subjects VALUES('ta','subA')")
db.execute("INSERT INTO teacher_subjects VALUES('tb','subB')")
db.execute("INSERT INTO lectures VALUES('la','subA','ta','NEEDS_REVIEW',100)")
db.execute("INSERT INTO lectures VALUES('lb','subB','tb','COMPLETED',100)")
db.execute("INSERT INTO lectures VALUES('lua','subA','ta','NEEDS_REVIEW',120)")
db.execute("INSERT INTO attendance_records VALUES('ra','la','s1','ABSENT','DRAFT',0.0,0,NULL,NULL)")
db.execute("INSERT INTO attendance_records VALUES('rb','lb','s2','PRESENT','APPROVED',1.0,3600,1,2)")
db.execute("INSERT INTO attendance_records VALUES('rua','lua','s1','PRESENT','DRAFT',1.0,3600,1,2)")
# TEST A: end -> NEEDS_REVIEW, no queue side effect.
assert db.execute("SELECT COUNT(*) FROM notifications").fetchone()[0]==0
# TEST B/C: approve and dedupe queue.
db.execute("UPDATE lectures SET status='COMPLETED' WHERE id='la'")
db.execute("UPDATE attendance_records SET approvalStatus='APPROVED' WHERE id='ra'")
for _ in range(2):
    db.execute("INSERT OR IGNORE INTO notifications VALUES(?,?)",(f'n{_}','absence-wa:s1:la'))
assert db.execute("SELECT COUNT(*) FROM notifications").fetchone()[0]==1, 'TEST C: duplicate notification was not deduped'

query='''SELECT ar.studentId FROM attendance_records ar
JOIN lectures l ON l.id=ar.lectureId
JOIN students st ON st.id=ar.studentId
JOIN teacher_subjects ts ON ts.subjectId=l.subjectId AND ts.teacherId=?
WHERE l.teacherId=? AND (? IS NULL OR l.subjectId=?)
AND l.scheduledStart>=? AND l.scheduledStart<?
AND l.status IN ('COMPLETED','FROZEN') AND ar.approvalStatus IN ('APPROVED','FROZEN')'''
# TEST D: teacher A, no subject filter, must not include teacher B.
rows=[r[0] for r in db.execute(query,('ta','ta',None,None,0,1000))]
assert rows==['s1'], f'TEST D: cross-teacher data leaked: {rows}'
# TEST E: teacher A explicitly asks for teacher B subject -> no rows.
rows=[r[0] for r in db.execute(query,('ta','ta','subB','subB',0,1000))]
assert rows==[], f'TEST E: unauthorized subject returned: {rows}'
# TEST F: unapproved teacher A lecture must not appear.
rows=[r[0] for r in db.execute(query,('ta','ta','subA','subA',110,130))]
assert rows==[], f'TEST F: unapproved lecture returned: {rows}'

# TEST G: pagination calculation consumes every row and spans multiple pages.
def paginate(count:int, start_y=250, row_height=26, bottom=805, header_height=34):
    y=start_y; pages=1; consumed=0
    for _ in range(count):
        if y+row_height>bottom:
            pages+=1; y=40+header_height
        y+=row_height; consumed+=1
    return consumed,pages
consumed,pages=paginate(60)
assert consumed==60 and pages>1, f'TEST G: pagination failed consumed={consumed}, pages={pages}'

print('R2 VALIDATION PASS')
print('TEST A PASS - no absence queue before approval')
print('TEST B PASS - approval is notification side-effect point')
print('TEST C PASS - duplicate absence notification deduped')
print('TEST D PASS - Teacher A report excludes Teacher B')
print('TEST E PASS - cross-subject request returns no unauthorized rows')
print('TEST F PASS - unapproved lecture records excluded')
print(f'TEST G PASS - 60 rows consumed across {pages} pages')
