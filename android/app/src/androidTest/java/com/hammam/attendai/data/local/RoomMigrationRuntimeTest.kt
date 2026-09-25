package com.hammam.attendai.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hammam.attendai.backup.BackupValidationRules
import com.hammam.attendai.backup.EncryptedBackupManager
import com.hammam.attendai.data.local.entity.AppSettingEntity
import com.hammam.attendai.sync.LocalSyncIdentityGuard
import com.hammam.attendai.sync.LocalSyncIdentityRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Repair-8 runtime migration acceptance.
 *
 * Historical fixtures are reconstructed only from authoritative production contracts:
 *  - Room v5 creates the current schema.
 *  - v4 is v5 minus the exact MIGRATION_4_5 delta; this is independently consistent with
 *    the historical v4 source lineage.
 *  - v3/v2/v1 are obtained by reversing only the exact deltas encoded in
 *    MIGRATION_3_4 / MIGRATION_2_3 / MIGRATION_1_2.
 *
 * The production migrations under test are never reimplemented for the forward upgrade.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val created = mutableSetOf<String>()

    @After
    fun cleanup() {
        created.forEach { name -> context.deleteDatabase(name) }
        context.cacheDir.listFiles()?.filter { it.name.startsWith("repair8-") }?.forEach { it.delete() }
    }

    @Test fun migrate1To5_realRoom_reopen_backup_sync() = verifyPath(1, backupCheck = true)
    @Test fun migrate2To5_realRoom_reopen() = verifyPath(2)
    @Test fun migrate3To5_realRoom_reopen() = verifyPath(3)
    @Test fun migrate4To5_realRoom_reopen_syncQueuePreserved() = verifyPath(4)

    @Test
    fun orphanLegacyAppeal_v2To5_failsClosedWithoutDataLoss() {
        val name = "repair8-orphan-v2.db"; created += name
        createHistorical(name, 2, large = false)
        direct(name).use { db ->
            db.execSQL(
                "INSERT INTO attendance_appeals(id,studentId,attendanceRecordId,reason,attachmentPath,status,submittedAt,reviewedBy,reviewedAt,decisionNote) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("orphan-ap","s1","missing-record","Cannot resolve",null,"PENDING",77L,null,null,null)
            )
        }
        val failure = runCatching { openProduction(name).useOpened() }.exceptionOrNull()
        assertNotNull("Migration must fail instead of discarding an orphan appeal", failure)
        direct(name).use { db ->
            assertEquals(2, pragmaInt(db, "user_version"))
            assertEquals(1L, queryLong(db, "SELECT COUNT(*) FROM attendance_appeals WHERE id='orphan-ap'"))
            assertEquals(0L, queryLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='attendance_appeals_legacy'"))
            assertEquals("ok", queryString(db, "PRAGMA integrity_check"))
        }
    }

    @Test
    fun migrate1To5_largeDataset_preservesCountsAndReopens() {
        val name = "repair8-large-v1.db"; created += name
        createHistorical(name, 1, large = true)
        val started = SystemClock.elapsedRealtime()
        val db = openProduction(name)
        forceOpen(db)
        val elapsed = SystemClock.elapsedRealtime() - started
        verifyDatabaseHealth(db)
        assertEquals(1501L, roomCount(db, "students"))
        assertEquals(121L, roomCount(db, "lectures"))
        assertEquals(6002L, roomCount(db, "attendance_records"))
        assertEquals(602L, roomCount(db, "attendance_appeals"))
        assertEquals(501L, roomCount(db, "report_jobs"))
        assertEquals(1002L, roomCount(db, "sync_queue"))
        assertTrue("Migration elapsed time is diagnostic only: ${elapsed}ms", elapsed >= 0L)
        val before = snapshotCounts(db)
        db.close()
        val reopened = openProduction(name); forceOpen(reopened)
        assertEquals(before, snapshotCounts(reopened))
        verifyDatabaseHealth(reopened)
        reopened.close()
    }

    @Test
    fun edgeDataset_unicode_nullable_archived_reviewed_survivesV2To5() {
        val name = "repair8-edge-v2.db"; created += name
        createHistorical(name, 2, large = false)
        val db = openProduction(name); forceOpen(db)
        assertEquals("طالب عربي – O'Neil", roomString(db, "SELECT fullName FROM students WHERE id='s1'"))
        assertEquals(0L, roomLong(db, "SELECT isActive FROM users WHERE id='u-disabled'"))
        assertEquals(2L, roomLong(db, "SELECT COUNT(*) FROM user_roles WHERE userId='u1'"))
        assertEquals(1L, roomLong(db, "SELECT COUNT(*) FROM attendance_appeals WHERE reviewedAt IS NOT NULL"))
        assertEquals(1L, roomLong(db, "SELECT COUNT(*) FROM attendance_appeals WHERE reviewedAt IS NULL"))
        assertTrue(roomString(db, "SELECT description FROM attendance_appeals WHERE id='ap-long'").length >= 1024)
        verifyDatabaseHealth(db)
        db.close()
    }

    private fun verifyPath(startVersion: Int, backupCheck: Boolean = false) {
        val name = "repair8-v${startVersion}-to-v5.db"; created += name
        createHistorical(name, startVersion, large = false)
        val before = directSnapshot(name)

        val db = openProduction(name); forceOpen(db)
        verifyDatabaseHealth(db)
        verifyMigrationSemantics(db, startVersion)
        verifyDaoSmoke(db, startVersion)
        verifySyncCompatibility(db)
        if (backupCheck) verifyBackupCompatibility(db, name)

        val afterFirstOpen = snapshotCounts(db)
        runBlocking { db.coreDao().upsertSetting(AppSettingEntity("post.migration.$startVersion", "cipher-$startVersion", 999L)) }
        db.close()

        val reopened = openProduction(name); forceOpen(reopened)
        verifyDatabaseHealth(reopened)
        assertEquals(afterFirstOpen, snapshotCounts(reopened))
        assertEquals("cipher-$startVersion", roomString(reopened, "SELECT valueCiphertext FROM app_settings WHERE key='post.migration.$startVersion'"))
        assertHistoricalRowsPreserved(reopened, before, startVersion)
        assertEquals("wal", roomString(reopened, "PRAGMA journal_mode").lowercase())
        reopened.close()
    }

    private fun createHistorical(name: String, targetVersion: Int, large: Boolean) {
        context.deleteDatabase(name)
        val current = openProduction(name); forceOpen(current)
        seedCurrentV5(current, large)
        current.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        current.close()

        direct(name).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            if (targetVersion <= 4) reverseV5ToV4(db)
            if (targetVersion <= 3) reverseV4ToV3(db)
            if (targetVersion <= 2) reverseV3ToV2(db)
            if (targetVersion <= 1) reverseV2ToV1(db)
            db.execSQL("DROP TABLE IF EXISTS room_master_table")
            db.execSQL("PRAGMA user_version=$targetVersion")
            db.execSQL("PRAGMA foreign_keys=ON")
            assertEquals("ok", queryString(db, "PRAGMA integrity_check"))
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
        }
    }

    private fun seedCurrentV5(db: HammamDatabase, large: Boolean) {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            sql.execSQL("INSERT INTO users(id,username,displayName,passwordHash,isActive,createdAt,updatedAt,version) VALUES('u1','owner','المالك Owner',NULL,1,10,11,7)")
            sql.execSQL("INSERT INTO users(id,username,displayName,passwordHash,isActive,createdAt,updatedAt,version) VALUES('u-disabled','disabled','Disabled',NULL,0,12,13,2)")
            sql.execSQL("INSERT INTO roles(id,name,description) VALUES('role-owner','SYSTEM_OWNER',NULL),('role-teacher','Teacher','Teacher role')")
            sql.execSQL("INSERT INTO permissions(id,code,description) VALUES('perm-view','VIEW_STUDENTS',NULL)")
            sql.execSQL("INSERT INTO user_roles(userId,roleId) VALUES('u1','role-owner'),('u1','role-teacher')")
            sql.execSQL("INSERT INTO role_permissions(roleId,permissionId) VALUES('role-owner','perm-view')")

            sql.execSQL("INSERT INTO universities(id,name,archivedAt) VALUES('uni1','جامعة صنعاء',NULL)")
            sql.execSQL("INSERT INTO faculties(id,universityId,name,archivedAt) VALUES('fac1','uni1','Medicine',NULL)")
            sql.execSQL("INSERT INTO departments(id,facultyId,name,archivedAt) VALUES('dep1','fac1','General',NULL)")
            sql.execSQL("INSERT INTO academic_years(id,name,startDate,endDate,isActive) VALUES('yr1','2026/2027','2026-09-01','2027-06-30',1)")
            sql.execSQL("INSERT INTO semesters(id,name,academicYearId,startDate,endDate,status,createdAt,updatedAt) VALUES('sem1','Semester 1','yr1','2026-09-01','2027-01-31','ACTIVE',20,21)")
            sql.execSQL("INSERT INTO levels(id,departmentId,name,orderIndex,archivedAt) VALUES('lvl1','dep1','Level 1',1,NULL)")
            sql.execSQL("INSERT INTO batches(id,levelId,name,academicYearId,archivedAt) VALUES('bat1','lvl1','Batch A','yr1',NULL)")
            sql.execSQL("INSERT INTO sections(id,batchId,name,archivedAt) VALUES('sec1','bat1','Section A',NULL)")
            sql.execSQL("INSERT INTO groups(id,sectionId,name,archivedAt) VALUES('grp1','sec1','Group A',NULL)")

            sql.execSQL("INSERT INTO students(id,universityNumber,fullName,normalizedName,phoneNumber,whatsappNumber,levelId,batchId,sectionId,groupId,status,registeredDeviceId,createdAt,updatedAt,archivedAt,version) VALUES('s1','2026001','طالب عربي – O''Neil','طالب عربي o''neil',NULL,'+967700000001','lvl1','bat1','sec1','grp1','ACTIVE',NULL,30,31,NULL,4)")
            sql.execSQL("INSERT INTO teachers(id,fullName,normalizedName,phone,whatsapp,email,preferredNotificationChannel,notificationsEnabled,createdAt,updatedAt,archivedAt,version) VALUES('t1','د. أحمد O''Neil','د أحمد o''neil',NULL,NULL,'teacher@example.com','EMAIL',1,40,41,NULL,5)")
            sql.execSQL("INSERT INTO attendance_policies(id,name,scopeType,scopeId,fullAttendanceThreshold,partialAttendanceThreshold,lateAfterMinutes,earlyLeaveThresholdMinutes,absenceThreshold,temporaryMissingGraceSeconds,minimumPresenceVerificationSeconds,confidenceThreshold,createdAt,updatedAt,version) VALUES('pol1','Default','GLOBAL',NULL,0.80,0.50,10,5,0.20,30,60,0.70,50,51,3)")
            sql.execSQL("INSERT INTO subjects(id,code,name,teacherId,levelId,semesterId,groupId,attendancePolicyId,status,archivedAt,version) VALUES('sub1','BIO-101','Biology','t1','lvl1','sem1','grp1','pol1','ACTIVE',NULL,6)")
            sql.execSQL("INSERT INTO teacher_subjects(teacherId,subjectId) VALUES('t1','sub1')")
            sql.execSQL("INSERT INTO timetables(id,dayOfWeek,startTime,endTime,room,subjectId,teacherId,groupId,lectureType,scheduleKind,isActive,createdAt,updatedAt,version,weeklyScheduleId) VALUES('tt1',1,'08:00','09:00','Lab, A','sub1','t1','grp1','LECTURE','WEEKLY',1,60,61,2,NULL)")
            sql.execSQL("INSERT INTO lectures(id,subjectId,teacherId,semesterId,groupId,scheduledStart,scheduledEnd,actualStart,actualEnd,room,status,attendancePolicySnapshotJson,createdAt,updatedAt,version) VALUES('l1','sub1','t1','sem1','grp1',1000,4600,1010,4590,'Room 1','COMPLETED','{}',70,71,8)")
            sql.execSQL("INSERT INTO lectures(id,subjectId,teacherId,semesterId,groupId,scheduledStart,scheduledEnd,actualStart,actualEnd,room,status,attendancePolicySnapshotJson,createdAt,updatedAt,version) VALUES('l2','sub1','t1','sem1','grp1',5000,8600,5010,8590,NULL,'FROZEN','{}',72,73,9)")
            sql.execSQL("INSERT INTO attendance_records(id,lectureId,studentId,firstSeenAt,lastSeenAt,verifiedPresenceSeconds,lectureDurationSeconds,attendancePercentage,lateMinutes,earlyLeaveMinutes,confidenceScore,finalStatus,approvalStatus,source,notes,createdAt,updatedAt,version) VALUES('ar1','l1','s1',1010,4500,3490,3600,0.969,0,0,0.95,'PRESENT','APPROVED','BLE',NULL,80,81,11)")
            sql.execSQL("INSERT INTO attendance_records(id,lectureId,studentId,firstSeenAt,lastSeenAt,verifiedPresenceSeconds,lectureDurationSeconds,attendancePercentage,lateMinutes,earlyLeaveMinutes,confidenceScore,finalStatus,approvalStatus,source,notes,createdAt,updatedAt,version) VALUES('ar2','l2','s1',5010,8500,3490,3600,0.969,0,0,0.95,'PRESENT','FROZEN','MANUAL','Reviewed',82,83,12)")
            sql.execSQL("INSERT INTO attendance_appeals(id,studentId,attendanceRecordId,lectureId,subjectId,reasonType,description,attachmentLocalUri,attachmentRemoteUrl,status,submittedAt,updatedAt,reviewedBy,reviewedAt,decisionNote,syncStatus,version) VALUES('ap1','s1','ar1','l1','sub1','OTHER','Incorrect status',NULL,NULL,'PENDING',90,91,NULL,NULL,NULL,'LOCAL_ONLY',3)")
            sql.execSQL("INSERT INTO attendance_appeals(id,studentId,attendanceRecordId,lectureId,subjectId,reasonType,description,attachmentLocalUri,attachmentRemoteUrl,status,submittedAt,updatedAt,reviewedBy,reviewedAt,decisionNote,syncStatus,version) VALUES('ap-long','s1','ar2','l2','sub1','OTHER',?,NULL,NULL,'ACCEPTED',92,93,'u1',94,'تمت المراجعة','SYNCED',4)", arrayOf("عربي Unicode O'Neil \"quoted\" " + "x".repeat(1200)))

            sql.execSQL("INSERT INTO teacher_report_settings(id,teacherId,subjectId,enabled,frequency,sendTime,weeklyDay,monthlyDay,semesterReportEnabled,customRule,timezone,channel,reportFormat,includeStudentDetails,requireApproval,aiSummaryEnabled,sendIfNoLecture,updatedAt,version) VALUES('trs1','t1','sub1',1,'WEEKLY','18:00',7,NULL,0,NULL,'Asia/Aden','EMAIL','PDF',1,1,0,1,100,5)")
            sql.execSQL("INSERT INTO report_jobs(id,teacherId,subjectId,reportType,periodStart,periodEnd,scheduledAt,generatedAt,sentAt,status,retryCount,providerMessageId,errorMessage,deduplicationKey,version) VALUES('rj1','t1','sub1','WEEKLY',0,10000,101,102,103,'SENT',2,'provider-1',NULL,'dedup-rj1',6)")
            sql.execSQL("INSERT INTO app_settings(key,valueCiphertext,updatedAt) VALUES('setting1','ciphertext-preserved',110),('sync.pull.cursor.workspace-a','cipher-cursor',111)")
            sql.execSQL("INSERT INTO audit_logs(id,actorId,action,entityType,entityId,oldData,newData,reason,timestamp,actorRole) VALUES('au1','u1','TEST','Student','s1',NULL,NULL,'reason',120,'SYSTEM_OWNER')")
            sql.execSQL("INSERT INTO sync_queue(id,entityType,entityId,operation,payloadCiphertext,createdAt,lastAttempt,retryCount,status,error,idempotencyKey,version) VALUES('q-pending','Teacher','t1','UPSERT','enc-pending',130,NULL,3,'PENDING','NETWORK_UNAVAILABLE','teacher:t1:5',7)")
            sql.execSQL("INSERT INTO sync_queue(id,entityType,entityId,operation,payloadCiphertext,createdAt,lastAttempt,retryCount,status,error,idempotencyKey,version) VALUES('q-sent','AttendancePolicy','pol1','UPSERT','enc-sent',131,132,0,'SENT',NULL,'policy:pol1:3',8)")

            sql.execSQL("INSERT INTO user_scopes(id,userId,scopeType,scopeId,active,createdAt,createdBy) VALUES('scope1','u1','GROUP','grp1',1,140,'u1')")
            sql.execSQL("INSERT INTO user_permission_grants(id,userId,permissionCode,scopeType,scopeId,startsAt,expiresAt,grantedBy,createdAt,reason,active,revokedAt) VALUES('grant1','u1','VIEW_STUDENTS','GROUP','grp1',NULL,NULL,'u1',141,'test',1,NULL)")
            sql.execSQL("INSERT INTO device_replacement_requests(id,studentId,oldDeviceId,newDevicePublicId,newPublicKey,newPresenceSecretCiphertext,status,requestedAt,reviewedBy,reviewedAt,decisionNote,version) VALUES('dr1','s1',NULL,'device-new',NULL,'enc-secret','PENDING',142,NULL,NULL,NULL,2)")
            sql.execSQL("INSERT INTO weekly_timetable_versions(id,groupId,weekStart,weekEnd,versionNumber,status,sourceType,sourceUri,importedBy,approvedBy,createdAt,updatedAt,approvedAt,reason) VALUES('wtv1','grp1','2026-09-21','2026-09-27',1,'ACTIVE','MANUAL',NULL,'u1','u1',143,144,145,NULL)")
            sql.execSQL("INSERT INTO sync_entity_metadata(entityType,entityId,localVersion,firstSeenAt,updatedAt,payloadFingerprint,lastSyncedServerVersion) VALUES('Teacher','t1',5,150,151,'fp-t1',99)")

            if (large) seedLarge(sql)
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private fun seedLarge(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
        repeat(1500) { i ->
            val id="ls$i"
            sql.execSQL("INSERT INTO students(id,universityNumber,fullName,normalizedName,phoneNumber,whatsappNumber,levelId,batchId,sectionId,groupId,status,registeredDeviceId,createdAt,updatedAt,archivedAt,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id,"L${100000+i}","Student $i","student $i",null,null,"lvl1","bat1","sec1","grp1","ACTIVE",null,200L+i,200L+i,null,1L))
        }
        repeat(120) { i ->
            val id="ll$i"; val start=100000L+i*5000L
            sql.execSQL("INSERT INTO lectures(id,subjectId,teacherId,semesterId,groupId,scheduledStart,scheduledEnd,actualStart,actualEnd,room,status,attendancePolicySnapshotJson,createdAt,updatedAt,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id,"sub1","t1","sem1","grp1",start,start+3600,start,start+3500,null,"COMPLETED","{}",start,start+1,1L))
        }
        var record=0
        repeat(100) { lecture -> repeat(60) { student ->
            val id="lar${record++}"; val lid="ll$lecture"; val sid="ls$student"
            sql.execSQL("INSERT INTO attendance_records(id,lectureId,studentId,firstSeenAt,lastSeenAt,verifiedPresenceSeconds,lectureDurationSeconds,attendancePercentage,lateMinutes,earlyLeaveMinutes,confidenceScore,finalStatus,approvalStatus,source,notes,createdAt,updatedAt,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id,lid,sid,1L,3500L,3499L,3600L,0.97,0,0,0.9,"PRESENT","APPROVED","BLE",null,1L,2L,1L))
        }}
        repeat(600) { i ->
            val rec="lar$i"; val lecture="ll${i/60}"
            sql.execSQL("INSERT INTO attendance_appeals(id,studentId,attendanceRecordId,lectureId,subjectId,reasonType,description,attachmentLocalUri,attachmentRemoteUrl,status,submittedAt,updatedAt,reviewedBy,reviewedAt,decisionNote,syncStatus,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("lap$i","ls${i%60}",rec,lecture,"sub1","OTHER","Appeal $i",null,null,"PENDING",10L+i,10L+i,null,null,null,"LOCAL_ONLY",1L))
        }
        repeat(500) { i ->
            sql.execSQL("INSERT INTO report_jobs(id,teacherId,subjectId,reportType,periodStart,periodEnd,scheduledAt,generatedAt,sentAt,status,retryCount,providerMessageId,errorMessage,deduplicationKey,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("lrj$i","t1","sub1","WEEKLY",0L,1000L,1000L+i,null,null,"SCHEDULED",0,null,null,"large-dedup-$i",1L))
        }
        repeat(1000) { i ->
            val status=if(i%2==0)"PENDING" else "SENT"
            sql.execSQL("INSERT INTO sync_queue(id,entityType,entityId,operation,payloadCiphertext,createdAt,lastAttempt,retryCount,status,error,idempotencyKey,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("lq$i","Teacher","t1","UPSERT","enc-$i",1000L+i,null,i%5,status,null,"large-key-$i",1L))
        }
    }

    private fun reverseV5ToV4(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS sync_entity_metadata")
    }

    private fun reverseV4ToV3(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS user_scopes")
        db.execSQL("DROP TABLE IF EXISTS user_permission_grants")
        db.execSQL("DROP TABLE IF EXISTS device_replacement_requests")
        db.execSQL("DROP TABLE IF EXISTS weekly_timetable_versions")
        db.execSQL("DROP INDEX IF EXISTS index_timetables_weeklyScheduleId")
        db.execSQL("ALTER TABLE timetables DROP COLUMN weeklyScheduleId")
        db.execSQL("ALTER TABLE audit_logs DROP COLUMN actorRole")
        db.execSQL("DROP INDEX IF EXISTS index_audit_logs_entityType_entityId_timestamp")
    }

    private fun reverseV3ToV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE attendance_appeals_v2(id TEXT NOT NULL PRIMARY KEY,studentId TEXT NOT NULL,attendanceRecordId TEXT NOT NULL,reason TEXT NOT NULL,attachmentPath TEXT,status TEXT NOT NULL,submittedAt INTEGER NOT NULL,reviewedBy TEXT,reviewedAt INTEGER,decisionNote TEXT)")
        db.execSQL("INSERT INTO attendance_appeals_v2(id,studentId,attendanceRecordId,reason,attachmentPath,status,submittedAt,reviewedBy,reviewedAt,decisionNote) SELECT id,studentId,attendanceRecordId,description,attachmentLocalUri,status,submittedAt,reviewedBy,reviewedAt,decisionNote FROM attendance_appeals")
        db.execSQL("DROP TABLE attendance_appeals")
        db.execSQL("ALTER TABLE attendance_appeals_v2 RENAME TO attendance_appeals")
    }

    private fun reverseV2ToV1(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE teacher_report_settings DROP COLUMN sendIfNoLecture")
    }

    private fun verifyMigrationSemantics(db: HammamDatabase, startVersion: Int) {
        assertEquals(5L, roomLong(db, "PRAGMA user_version"))
        val send = roomLong(db, "SELECT sendIfNoLecture FROM teacher_report_settings WHERE id='trs1'")
        assertEquals(if(startVersion==1)0L else 1L, send)
        assertEquals("l1", roomString(db, "SELECT lectureId FROM attendance_appeals WHERE id='ap1'"))
        assertEquals("sub1", roomString(db, "SELECT subjectId FROM attendance_appeals WHERE id='ap1'"))
        assertEquals("Incorrect status", roomString(db, "SELECT description FROM attendance_appeals WHERE id='ap1'"))
        assertEquals("OTHER", roomString(db, "SELECT reasonType FROM attendance_appeals WHERE id='ap1'"))
        assertEquals("LOCAL_ONLY", roomString(db, "SELECT syncStatus FROM attendance_appeals WHERE id='ap1'"))
        if(startVersion<=3){
            assertEquals(0L, roomLong(db, "SELECT COUNT(*) FROM user_scopes"))
            assertNull(roomNullableString(db, "SELECT weeklyScheduleId FROM timetables WHERE id='tt1'"))
            assertNull(roomNullableString(db, "SELECT actorRole FROM audit_logs WHERE id='au1'"))
        } else {
            assertEquals(1L, roomLong(db, "SELECT COUNT(*) FROM user_scopes WHERE id='scope1'"))
            assertEquals("SYSTEM_OWNER", roomString(db, "SELECT actorRole FROM audit_logs WHERE id='au1'"))
        }
        assertEquals(0L, roomLong(db, "SELECT COUNT(*) FROM sync_entity_metadata"))
        assertEquals("PENDING", roomString(db, "SELECT status FROM sync_queue WHERE id='q-pending'"))
        assertEquals(3L, roomLong(db, "SELECT retryCount FROM sync_queue WHERE id='q-pending'"))
        assertEquals("teacher:t1:5", roomString(db, "SELECT idempotencyKey FROM sync_queue WHERE id='q-pending'"))
        assertEquals("enc-pending", roomString(db, "SELECT payloadCiphertext FROM sync_queue WHERE id='q-pending'"))
        assertEquals("SENT", roomString(db, "SELECT status FROM sync_queue WHERE id='q-sent'"))
        assertRequiredIndexes(db)
    }

    private fun verifyDaoSmoke(db: HammamDatabase, startVersion: Int) = runBlocking {
        val dao=db.coreDao()
        assertEquals("المالك Owner", dao.getUserById("u1")?.displayName)
        assertEquals("طالب عربي – O'Neil", dao.getStudentById("s1")?.fullName)
        assertEquals("perm-view", dao.getPermissionIdByCode("VIEW_STUDENTS"))
        assertEquals("trs1", dao.getReportSetting("t1","sub1")?.id)
        assertEquals(0, dao.getAllSyncEntityMetadata().size)
        dao.insertSyncEntityMetadata(com.hammam.attendai.data.local.entity.SyncEntityMetadataEntity("Teacher","post-$startVersion",1,1,1,"fp",null))
        assertNotNull(dao.getSyncEntityMetadata("Teacher","post-$startVersion"))
    }

    private fun verifySyncCompatibility(db: HammamDatabase) = runBlocking {
        assertEquals("ciphertext-preserved", db.coreDao().getSetting("setting1")?.valueCiphertext)
        assertEquals("cipher-cursor", db.coreDao().getSetting("sync.pull.cursor.workspace-a")?.valueCiphertext)
        val guard=LocalSyncIdentityGuard(db, { it }, { it })
        val selected=guard.selectWorkspaceForSession("acct-a",null,listOf("workspace-a"))
        assertEquals("workspace-a", selected.workspaceId)
        assertNull(selected.error)
        assertNull(guard.bindOrValidate("acct-a","workspace-a"))
        assertEquals(LocalSyncIdentityRules.WORKSPACE_MISMATCH,guard.bindOrValidate("acct-a","workspace-b"))
    }

    private fun verifyBackupCompatibility(db: HammamDatabase, name: String) {
        val path=context.getDatabasePath(name)
        assertNull(BackupValidationRules.validateVersion(5, roomLong(db,"PRAGMA user_version").toInt(), 5))
        val manager=EncryptedBackupManager(context,db)
        val target=File(context.cacheDir,"repair8-${name}.backup")
        val pass="migration-passphrase".toCharArray()
        try {
            manager.export(path,target,pass)
            val validation=manager.validate(target,pass)
            assertTrue(validation.error ?: "backup validation failed", validation.valid)
            assertEquals(5,validation.databaseVersion)
        } finally { pass.fill('\u0000'); target.delete() }
    }

    private fun verifyDatabaseHealth(db: HammamDatabase) {
        assertEquals(5L, roomLong(db,"PRAGMA user_version"))
        verifyForeignKeyEnforcement(db)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { c ->
            assertFalse("Foreign-key violation after migration", c.moveToFirst())
        }
        assertEquals("ok", roomString(db,"PRAGMA integrity_check"))
    }

    private fun verifyForeignKeyEnforcement(db: HammamDatabase) {
        val sql = db.openHelper.writableDatabase
        val missingUserId = "repair8-missing-user-${System.nanoTime()}"
        val failure = runCatching {
            sql.execSQL(
                "INSERT INTO user_roles(userId,roleId) VALUES(?,?)",
                arrayOf<Any?>(missingUserId, "role-owner")
            )
        }.exceptionOrNull()

        if (failure == null) {
            sql.execSQL(
                "DELETE FROM user_roles WHERE userId=? AND roleId=?",
                arrayOf<Any?>(missingUserId, "role-owner")
            )
        }

        val readablePragma = roomLong(db, "PRAGMA foreign_keys")
        assertNotNull(
            "Room write path accepted an invalid user_roles child row; " +
                "readable-connection PRAGMA foreign_keys=$readablePragma",
            failure
        )
        assertTrue(
            "Expected a SQLite FOREIGN KEY constraint failure but got " +
                "${failure?.javaClass?.name}: ${failure?.message}; " +
                "readable-connection PRAGMA foreign_keys=$readablePragma",
            failure != null && isForeignKeyConstraintFailure(failure)
        )
    }

    private fun isForeignKeyConstraintFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is SQLiteConstraintException &&
                current.message?.contains("FOREIGN KEY", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun assertHistoricalRowsPreserved(db:HammamDatabase,before:Map<String,Long>,startVersion:Int){
        assertEquals(before.getValue("users"),roomCount(db,"users"))
        assertEquals(before.getValue("students"),roomCount(db,"students"))
        assertEquals(before.getValue("teachers"),roomCount(db,"teachers"))
        assertEquals(before.getValue("attendance_records"),roomCount(db,"attendance_records"))
        assertEquals(before.getValue("attendance_appeals"),roomCount(db,"attendance_appeals"))
        assertEquals(before.getValue("report_jobs"),roomCount(db,"report_jobs"))
        assertEquals(before.getValue("sync_queue"),roomCount(db,"sync_queue"))
        assertEquals("2026001",roomString(db,"SELECT universityNumber FROM students WHERE id='s1'"))
        assertEquals(7L,roomLong(db,"SELECT version FROM users WHERE id='u1'"))
        assertEquals(5L,roomLong(db,"SELECT version FROM teachers WHERE id='t1'"))
        assertEquals(11L,roomLong(db,"SELECT version FROM attendance_records WHERE id='ar1'"))
        assertEquals("ciphertext-preserved",roomString(db,"SELECT valueCiphertext FROM app_settings WHERE key='setting1'"))
        if(startVersion>=4) assertEquals(1L,roomLong(db,"SELECT COUNT(*) FROM weekly_timetable_versions WHERE id='wtv1'"))
    }

    private fun assertRequiredIndexes(db:HammamDatabase){
        val expected=mapOf(
            "user_scopes" to setOf("index_user_scopes_userId","index_user_scopes_scopeType_scopeId","index_user_scopes_active"),
            "user_permission_grants" to setOf("index_user_permission_grants_userId","index_user_permission_grants_permissionCode","index_user_permission_grants_scopeType_scopeId","index_user_permission_grants_active","index_user_permission_grants_expiresAt"),
            "device_replacement_requests" to setOf("index_device_replacement_requests_studentId","index_device_replacement_requests_status","index_device_replacement_requests_requestedAt"),
            "weekly_timetable_versions" to setOf("index_weekly_timetable_versions_groupId","index_weekly_timetable_versions_weekStart","index_weekly_timetable_versions_status","index_weekly_timetable_versions_groupId_weekStart_versionNumber"),
            "timetables" to setOf("index_timetables_weeklyScheduleId"),
            "audit_logs" to setOf("index_audit_logs_entityType_entityId_timestamp"),
            "sync_entity_metadata" to setOf("index_sync_entity_metadata_entityType","index_sync_entity_metadata_updatedAt")
        )
        expected.forEach{(table,want)->
            val got=mutableSetOf<String>()
            db.openHelper.readableDatabase.query("PRAGMA index_list(`$table`)").use{c->while(c.moveToNext())got+=c.getString(c.getColumnIndexOrThrow("name"))}
            assertTrue("Missing indexes for $table: ${want-got}",got.containsAll(want))
        }
    }

    private fun snapshotCounts(db:HammamDatabase)=listOf("users","students","teachers","lectures","attendance_records","attendance_appeals","report_jobs","sync_queue").associateWith{roomCount(db,it)}
    private fun directSnapshot(name:String)=direct(name).use{db->listOf("users","students","teachers","attendance_records","attendance_appeals","report_jobs","sync_queue").associateWith{queryLong(db,"SELECT COUNT(*) FROM `$it`")}}

    private fun openProduction(name:String)=Room.databaseBuilder(context,HammamDatabase::class.java,name)
        .addMigrations(*HammamDatabase.ALL_MIGRATIONS)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .allowMainThreadQueries()
        .build()

    private fun forceOpen(db:HammamDatabase){ db.openHelper.writableDatabase.query("SELECT 1").close() }
    private fun HammamDatabase.useOpened(){ forceOpen(this); close() }
    private fun direct(name:String)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)

    private fun roomCount(db:HammamDatabase,table:String)=roomLong(db,"SELECT COUNT(*) FROM `$table`")
    private fun roomLong(db:HammamDatabase,sql:String):Long=db.openHelper.readableDatabase.query(sql).use{c->assertTrue(c.moveToFirst());c.getLong(0)}
    private fun roomString(db:HammamDatabase,sql:String):String=db.openHelper.readableDatabase.query(sql).use{c->assertTrue(c.moveToFirst());c.getString(0)}
    private fun roomNullableString(db:HammamDatabase,sql:String):String?=db.openHelper.readableDatabase.query(sql).use{c->assertTrue(c.moveToFirst());if(c.isNull(0))null else c.getString(0)}
    private fun queryLong(db:SQLiteDatabase,sql:String):Long=db.rawQuery(sql,null).use{c->assertTrue(c.moveToFirst());c.getLong(0)}
    private fun queryString(db:SQLiteDatabase,sql:String):String=db.rawQuery(sql,null).use{c->assertTrue(c.moveToFirst());c.getString(0)}
    private fun pragmaInt(db:SQLiteDatabase,name:String)=db.rawQuery("PRAGMA $name",null).use{c->assertTrue(c.moveToFirst());c.getInt(0)}
}
