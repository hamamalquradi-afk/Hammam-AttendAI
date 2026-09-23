package com.hammam.attendai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hammam.attendai.data.local.dao.CoreDao
import com.hammam.attendai.data.local.entity.*

@Database(
    entities = [
        UserEntity::class, RoleEntity::class, PermissionEntity::class, UserRoleEntity::class, RolePermissionEntity::class, UserScopeEntity::class, UserPermissionGrantEntity::class,
        UniversityEntity::class, FacultyEntity::class, DepartmentEntity::class, AcademicYearEntity::class, SemesterEntity::class,
        LevelEntity::class, BatchEntity::class, SectionEntity::class, GroupEntity::class,
        StudentEntity::class, StudentDeviceEntity::class, DeviceReplacementRequestEntity::class, TeacherEntity::class, AttendancePolicyEntity::class,
        SubjectEntity::class, TeacherSubjectEntity::class, TimetableEntity::class, WeeklyTimetableVersionEntity::class, LectureEntity::class, AttendanceSessionEntity::class,
        AttendanceRecordEntity::class, PresenceIntervalEntity::class, PresenceEventEntity::class, AttendanceAppealEntity::class,
        ExcusedAbsenceEntity::class, NotificationEntity::class, NotificationTemplateEntity::class, TeacherReportSettingEntity::class,
        ReportJobEntity::class, GeneratedReportEntity::class, AuditLogEntity::class, SyncQueueEntity::class, SyncEntityMetadataEntity::class, AppSettingEntity::class,
        FeatureFlagEntity::class, BackupHistoryEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class HammamDatabase : RoomDatabase() {
    abstract fun coreDao(): CoreDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE teacher_report_settings ADD COLUMN sendIfNoLecture INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val legacyCount = db.query("SELECT COUNT(*) FROM attendance_appeals").use { c -> c.moveToFirst(); c.getLong(0) }
                val resolvableCount = db.query(
                    "SELECT COUNT(*) FROM attendance_appeals aa " +
                        "JOIN attendance_records ar ON ar.id=aa.attendanceRecordId " +
                        "JOIN lectures l ON l.id=ar.lectureId " +
                        "JOIN subjects s ON s.id=l.subjectId " +
                        "JOIN students st ON st.id=aa.studentId"
                ).use { c -> c.moveToFirst(); c.getLong(0) }
                check(legacyCount == resolvableCount) {
                    "Attendance appeal migration stopped to avoid losing orphaned legacy appeal data"
                }

                db.execSQL("ALTER TABLE attendance_appeals RENAME TO attendance_appeals_legacy")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS attendance_appeals (
                        id TEXT NOT NULL PRIMARY KEY,
                        studentId TEXT NOT NULL,
                        attendanceRecordId TEXT NOT NULL,
                        lectureId TEXT NOT NULL,
                        subjectId TEXT NOT NULL,
                        reasonType TEXT NOT NULL,
                        description TEXT NOT NULL,
                        attachmentLocalUri TEXT,
                        attachmentRemoteUrl TEXT,
                        status TEXT NOT NULL,
                        submittedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        reviewedBy TEXT,
                        reviewedAt INTEGER,
                        decisionNote TEXT,
                        syncStatus TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        FOREIGN KEY(studentId) REFERENCES students(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(attendanceRecordId) REFERENCES attendance_records(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(lectureId) REFERENCES lectures(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(subjectId) REFERENCES subjects(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )""".trimIndent()
                )
                db.execSQL(
                    """INSERT INTO attendance_appeals(
                        id,studentId,attendanceRecordId,lectureId,subjectId,reasonType,description,
                        attachmentLocalUri,attachmentRemoteUrl,status,submittedAt,updatedAt,reviewedBy,
                        reviewedAt,decisionNote,syncStatus,version
                    )
                    SELECT aa.id,aa.studentId,aa.attendanceRecordId,ar.lectureId,l.subjectId,'OTHER',aa.reason,
                           aa.attachmentPath,NULL,aa.status,aa.submittedAt,
                           COALESCE(aa.reviewedAt,aa.submittedAt),aa.reviewedBy,aa.reviewedAt,aa.decisionNote,
                           'LOCAL_ONLY',1
                    FROM attendance_appeals_legacy aa
                    JOIN attendance_records ar ON ar.id=aa.attendanceRecordId
                    JOIN lectures l ON l.id=ar.lectureId
                    JOIN subjects s ON s.id=l.subjectId
                    JOIN students st ON st.id=aa.studentId""".trimIndent()
                )
                db.execSQL("DROP TABLE attendance_appeals_legacy")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_studentId ON attendance_appeals(studentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_attendanceRecordId ON attendance_appeals(attendanceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_lectureId ON attendance_appeals(lectureId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_subjectId ON attendance_appeals(subjectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_status ON attendance_appeals(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_syncStatus ON attendance_appeals(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_appeals_submittedAt ON attendance_appeals(submittedAt)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS user_scopes (
                    id TEXT NOT NULL PRIMARY KEY,userId TEXT NOT NULL,scopeType TEXT NOT NULL,scopeId TEXT,
                    active INTEGER NOT NULL,createdAt INTEGER NOT NULL,createdBy TEXT,
                    FOREIGN KEY(userId) REFERENCES users(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_scopes_userId ON user_scopes(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_scopes_scopeType_scopeId ON user_scopes(scopeType,scopeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_scopes_active ON user_scopes(active)")

                db.execSQL("""CREATE TABLE IF NOT EXISTS user_permission_grants (
                    id TEXT NOT NULL PRIMARY KEY,userId TEXT NOT NULL,permissionCode TEXT NOT NULL,
                    scopeType TEXT,scopeId TEXT,startsAt INTEGER,expiresAt INTEGER,grantedBy TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,reason TEXT NOT NULL,active INTEGER NOT NULL,revokedAt INTEGER,
                    FOREIGN KEY(userId) REFERENCES users(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_permission_grants_userId ON user_permission_grants(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_permission_grants_permissionCode ON user_permission_grants(permissionCode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_permission_grants_scopeType_scopeId ON user_permission_grants(scopeType,scopeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_permission_grants_active ON user_permission_grants(active)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_permission_grants_expiresAt ON user_permission_grants(expiresAt)")

                db.execSQL("""CREATE TABLE IF NOT EXISTS device_replacement_requests (
                    id TEXT NOT NULL PRIMARY KEY,studentId TEXT NOT NULL,oldDeviceId TEXT,newDevicePublicId TEXT NOT NULL,
                    newPublicKey TEXT,newPresenceSecretCiphertext TEXT,status TEXT NOT NULL,requestedAt INTEGER NOT NULL,
                    reviewedBy TEXT,reviewedAt INTEGER,decisionNote TEXT,version INTEGER NOT NULL,
                    FOREIGN KEY(studentId) REFERENCES students(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_replacement_requests_studentId ON device_replacement_requests(studentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_replacement_requests_status ON device_replacement_requests(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_replacement_requests_requestedAt ON device_replacement_requests(requestedAt)")

                db.execSQL("""CREATE TABLE IF NOT EXISTS weekly_timetable_versions (
                    id TEXT NOT NULL PRIMARY KEY,groupId TEXT NOT NULL,weekStart TEXT NOT NULL,weekEnd TEXT NOT NULL,
                    versionNumber INTEGER NOT NULL,status TEXT NOT NULL,sourceType TEXT NOT NULL,sourceUri TEXT,
                    importedBy TEXT NOT NULL,approvedBy TEXT,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,
                    approvedAt INTEGER,reason TEXT
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_timetable_versions_groupId ON weekly_timetable_versions(groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_timetable_versions_weekStart ON weekly_timetable_versions(weekStart)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_weekly_timetable_versions_status ON weekly_timetable_versions(status)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_weekly_timetable_versions_groupId_weekStart_versionNumber ON weekly_timetable_versions(groupId,weekStart,versionNumber)")

                db.execSQL("ALTER TABLE timetables ADD COLUMN weeklyScheduleId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_timetables_weeklyScheduleId ON timetables(weeklyScheduleId)")
                db.execSQL("ALTER TABLE audit_logs ADD COLUMN actorRole TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_entityType_entityId_timestamp ON audit_logs(entityType,entityId,timestamp)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS sync_entity_metadata (
                    entityType TEXT NOT NULL,entityId TEXT NOT NULL,localVersion INTEGER NOT NULL,
                    firstSeenAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,payloadFingerprint TEXT NOT NULL,
                    lastSyncedServerVersion INTEGER,PRIMARY KEY(entityType,entityId)
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_entity_metadata_entityType ON sync_entity_metadata(entityType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_entity_metadata_updatedAt ON sync_entity_metadata(updatedAt)")
            }
        }
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
