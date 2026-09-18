package com.hammam.attendai.data.local.entity

import androidx.room.*
import com.hammam.attendai.domain.model.*

@Entity(tableName="users", indices=[Index(value=["username"], unique=true)])
data class UserEntity(@PrimaryKey val id:String, val username:String, val displayName:String, val passwordHash:String?=null, val isActive:Boolean=true, val createdAt:Long, val updatedAt:Long, val version:Long=1)
@Entity(tableName="roles", indices=[Index(value=["name"], unique=true)]) data class RoleEntity(@PrimaryKey val id:String, val name:String, val description:String?=null)
@Entity(tableName="permissions", indices=[Index(value=["code"], unique=true)]) data class PermissionEntity(@PrimaryKey val id:String, val code:String, val description:String?=null)
@Entity(tableName="user_roles", primaryKeys=["userId","roleId"], foreignKeys=[ForeignKey(entity=UserEntity::class,parentColumns=["id"],childColumns=["userId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=RoleEntity::class,parentColumns=["id"],childColumns=["roleId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("userId"),Index("roleId")]) data class UserRoleEntity(val userId:String,val roleId:String)
@Entity(tableName="role_permissions", primaryKeys=["roleId","permissionId"], foreignKeys=[ForeignKey(entity=RoleEntity::class,parentColumns=["id"],childColumns=["roleId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=PermissionEntity::class,parentColumns=["id"],childColumns=["permissionId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("roleId"),Index("permissionId")]) data class RolePermissionEntity(val roleId:String,val permissionId:String)
@Entity(tableName="user_scopes", foreignKeys=[ForeignKey(entity=UserEntity::class,parentColumns=["id"],childColumns=["userId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("userId"),Index(value=["scopeType","scopeId"]),Index("active")]) data class UserScopeEntity(@PrimaryKey val id:String,val userId:String,val scopeType:String,val scopeId:String?,val active:Boolean=true,val createdAt:Long,val createdBy:String?)
@Entity(tableName="user_permission_grants", foreignKeys=[ForeignKey(entity=UserEntity::class,parentColumns=["id"],childColumns=["userId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("userId"),Index("permissionCode"),Index(value=["scopeType","scopeId"]),Index("active"),Index("expiresAt")]) data class UserPermissionGrantEntity(@PrimaryKey val id:String,val userId:String,val permissionCode:String,val scopeType:String?,val scopeId:String?,val startsAt:Long?,val expiresAt:Long?,val grantedBy:String,val createdAt:Long,val reason:String,val active:Boolean=true,val revokedAt:Long?=null)

@Entity(tableName="universities") data class UniversityEntity(@PrimaryKey val id:String,val name:String,val archivedAt:Long?=null)
@Entity(tableName="faculties", foreignKeys=[ForeignKey(entity=UniversityEntity::class,parentColumns=["id"],childColumns=["universityId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("universityId")]) data class FacultyEntity(@PrimaryKey val id:String,val universityId:String,val name:String,val archivedAt:Long?=null)
@Entity(tableName="departments", foreignKeys=[ForeignKey(entity=FacultyEntity::class,parentColumns=["id"],childColumns=["facultyId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("facultyId")]) data class DepartmentEntity(@PrimaryKey val id:String,val facultyId:String,val name:String,val archivedAt:Long?=null)
@Entity(tableName="academic_years") data class AcademicYearEntity(@PrimaryKey val id:String,val name:String,val startDate:String,val endDate:String,val isActive:Boolean)
@Entity(tableName="semesters", indices=[Index("academicYearId"),Index("status")]) data class SemesterEntity(@PrimaryKey val id:String,val name:String,val academicYearId:String,val startDate:String,val endDate:String,val status:SemesterStatus,val createdAt:Long,val updatedAt:Long)
@Entity(tableName="levels", indices=[Index("departmentId")]) data class LevelEntity(@PrimaryKey val id:String,val departmentId:String,val name:String,val orderIndex:Int,val archivedAt:Long?=null)
@Entity(tableName="batches", indices=[Index("levelId")]) data class BatchEntity(@PrimaryKey val id:String,val levelId:String,val name:String,val academicYearId:String,val archivedAt:Long?=null)
@Entity(tableName="sections", indices=[Index("batchId")]) data class SectionEntity(@PrimaryKey val id:String,val batchId:String,val name:String,val archivedAt:Long?=null)
@Entity(tableName="groups", indices=[Index("sectionId")]) data class GroupEntity(@PrimaryKey val id:String,val sectionId:String,val name:String,val archivedAt:Long?=null)

@Entity(tableName="students", indices=[Index(value=["universityNumber"],unique=true),Index("normalizedName"),Index("levelId"),Index("batchId"),Index("sectionId"),Index("groupId"),Index("status")])
data class StudentEntity(@PrimaryKey val id:String,val universityNumber:String?,val fullName:String,val normalizedName:String,val phoneNumber:String?,val whatsappNumber:String?,val levelId:String?,val batchId:String?,val sectionId:String?,val groupId:String?,val status:StudentStatus,val registeredDeviceId:String?,val createdAt:Long,val updatedAt:Long,val archivedAt:Long?=null,val version:Long=1)
@Entity(tableName="student_devices", indices=[Index("studentId"),Index(value=["devicePublicId"],unique=true),Index("status")]) data class StudentDeviceEntity(@PrimaryKey val id:String,val studentId:String,val devicePublicId:String,val publicKey:String?,val presenceSecretCiphertext:String?,val registeredAt:Long,val lastSeen:Long?,val status:DeviceStatus,val version:Long=1)
@Entity(tableName="device_replacement_requests", foreignKeys=[ForeignKey(entity=StudentEntity::class,parentColumns=["id"],childColumns=["studentId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("studentId"),Index("status"),Index("requestedAt")]) data class DeviceReplacementRequestEntity(@PrimaryKey val id:String,val studentId:String,val oldDeviceId:String?,val newDevicePublicId:String,val newPublicKey:String?,val newPresenceSecretCiphertext:String?,val status:String,val requestedAt:Long,val reviewedBy:String?,val reviewedAt:Long?,val decisionNote:String?,val version:Long=1)
@Entity(tableName="teachers", indices=[Index("normalizedName")]) data class TeacherEntity(@PrimaryKey val id:String,val fullName:String,val normalizedName:String,val phone:String?,val whatsapp:String?,val email:String?,val preferredNotificationChannel:String?,val notificationsEnabled:Boolean=true,val createdAt:Long,val updatedAt:Long,val archivedAt:Long?=null,val version:Long=1)

@Entity(tableName="attendance_policies") data class AttendancePolicyEntity(@PrimaryKey val id:String,val name:String,val scopeType:String,val scopeId:String?,val fullAttendanceThreshold:Double,val partialAttendanceThreshold:Double,val lateAfterMinutes:Int,val earlyLeaveThresholdMinutes:Int,val absenceThreshold:Double,val temporaryMissingGraceSeconds:Long,val minimumPresenceVerificationSeconds:Long,val confidenceThreshold:Double,val createdAt:Long,val updatedAt:Long,val version:Long=1)
@Entity(tableName="subjects", indices=[Index("code"),Index("teacherId"),Index("levelId"),Index("semesterId"),Index("groupId")]) data class SubjectEntity(@PrimaryKey val id:String,val code:String,val name:String,val teacherId:String?,val levelId:String,val semesterId:String,val groupId:String,val attendancePolicyId:String?,val status:String,val archivedAt:Long?=null,val version:Long=1)
@Entity(tableName="teacher_subjects", primaryKeys=["teacherId","subjectId"], indices=[Index("teacherId"),Index("subjectId")]) data class TeacherSubjectEntity(val teacherId:String,val subjectId:String)
@Entity(tableName="timetables", indices=[Index("subjectId"),Index("teacherId"),Index("groupId"),Index("dayOfWeek"),Index("weeklyScheduleId")]) data class TimetableEntity(@PrimaryKey val id:String,val dayOfWeek:Int,val startTime:String,val endTime:String,val room:String?,val subjectId:String,val teacherId:String,val groupId:String,val lectureType:String,val scheduleKind:String,val isActive:Boolean=true,val createdAt:Long,val updatedAt:Long,val version:Long=1,val weeklyScheduleId:String?=null)
@Entity(tableName="weekly_timetable_versions", indices=[Index("groupId"),Index("weekStart"),Index("status"),Index(value=["groupId","weekStart","versionNumber"],unique=true)]) data class WeeklyTimetableVersionEntity(@PrimaryKey val id:String,val groupId:String,val weekStart:String,val weekEnd:String,val versionNumber:Int,val status:String,val sourceType:String,val sourceUri:String?,val importedBy:String,val approvedBy:String?,val createdAt:Long,val updatedAt:Long,val approvedAt:Long?,val reason:String?=null)
@Entity(tableName="lectures", indices=[Index("subjectId"),Index("teacherId"),Index("semesterId"),Index("groupId"),Index("scheduledStart"),Index("status")]) data class LectureEntity(@PrimaryKey val id:String,val subjectId:String,val teacherId:String,val semesterId:String,val groupId:String,val scheduledStart:Long,val scheduledEnd:Long,val actualStart:Long?,val actualEnd:Long?,val room:String?,val status:LectureStatus,val attendancePolicySnapshotJson:String,val createdAt:Long,val updatedAt:Long,val version:Long=1)
@Entity(tableName="attendance_sessions", indices=[Index("lectureId"),Index("status")]) data class AttendanceSessionEntity(@PrimaryKey val id:String,val lectureId:String,val startedBy:String,val startedAt:Long,val endedAt:Long?,val status:String,val activeDetector:String,val restoredAfterCrash:Boolean=false,val version:Long=1)
@Entity(tableName="attendance_records", indices=[Index("lectureId"),Index("studentId"),Index(value=["lectureId","studentId"],unique=true),Index("finalStatus"),Index("updatedAt")]) data class AttendanceRecordEntity(@PrimaryKey val id:String,val lectureId:String,val studentId:String,val firstSeenAt:Long?,val lastSeenAt:Long?,val verifiedPresenceSeconds:Long,val lectureDurationSeconds:Long,val attendancePercentage:Double,val lateMinutes:Int,val earlyLeaveMinutes:Int,val confidenceScore:Double,val finalStatus:FinalAttendanceStatus,val approvalStatus:ApprovalStatus,val source:PresenceSource,val notes:String?,val createdAt:Long,val updatedAt:Long,val version:Long=1)
@Entity(tableName="presence_intervals", indices=[Index("attendanceRecordId"),Index("sessionId"),Index("studentId")]) data class PresenceIntervalEntity(@PrimaryKey val id:String,val attendanceRecordId:String,val sessionId:String,val studentId:String,val startAt:Long,val endAt:Long?,val source:PresenceSource,val confidence:Double,val createdAt:Long)
@Entity(tableName="presence_events", indices=[Index("attendanceSessionId"),Index("studentId"),Index("deviceId"),Index("timestamp"),Index("eventType")]) data class PresenceEventEntity(@PrimaryKey val id:String,val attendanceSessionId:String,val studentId:String,val deviceId:String?,val eventType:PresenceEventType,val timestamp:Long,val source:PresenceSource,val signalStrength:Int?,val confidence:Double,val metadata:String?,val createdAt:Long)

@Entity(
    tableName="attendance_appeals",
    foreignKeys=[
        ForeignKey(entity=StudentEntity::class,parentColumns=["id"],childColumns=["studentId"],onDelete=ForeignKey.RESTRICT),
        ForeignKey(entity=AttendanceRecordEntity::class,parentColumns=["id"],childColumns=["attendanceRecordId"],onDelete=ForeignKey.RESTRICT),
        ForeignKey(entity=LectureEntity::class,parentColumns=["id"],childColumns=["lectureId"],onDelete=ForeignKey.RESTRICT),
        ForeignKey(entity=SubjectEntity::class,parentColumns=["id"],childColumns=["subjectId"],onDelete=ForeignKey.RESTRICT)
    ],
    indices=[Index("studentId"),Index("attendanceRecordId"),Index("lectureId"),Index("subjectId"),Index("status"),Index("syncStatus"),Index("submittedAt")]
)
data class AttendanceAppealEntity(
    @PrimaryKey val id:String,
    val studentId:String,
    val attendanceRecordId:String,
    val lectureId:String,
    val subjectId:String,
    val reasonType:String,
    val description:String,
    val attachmentLocalUri:String?,
    val attachmentRemoteUrl:String?,
    val status:AppealStatus,
    val submittedAt:Long,
    val updatedAt:Long,
    val reviewedBy:String?,
    val reviewedAt:Long?,
    val decisionNote:String?,
    val syncStatus:AppealSyncStatus,
    val version:Long=1
)
@Entity(tableName="excused_absences", indices=[Index("studentId"),Index("attendanceRecordId")]) data class ExcusedAbsenceEntity(@PrimaryKey val id:String,val studentId:String,val attendanceRecordId:String,val reason:String,val notes:String?,val attachmentPath:String?,val approvedBy:String?,val approvedAt:Long?,val createdAt:Long)

@Entity(tableName="notifications", indices=[Index("recipientType"),Index("recipientId"),Index("status"),Index(value=["deduplicationKey"],unique=true),Index("scheduledAt")]) data class NotificationEntity(@PrimaryKey val id:String,val recipientType:String,val recipientId:String,val channel:String,val template:String,val payloadCiphertext:String,val status:QueueStatus,val scheduledAt:Long,val sentAt:Long?,val retryCount:Int,val error:String?,val deduplicationKey:String,val version:Long=1)
@Entity(tableName="notification_templates", indices=[Index(value=["code"],unique=true)]) data class NotificationTemplateEntity(@PrimaryKey val id:String,val code:String,val channel:String,val body:String,val isActive:Boolean=true,val updatedAt:Long)
@Entity(tableName="teacher_report_settings", indices=[Index("teacherId"),Index("subjectId")]) data class TeacherReportSettingEntity(@PrimaryKey val id:String,val teacherId:String,val subjectId:String?,val enabled:Boolean,val frequency:String,val sendTime:String,val weeklyDay:Int?,val monthlyDay:Int?,val semesterReportEnabled:Boolean,val customRule:String?,val timezone:String,val channel:String,val reportFormat:String,val includeStudentDetails:Boolean,val requireApproval:Boolean,val aiSummaryEnabled:Boolean,val sendIfNoLecture:Boolean=false,val updatedAt:Long,val version:Long=1)
@Entity(tableName="report_jobs", indices=[Index("teacherId"),Index("subjectId"),Index("status"),Index(value=["deduplicationKey"],unique=true),Index("scheduledAt")]) data class ReportJobEntity(@PrimaryKey val id:String,val teacherId:String,val subjectId:String?,val reportType:String,val periodStart:Long,val periodEnd:Long,val scheduledAt:Long,val generatedAt:Long?,val sentAt:Long?,val status:ReportJobStatus,val retryCount:Int,val providerMessageId:String?,val errorMessage:String?,val deduplicationKey:String,val version:Long=1)
@Entity(tableName="generated_reports", indices=[Index("reportJobId"),Index(value=["hash"],unique=true)]) data class GeneratedReportEntity(@PrimaryKey val id:String,val reportJobId:String,val format:String,val filePath:String,val generatedAt:Long,val hash:String,val sizeBytes:Long)

@Entity(tableName="audit_logs", indices=[Index("actorId"),Index("entityType"),Index("entityId"),Index("timestamp"),Index("action"),Index(value=["entityType","entityId","timestamp"])]) data class AuditLogEntity(@PrimaryKey val id:String,val actorId:String?,val action:String,val entityType:String,val entityId:String?,val oldData:String?,val newData:String?,val reason:String?,val timestamp:Long,val actorRole:String?=null)
@Entity(tableName="sync_queue", indices=[Index("entityType"),Index("entityId"),Index("status"),Index("createdAt")]) data class SyncQueueEntity(@PrimaryKey val id:String,val entityType:String,val entityId:String,val operation:String,val payloadCiphertext:String,val createdAt:Long,val lastAttempt:Long?,val retryCount:Int,val status:QueueStatus,val error:String?,val idempotencyKey:String,val version:Long=1)
@Entity(tableName="app_settings", indices=[Index(value=["key"],unique=true)]) data class AppSettingEntity(@PrimaryKey val key:String,val valueCiphertext:String,val updatedAt:Long)
@Entity(tableName="feature_flags", indices=[Index(value=["code"],unique=true)]) data class FeatureFlagEntity(@PrimaryKey val id:String,val code:String,val enabled:Boolean,val updatedAt:Long)
@Entity(tableName="backup_history", indices=[Index("createdAt"),Index("status")]) data class BackupHistoryEntity(@PrimaryKey val id:String,val createdAt:Long,val fileName:String,val sizeBytes:Long,val databaseVersion:Int,val status:String,val checksum:String?,val error:String?)
