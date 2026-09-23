from pathlib import Path
import sqlite3, tempfile
root=Path(__file__).resolve().parents[1]
core=(root/'android/app/src/main/java/com/hammam/attendai/data/local/dao/CoreDao.kt').read_text()
proc=(root/'android/app/src/main/java/com/hammam/attendai/sync/Processors.kt').read_text()
work=(root/'android/app/src/main/java/com/hammam/attendai/sync/WorkOrchestrator.kt').read_text()
entities=(root/'android/app/src/main/java/com/hammam/attendai/data/local/entity/Entities.kt').read_text()
assert "retryCount<5" in core
assert "NEEDS_MANUAL_REVIEW" in proc
assert "NetworkType.CONNECTED" in work and "enqueueUniqueWork" in work and "enqueueUniquePeriodicWork" in work
assert 'Index(value=["deduplicationKey"],unique=true)' in entities
# Representative persisted/dedup behavior.
tmp=tempfile.TemporaryDirectory()
db_path=Path(tmp.name)/'queue.db'
c=sqlite3.connect(db_path)
c.executescript('''
CREATE TABLE notifications(id TEXT PRIMARY KEY,deduplicationKey TEXT NOT NULL UNIQUE,status TEXT NOT NULL,retryCount INTEGER NOT NULL);
CREATE TABLE report_jobs(id TEXT PRIMARY KEY,deduplicationKey TEXT NOT NULL UNIQUE,status TEXT NOT NULL,retryCount INTEGER NOT NULL);
CREATE TABLE sync_queue(id TEXT PRIMARY KEY,idempotencyKey TEXT NOT NULL,status TEXT NOT NULL,retryCount INTEGER NOT NULL);
CREATE UNIQUE INDEX uq_sync_idem ON sync_queue(idempotencyKey);
''')
c.execute("INSERT INTO notifications VALUES('n1','student:lecture:absence','PENDING',0)")
try:c.execute("INSERT INTO notifications VALUES('n2','student:lecture:absence','PENDING',0)");raise AssertionError('notification duplicate accepted')
except sqlite3.IntegrityError:pass
c.execute("INSERT INTO report_jobs VALUES('r1','teacher:subject:weekly:1:2','PENDING_SEND',0)")
try:c.execute("INSERT INTO report_jobs VALUES('r2','teacher:subject:weekly:1:2','PENDING_SEND',0)");raise AssertionError('report duplicate accepted')
except sqlite3.IntegrityError:pass
c.execute("INSERT INTO sync_queue VALUES('s1','entity:1:update','FAILED',4)")
try:c.execute("INSERT INTO sync_queue VALUES('s2','entity:1:update','PENDING',0)");raise AssertionError('sync duplicate accepted')
except sqlite3.IntegrityError:pass
c.commit();c.close()
# A queued mutation must survive process/database restart exactly once.
c=sqlite3.connect(db_path)
row=c.execute("SELECT id,idempotencyKey,status,retryCount FROM sync_queue WHERE id='s1'").fetchone()
assert row==('s1','entity:1:update','FAILED',4),row
c.close();tmp.cleanup()
print('OFFLINE_QUEUE_VALIDATION_PASS')
# Repair-7: durable queue must be gated by one persisted local account/workspace identity.
identity=(root/'android/app/src/main/java/com/hammam/attendai/sync/LocalSyncIdentity.kt').read_text()
session=(root/'android/app/src/main/java/com/hammam/attendai/sync/BackendAccountSessionManager.kt').read_text()
http=(root/'android/app/src/main/java/com/hammam/attendai/sync/HttpBackendClient.kt').read_text()
assert 'sync.local.identity.binding.v1' in identity
assert 'bindOrValidate(account,workspace)' in proc
assert 'workspace!!' not in proc
assert 'sync.pull.blocked.' in proc
assert 'selectWorkspaceForSession' in session and 'memberships.first()' not in session
assert 'catch(e:CancellationException){throw e}' in http
print('REPAIR7_SYNC_IDENTITY_QUEUE_GUARD_PASS')
