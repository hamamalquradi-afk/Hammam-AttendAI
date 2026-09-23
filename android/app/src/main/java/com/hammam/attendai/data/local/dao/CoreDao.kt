package com.hammam.attendai.data.local.dao

import androidx.room.*
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.domain.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CoreDao {

    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertUser(v:UserEntity):Long
    @Update suspend fun updateUser(v:UserEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertRole(v:RoleEntity):Long
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertPermission(v:PermissionEntity):Long
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertUserRole(v:UserRoleEntity):Long
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertRolePermission(v:RolePermissionEntity):Long
    @Query("SELECT id FROM roles WHERE name=:name LIMIT 1") suspend fun getRoleIdByName(name:String):String?
    @Query("SELECT id FROM permissions WHERE code=:code LIMIT 1") suspend fun getPermissionIdByCode(code:String):String?
    @Query("SELECT * FROM users WHERE id=:id LIMIT 1") suspend fun getUserById(id:String):UserEntity?
    @Query("SELECT * FROM users WHERE id=:id LIMIT 1") fun observeUserById(id:String):Flow<UserEntity?>
    @Query("SELECT COUNT(*) > 0 FROM users WHERE id=:userId AND isActive=1") suspend fun isUserActive(userId:String):Boolean
    @Query("""SELECT u.id AS id,u.displayName AS displayName,u.isActive AS isActive,CASE WHEN MAX(CASE WHEN r.name='SYSTEM_OWNER' THEN 1 ELSE 0 END)=1 THEN 'SYSTEM_OWNER' ELSE MIN(r.name) END AS roleName FROM users u LEFT JOIN user_roles ur ON ur.userId=u.id LEFT JOIN roles r ON r.id=ur.roleId GROUP BY u.id,u.displayName,u.isActive ORDER BY u.displayName""") fun observeUserAccessRows():Flow<List<UserAccessRow>>
    @Query("SELECT * FROM roles ORDER BY name") fun observeRoles():Flow<List<RoleEntity>>
    @Query("SELECT * FROM permissions ORDER BY code") fun observePermissions():Flow<List<PermissionEntity>>
    @Query("DELETE FROM user_roles WHERE userId=:userId") suspend fun clearUserRoles(userId:String)
    @Query("SELECT COUNT(*) FROM users") suspend fun countUsers():Int
    @Query("""SELECT u.* FROM users u JOIN user_roles ur ON ur.userId=u.id JOIN roles r ON r.id=ur.roleId WHERE r.name='SYSTEM_OWNER' AND u.isActive=1 ORDER BY u.createdAt LIMIT 1""") suspend fun getSystemOwnerUser():UserEntity?
    @Query("""SELECT u.* FROM users u JOIN user_roles ur ON ur.userId=u.id JOIN roles r ON r.id=ur.roleId WHERE r.name='SYSTEM_OWNER' ORDER BY u.createdAt LIMIT 1""") suspend fun getAnySystemOwnerUser():UserEntity?
    @Query("""SELECT u.* FROM users u JOIN user_roles ur ON ur.userId=u.id JOIN roles r ON r.id=ur.roleId WHERE r.name='Administrator' AND u.isActive=1 ORDER BY u.createdAt LIMIT 1""") suspend fun getActiveAdministratorUser():UserEntity?
    @Query("SELECT DISTINCT r.name FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId ORDER BY r.name") suspend fun getRoleNames(userId:String):List<String>
    @Query("SELECT r.name AS roleName,p.code AS permissionCode FROM role_permissions rp JOIN roles r ON r.id=rp.roleId JOIN permissions p ON p.id=rp.permissionId ORDER BY r.name,p.code") suspend fun getRolePermissionExport():List<RolePermissionExportRow>
    @Query("SELECT DISTINCT r.name FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId ORDER BY r.name") fun observeRoleNames(userId:String):Flow<List<String>>
    @Query("SELECT * FROM user_scopes WHERE userId=:userId AND active=1 ORDER BY createdAt") suspend fun getActiveUserScopes(userId:String):List<UserScopeEntity>
    @Query("SELECT * FROM user_scopes WHERE userId=:userId AND active=1 ORDER BY createdAt") fun observeActiveUserScopes(userId:String):Flow<List<UserScopeEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertUserScope(v:UserScopeEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertPermissionGrant(v:UserPermissionGrantEntity)
    @Update suspend fun updatePermissionGrant(v:UserPermissionGrantEntity)
    @Query("SELECT * FROM user_permission_grants WHERE userId=:userId ORDER BY createdAt DESC") fun observePermissionGrants(userId:String):Flow<List<UserPermissionGrantEntity>>
    @Query("SELECT * FROM user_permission_grants WHERE id=:id LIMIT 1") suspend fun getPermissionGrant(id:String):UserPermissionGrantEntity?
    @Query("""SELECT COUNT(*) > 0 FROM user_permission_grants g JOIN users u ON u.id=g.userId
        WHERE g.userId=:userId AND g.permissionCode=:permissionCode AND g.active=1 AND u.isActive=1
        AND (g.startsAt IS NULL OR g.startsAt<=:now) AND (g.expiresAt IS NULL OR g.expiresAt>:now)
        AND (:scopeType IS NULL OR g.scopeType IS NULL OR (g.scopeType=:scopeType AND (g.scopeId IS NULL OR g.scopeId=:scopeId)))""")
    suspend fun hasActivePermissionGrant(userId:String,permissionCode:String,now:Long,scopeType:String?,scopeId:String?):Boolean
    @Query("SELECT * FROM user_permission_grants WHERE active=1 AND expiresAt IS NOT NULL AND expiresAt<=:now") suspend fun getExpiredPermissionGrants(now:Long):List<UserPermissionGrantEntity>
    @Query("""SELECT COUNT(*) > 0 FROM user_roles ur
        JOIN role_permissions rp ON rp.roleId=ur.roleId
        JOIN permissions p ON p.id=rp.permissionId
        JOIN users u ON u.id=ur.userId
        WHERE ur.userId=:userId AND p.code=:permissionCode AND u.isActive=1""") suspend fun hasPermission(userId:String,permissionCode:String):Boolean
    @Query("""SELECT code FROM (
        SELECT DISTINCT p.code AS code FROM user_roles ur
        JOIN role_permissions rp ON rp.roleId=ur.roleId JOIN permissions p ON p.id=rp.permissionId
        JOIN users u ON u.id=ur.userId WHERE ur.userId=:userId AND u.isActive=1
        UNION
        SELECT DISTINCT g.permissionCode AS code FROM user_permission_grants g JOIN users u2 ON u2.id=g.userId
        WHERE g.userId=:userId AND g.active=1 AND u2.isActive=1
        AND (g.startsAt IS NULL OR g.startsAt<=CAST(strftime('%s','now') AS INTEGER)*1000)
        AND (g.expiresAt IS NULL OR g.expiresAt>CAST(strftime('%s','now') AS INTEGER)*1000)
    ) ORDER BY code""") fun observePermissionCodes(userId:String):Flow<List<String>>
    @Query("SELECT * FROM students WHERE archivedAt IS NULL ORDER BY normalizedName") fun observeStudents(): Flow<List<StudentEntity>>
    @Query("SELECT * FROM students ORDER BY id") suspend fun getStudentsOnce():List<StudentEntity>
    @Query("""SELECT DISTINCT st.* FROM students st WHERE st.archivedAt IS NULL AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND (
            (us.scopeType='STUDENT' AND us.scopeId=st.id) OR
            (us.scopeType='GROUP' AND us.scopeId=st.groupId) OR
            (us.scopeType='SECTION' AND us.scopeId=st.sectionId) OR
            (us.scopeType='BATCH' AND us.scopeId=st.batchId) OR
            (us.scopeType='LEVEL' AND us.scopeId=st.levelId)
        ))
        OR EXISTS(SELECT 1 FROM user_scopes us JOIN subjects sub ON us.scopeType='TEACHER' AND us.scopeId=sub.teacherId
                  WHERE us.userId=:userId AND us.active=1 AND sub.archivedAt IS NULL AND sub.groupId=st.groupId)
    ) ORDER BY st.normalizedName""") fun observeScopedStudents(userId:String):Flow<List<StudentEntity>>
    @Query("""SELECT COUNT(*) > 0 FROM students st WHERE st.id=:studentId AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND ((us.scopeType='STUDENT' AND us.scopeId=st.id) OR (us.scopeType='GROUP' AND us.scopeId=st.groupId) OR (us.scopeType='SECTION' AND us.scopeId=st.sectionId) OR (us.scopeType='BATCH' AND us.scopeId=st.batchId) OR (us.scopeType='LEVEL' AND us.scopeId=st.levelId)))
        OR EXISTS(SELECT 1 FROM user_scopes us JOIN subjects sub ON us.scopeType='TEACHER' AND us.scopeId=sub.teacherId WHERE us.userId=:userId AND us.active=1 AND sub.archivedAt IS NULL AND sub.groupId=st.groupId)
    )""") suspend fun canAccessStudent(userId:String,studentId:String):Boolean
    @Query("SELECT st.* FROM students st JOIN user_scopes us ON us.scopeType='STUDENT' AND us.scopeId=st.id JOIN users u ON u.id=us.userId WHERE us.userId=:userId AND us.active=1 AND u.isActive=1 LIMIT 1") suspend fun getStudentForUser(userId:String):StudentEntity?
    @Query("""SELECT COUNT(*) > 0 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels l ON l.id=b.levelId WHERE g.id=:groupId AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND EXISTS(
        SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND (
            (us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR
            (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=l.id)
        )
    )""") suspend fun canAccessGroupScope(userId:String,groupId:String):Boolean
    @Query("SELECT COUNT(*) > 0 FROM user_scopes us JOIN users u ON u.id=us.userId WHERE us.userId=:userId AND us.active=1 AND u.isActive=1 AND us.scopeType=:scopeType AND us.scopeId=:scopeId") suspend fun hasExactScope(userId:String,scopeType:String,scopeId:String):Boolean
    @Query("SELECT * FROM students WHERE archivedAt IS NULL AND (normalizedName LIKE '%' || :q || '%' OR universityNumber LIKE '%' || :q || '%') ORDER BY normalizedName LIMIT 100") fun searchStudents(q:String): Flow<List<StudentEntity>>
    @Query("SELECT * FROM students WHERE archivedAt IS NULL AND (normalizedName LIKE '%' || :q || '%' OR universityNumber LIKE '%' || :q || '%') ORDER BY normalizedName LIMIT 50") suspend fun searchStudentsOnce(q:String):List<StudentEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertStudent(student:StudentEntity)
    @Update suspend fun updateStudent(student:StudentEntity)
    @Query("SELECT * FROM students WHERE id=:id LIMIT 1") suspend fun getStudentById(id:String):StudentEntity?
    @Query("SELECT * FROM students WHERE universityNumber=:number LIMIT 1") suspend fun getStudentByUniversityNumber(number:String):StudentEntity?
    @Query("SELECT * FROM students WHERE groupId=:groupId AND archivedAt IS NULL AND status='ACTIVE' ORDER BY normalizedName") suspend fun getActiveStudentsForGroup(groupId:String):List<StudentEntity>
    @Query("SELECT * FROM student_devices WHERE status='ACTIVE'") suspend fun getActiveStudentDevices():List<StudentDeviceEntity>
    @Query("SELECT * FROM student_devices WHERE id=:id LIMIT 1") suspend fun getStudentDevice(id:String):StudentDeviceEntity?
    @Query("SELECT * FROM student_devices WHERE studentId=:studentId AND status='ACTIVE' ORDER BY registeredAt DESC") suspend fun getActiveDevicesForStudent(studentId:String):List<StudentDeviceEntity>
    @Query("SELECT * FROM student_devices WHERE studentId=:studentId ORDER BY registeredAt DESC") fun observeStudentDevices(studentId:String):Flow<List<StudentDeviceEntity>>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertStudentDevice(v:StudentDeviceEntity)
    @Update suspend fun updateStudentDevice(v:StudentDeviceEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertDeviceReplacementRequest(v:DeviceReplacementRequestEntity)
    @Update suspend fun updateDeviceReplacementRequest(v:DeviceReplacementRequestEntity)
    @Query("SELECT * FROM device_replacement_requests WHERE id=:id LIMIT 1") suspend fun getDeviceReplacementRequest(id:String):DeviceReplacementRequestEntity?
    @Query("SELECT * FROM device_replacement_requests WHERE studentId=:studentId AND status='PENDING' ORDER BY requestedAt DESC LIMIT 1") suspend fun getPendingDeviceReplacementRequestForStudent(studentId:String):DeviceReplacementRequestEntity?
    @Query("SELECT * FROM device_replacement_requests WHERE studentId=:studentId ORDER BY requestedAt DESC") fun observeDeviceReplacementRequests(studentId:String):Flow<List<DeviceReplacementRequestEntity>>
    @Query("SELECT * FROM device_replacement_requests WHERE status='PENDING' ORDER BY requestedAt") fun observePendingDeviceReplacementRequests():Flow<List<DeviceReplacementRequestEntity>>

    @Query("SELECT * FROM lectures WHERE status='ACTIVE' ORDER BY actualStart DESC LIMIT 1") fun observeActiveLecture(): Flow<LectureEntity?>
    @Query("SELECT * FROM lectures WHERE status='ACTIVE' AND groupId=:groupId ORDER BY actualStart DESC LIMIT 1") fun observeActiveLectureForGroup(groupId:String):Flow<LectureEntity?>
    @Query("""SELECT DISTINCT l.* FROM lectures l WHERE l.status='ACTIVE' AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=l.teacherId)
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId
            JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=l.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY l.actualStart DESC LIMIT 1""") fun observeScopedActiveLecture(userId:String):Flow<LectureEntity?>
    @Query("""SELECT DISTINCT l.* FROM lectures l WHERE l.status='NEEDS_REVIEW' AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=l.teacherId)
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId
            JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=l.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY COALESCE(l.actualEnd,l.updatedAt) DESC LIMIT 1""") fun observeScopedReviewLecture(userId:String):Flow<LectureEntity?>
    @Query("""SELECT DISTINCT l.* FROM lectures l WHERE l.status IN ('COMPLETED','FROZEN') AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=l.teacherId)
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=l.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY COALESCE(l.actualEnd,l.scheduledEnd) DESC LIMIT 1""") suspend fun getLatestReportEligibleLectureForUser(userId:String):LectureEntity?
    @Query("SELECT * FROM attendance_sessions WHERE status='ACTIVE' ORDER BY startedAt DESC LIMIT 1") suspend fun getActiveSession(): AttendanceSessionEntity?
    @Query("SELECT COUNT(*) FROM attendance_sessions WHERE status='ACTIVE'") suspend fun countActiveSessions():Int
    @Query("SELECT * FROM attendance_sessions WHERE id=:id LIMIT 1") suspend fun getSessionById(id:String):AttendanceSessionEntity?
    @Query("SELECT * FROM lectures WHERE status IN ('READY','SCHEDULED') ORDER BY scheduledStart LIMIT 1") suspend fun getNextLecture(): LectureEntity?
    @Query("""SELECT l.* FROM lectures l JOIN subjects s ON s.id=l.subjectId JOIN groups g ON g.id=l.groupId JOIN semesters sem ON sem.id=l.semesterId JOIN teachers t ON t.id=l.teacherId JOIN attendance_policies p ON p.id=s.attendancePolicyId WHERE l.status IN ('READY','SCHEDULED') AND l.scheduledStart<=:now AND l.scheduledEnd>:now AND l.scheduledEnd>l.scheduledStart AND s.status='ACTIVE' AND s.archivedAt IS NULL AND s.groupId=l.groupId AND s.semesterId=l.semesterId AND g.archivedAt IS NULL AND sem.status='ACTIVE' AND t.archivedAt IS NULL ORDER BY l.scheduledStart LIMIT 1""") suspend fun getStartableLecture(now:Long):LectureEntity?
    @Query("""SELECT DISTINCT l.* FROM lectures l JOIN subjects s ON s.id=l.subjectId JOIN groups g ON g.id=l.groupId JOIN semesters sem ON sem.id=l.semesterId JOIN teachers t ON t.id=l.teacherId JOIN attendance_policies p ON p.id=s.attendancePolicyId WHERE l.status IN ('READY','SCHEDULED') AND l.scheduledStart<=:now AND l.scheduledEnd>:now AND l.scheduledEnd>l.scheduledStart AND s.status='ACTIVE' AND s.archivedAt IS NULL AND s.groupId=l.groupId AND s.semesterId=l.semesterId AND g.archivedAt IS NULL AND sem.status='ACTIVE' AND t.archivedAt IS NULL AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=l.teacherId)
        OR EXISTS(SELECT 1 FROM groups gx JOIN sections sec ON sec.id=gx.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE gx.id=l.groupId AND ((us.scopeType='GROUP' AND us.scopeId=gx.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY l.scheduledStart LIMIT 1""") suspend fun getStartableLectureForUser(userId:String,now:Long):LectureEntity?
    @Query("""SELECT DISTINCT l.* FROM lectures l WHERE l.status IN ('READY','SCHEDULED') AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=l.teacherId)
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=l.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY l.scheduledStart LIMIT 1""") suspend fun getNextLectureForUser(userId:String):LectureEntity?
    @Query("""SELECT DISTINCT l.* FROM lectures l WHERE l.status IN ('READY','SCHEDULED') AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=l.teacherId)
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=l.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
        OR EXISTS(SELECT 1 FROM user_scopes us JOIN students st ON us.scopeType='STUDENT' AND us.scopeId=st.id WHERE us.userId=:userId AND us.active=1 AND st.groupId=l.groupId)
    ) AND l.scheduledEnd>:now ORDER BY l.scheduledStart LIMIT 1""") fun observeNextLectureForUser(userId:String,now:Long):Flow<LectureEntity?>
    @Query("""SELECT DISTINCT s.* FROM subjects s WHERE s.archivedAt IS NULL AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=s.teacherId)
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='GROUP' AND us.scopeId=s.groupId)
    ) ORDER BY s.name""") fun observeScopedSubjects(userId:String):Flow<List<SubjectEntity>>
    @Query("""SELECT DISTINCT w.* FROM weekly_timetable_versions w WHERE EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=w.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
        OR EXISTS(SELECT 1 FROM user_scopes us JOIN students st ON us.scopeType='STUDENT' AND us.scopeId=st.id WHERE us.userId=:userId AND us.active=1 AND st.groupId=w.groupId)
    ) ORDER BY w.weekStart DESC,w.versionNumber DESC LIMIT 1""") fun observeLatestScopedWeeklyVersion(userId:String):Flow<WeeklyTimetableVersionEntity?>
    @Query("SELECT * FROM timetables WHERE isActive=1 ORDER BY dayOfWeek,startTime") suspend fun getActiveTimetables():List<TimetableEntity>
    @Query("SELECT * FROM timetables WHERE weeklyScheduleId=:scheduleId ORDER BY dayOfWeek,startTime") suspend fun getTimetablesForWeeklySchedule(scheduleId:String):List<TimetableEntity>
    @Query("DELETE FROM timetables WHERE weeklyScheduleId=:scheduleId AND isActive=0") suspend fun deleteInactiveTimetablesForWeeklySchedule(scheduleId:String)
    @Query("SELECT * FROM timetables WHERE subjectId=:subjectId ORDER BY dayOfWeek,startTime") suspend fun getTimetablesForSubject(subjectId:String):List<TimetableEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertTimetable(v:TimetableEntity)
    @Update suspend fun updateTimetable(v:TimetableEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertWeeklyTimetableVersion(v:WeeklyTimetableVersionEntity)
    @Update suspend fun updateWeeklyTimetableVersion(v:WeeklyTimetableVersionEntity)
    @Query("SELECT * FROM weekly_timetable_versions WHERE id=:id LIMIT 1") suspend fun getWeeklyTimetableVersion(id:String):WeeklyTimetableVersionEntity?
    @Query("SELECT * FROM weekly_timetable_versions WHERE groupId=:groupId ORDER BY weekStart DESC,versionNumber DESC") fun observeWeeklyTimetableVersions(groupId:String):Flow<List<WeeklyTimetableVersionEntity>>
    @Query("SELECT * FROM weekly_timetable_versions WHERE status='APPROVED' OR status='ACTIVE' ORDER BY weekStart DESC,versionNumber DESC") suspend fun getApprovedWeeklyTimetableVersions():List<WeeklyTimetableVersionEntity>
    @Query("SELECT COALESCE(MAX(versionNumber),0) FROM weekly_timetable_versions WHERE groupId=:groupId AND weekStart=:weekStart") suspend fun maxWeeklyTimetableVersion(groupId:String,weekStart:String):Int
    @Query("SELECT * FROM weekly_timetable_versions WHERE groupId=:groupId AND weekStart=:weekStart AND status IN ('APPROVED','ACTIVE') ORDER BY versionNumber DESC") suspend fun getActiveWeeklyVersions(groupId:String,weekStart:String):List<WeeklyTimetableVersionEntity>
    @Query("SELECT COUNT(*) FROM weekly_timetable_versions WHERE groupId=:groupId AND weekStart=:weekStart AND status IN ('APPROVED','ACTIVE')") suspend fun countApprovedWeeklyVersion(groupId:String,weekStart:String):Int
    @Query("SELECT id FROM groups WHERE archivedAt IS NULL") suspend fun getActiveGroupIds():List<String>
    @Query("UPDATE timetables SET isActive=0, updatedAt=:now, version=version+1 WHERE groupId=:groupId AND isActive=1") suspend fun deactivateTimetablesForGroup(groupId:String,now:Long)
    @Query("""UPDATE timetables SET isActive=0, updatedAt=:now, version=version+1 WHERE groupId=:groupId AND isActive=1 AND weeklyScheduleId IN (SELECT id FROM weekly_timetable_versions WHERE groupId=:groupId AND weekStart=:weekStart)""") suspend fun deactivateTimetablesForWeek(groupId:String,weekStart:String,now:Long)
    @Query("SELECT * FROM lectures WHERE groupId=:groupId AND scheduledStart>=:from AND scheduledStart<:until AND status IN ('SCHEDULED','READY') ORDER BY scheduledStart,id") suspend fun getFutureScheduledLectures(groupId:String,from:Long,until:Long):List<LectureEntity>
    @Query("UPDATE lectures SET status='CANCELLED', updatedAt=:now, version=version+1 WHERE groupId=:groupId AND scheduledStart>=:from AND scheduledStart<:until AND status IN ('SCHEDULED','READY')") suspend fun cancelFutureScheduledLectures(groupId:String,from:Long,until:Long,now:Long)
    @Query("SELECT COUNT(*) FROM lectures WHERE subjectId=:subjectId AND groupId=:groupId AND scheduledStart=:scheduledStart AND status!='CANCELLED'") suspend fun countNonCancelledLectureAt(subjectId:String,groupId:String,scheduledStart:Long):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertLecture(v:LectureEntity)
    @Query("SELECT * FROM lectures WHERE id=:id LIMIT 1") suspend fun getLectureById(id:String):LectureEntity?
    @Query("SELECT * FROM lectures ORDER BY id") suspend fun getLecturesOnce():List<LectureEntity>
    @Query("SELECT * FROM subjects WHERE id=:id LIMIT 1") suspend fun getSubjectById(id:String):SubjectEntity?
    @Query("SELECT * FROM subjects WHERE code=:code AND archivedAt IS NULL LIMIT 1") suspend fun getSubjectByCode(code:String):SubjectEntity?
    @Query("SELECT * FROM subjects WHERE semesterId=:semesterId AND archivedAt IS NULL ORDER BY name") suspend fun getSubjectsForSemester(semesterId:String):List<SubjectEntity>
    @Query("SELECT * FROM subjects WHERE archivedAt IS NULL AND (name LIKE '%' || :q || '%' OR code LIKE '%' || :q || '%') ORDER BY name LIMIT 20") suspend fun searchSubjectsOnce(q:String):List<SubjectEntity>
    @Query("SELECT * FROM attendance_policies WHERE id=:id LIMIT 1") suspend fun getAttendancePolicyById(id:String):AttendancePolicyEntity?
    @Query("SELECT * FROM attendance_policies ORDER BY name") suspend fun getAttendancePolicies():List<AttendancePolicyEntity>
    @Query("SELECT * FROM attendance_policies ORDER BY name") fun observeAttendancePolicies():Flow<List<AttendancePolicyEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertAttendancePolicy(v:AttendancePolicyEntity)
    @Query("SELECT * FROM teachers WHERE id=:id LIMIT 1") suspend fun getTeacherById(id:String):TeacherEntity?
    @Query("SELECT * FROM teachers WHERE archivedAt IS NULL AND normalizedName=:normalized LIMIT 1") suspend fun getTeacherByNormalizedName(normalized:String):TeacherEntity?
    @Query("SELECT * FROM semesters WHERE id=:id LIMIT 1") suspend fun getSemesterById(id:String):SemesterEntity?
    @Query("SELECT * FROM semesters ORDER BY startDate DESC") fun observeSemesters():Flow<List<SemesterEntity>>
    @Query("SELECT * FROM semesters ORDER BY startDate DESC") suspend fun getSemestersOnce():List<SemesterEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertSemester(v:SemesterEntity)
    @Update suspend fun updateSemester(v:SemesterEntity)
    @Query("SELECT COUNT(*) FROM lectures WHERE semesterId=:semesterId AND status='ACTIVE'") suspend fun countActiveLecturesInSemester(semesterId:String):Int
    @Query("SELECT COUNT(*) FROM attendance_records ar JOIN lectures l ON l.id=ar.lectureId WHERE l.semesterId=:semesterId AND (ar.finalStatus='MANUAL_REVIEW' OR ar.approvalStatus='PENDING')") suspend fun countPendingReviewsInSemester(semesterId:String):Int
    @Query("SELECT * FROM teachers WHERE archivedAt IS NULL ORDER BY normalizedName") fun observeTeachers():Flow<List<TeacherEntity>>
    @Query("SELECT * FROM teachers ORDER BY id") suspend fun getTeachersOnce():List<TeacherEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertTeacher(v:TeacherEntity)
    @Update suspend fun updateTeacher(v:TeacherEntity)
    @Query("SELECT * FROM subjects WHERE archivedAt IS NULL ORDER BY name") fun observeSubjects():Flow<List<SubjectEntity>>
    @Query("SELECT * FROM subjects ORDER BY id") suspend fun getSubjectsOnce():List<SubjectEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertSubject(v:SubjectEntity)
    @Update suspend fun updateSubject(v:SubjectEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertTeacherSubject(v:TeacherSubjectEntity):Long
    @Query("DELETE FROM teacher_subjects WHERE subjectId=:subjectId") suspend fun deleteTeacherSubjectsForSubject(subjectId:String)
    @Query("SELECT COUNT(*) > 0 FROM teacher_subjects WHERE teacherId=:teacherId AND subjectId=:subjectId") suspend fun isTeacherLinkedToSubject(teacherId:String,subjectId:String):Boolean
    @Query("SELECT COUNT(*) FROM lectures WHERE teacherId=:teacherId") suspend fun countTeacherLectureHistory(teacherId:String):Int
    @Query("SELECT COUNT(*) FROM subjects WHERE teacherId=:teacherId AND archivedAt IS NULL AND status!='ARCHIVED'") suspend fun countActiveSubjectsForTeacher(teacherId:String):Int
    @Query("SELECT COUNT(*) FROM timetables WHERE subjectId=:subjectId AND isActive=1") suspend fun countActiveTimetablesForSubject(subjectId:String):Int
    @Query("SELECT COUNT(*) FROM lectures WHERE subjectId=:subjectId") suspend fun countSubjectLectureHistory(subjectId:String):Int
    @Query("SELECT * FROM universities WHERE archivedAt IS NULL ORDER BY name") fun observeUniversities():Flow<List<UniversityEntity>>
    @Query("SELECT * FROM universities ORDER BY id") suspend fun getUniversitiesOnce():List<UniversityEntity>
    @Query("SELECT * FROM universities WHERE id=:id LIMIT 1") suspend fun getUniversityById(id:String):UniversityEntity?
    @Query("SELECT * FROM faculties WHERE archivedAt IS NULL ORDER BY name") fun observeFaculties():Flow<List<FacultyEntity>>
    @Query("SELECT * FROM faculties ORDER BY id") suspend fun getFacultiesOnce():List<FacultyEntity>
    @Query("SELECT * FROM faculties WHERE id=:id LIMIT 1") suspend fun getFacultyById(id:String):FacultyEntity?
    @Query("SELECT * FROM departments WHERE archivedAt IS NULL ORDER BY name") fun observeDepartments():Flow<List<DepartmentEntity>>
    @Query("SELECT * FROM departments ORDER BY id") suspend fun getDepartmentsOnce():List<DepartmentEntity>
    @Query("SELECT * FROM departments WHERE id=:id LIMIT 1") suspend fun getDepartmentById(id:String):DepartmentEntity?
    @Query("SELECT * FROM academic_years ORDER BY startDate DESC") fun observeAcademicYears():Flow<List<AcademicYearEntity>>
    @Query("SELECT * FROM academic_years ORDER BY id") suspend fun getAcademicYearsOnce():List<AcademicYearEntity>
    @Query("SELECT * FROM academic_years WHERE id=:id LIMIT 1") suspend fun getAcademicYearById(id:String):AcademicYearEntity?
    @Query("SELECT * FROM levels WHERE archivedAt IS NULL ORDER BY orderIndex") fun observeLevels():Flow<List<LevelEntity>>
    @Query("SELECT * FROM levels ORDER BY id") suspend fun getLevelsOnce():List<LevelEntity>
    @Query("SELECT * FROM levels WHERE id=:id LIMIT 1") suspend fun getLevelById(id:String):LevelEntity?
    @Query("SELECT * FROM batches WHERE archivedAt IS NULL ORDER BY name") fun observeBatches():Flow<List<BatchEntity>>
    @Query("SELECT * FROM batches ORDER BY id") suspend fun getBatchesOnce():List<BatchEntity>
    @Query("SELECT * FROM batches WHERE id=:id LIMIT 1") suspend fun getBatchById(id:String):BatchEntity?
    @Query("SELECT * FROM sections WHERE archivedAt IS NULL ORDER BY name") fun observeSections():Flow<List<SectionEntity>>
    @Query("SELECT * FROM sections ORDER BY id") suspend fun getSectionsOnce():List<SectionEntity>
    @Query("SELECT * FROM sections WHERE id=:id LIMIT 1") suspend fun getSectionById(id:String):SectionEntity?
    @Query("SELECT * FROM groups WHERE archivedAt IS NULL ORDER BY name") fun observeGroups():Flow<List<GroupEntity>>
    @Query("SELECT * FROM groups ORDER BY id") suspend fun getGroupsOnce():List<GroupEntity>
    @Query("SELECT * FROM levels WHERE id=:id AND archivedAt IS NULL LIMIT 1") suspend fun getActiveLevelById(id:String):LevelEntity?
    @Query("SELECT * FROM batches WHERE id=:id AND archivedAt IS NULL LIMIT 1") suspend fun getActiveBatchById(id:String):BatchEntity?
    @Query("SELECT * FROM sections WHERE id=:id AND archivedAt IS NULL LIMIT 1") suspend fun getActiveSectionById(id:String):SectionEntity?
    @Query("SELECT * FROM groups WHERE id=:id AND archivedAt IS NULL LIMIT 1") suspend fun getActiveGroupById(id:String):GroupEntity?
    @Query("SELECT * FROM groups WHERE id=:id LIMIT 1") suspend fun getGroupById(id:String):GroupEntity?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertUniversity(v:UniversityEntity)
    @Update suspend fun updateUniversity(v:UniversityEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertFaculty(v:FacultyEntity)
    @Update suspend fun updateFaculty(v:FacultyEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertDepartment(v:DepartmentEntity)
    @Update suspend fun updateDepartment(v:DepartmentEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertAcademicYear(v:AcademicYearEntity)
    @Update suspend fun updateAcademicYear(v:AcademicYearEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertLevel(v:LevelEntity)
    @Update suspend fun updateLevel(v:LevelEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertBatch(v:BatchEntity)
    @Update suspend fun updateBatch(v:BatchEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertSection(v:SectionEntity)
    @Update suspend fun updateSection(v:SectionEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertGroup(v:GroupEntity)
    @Update suspend fun updateGroup(v:GroupEntity)
    @Update suspend fun updateLecture(v:LectureEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertSession(v:AttendanceSessionEntity)
    @Update suspend fun updateSession(v:AttendanceSessionEntity)

    @Query("SELECT * FROM attendance_records WHERE lectureId=:lectureId ORDER BY studentId") fun observeLectureRecords(lectureId:String): Flow<List<AttendanceRecordEntity>>
    @Query("""SELECT ar.* FROM attendance_records ar JOIN students st ON st.id=ar.studentId WHERE ar.studentId=:studentId AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND ((us.scopeType='STUDENT' AND us.scopeId=st.id) OR (us.scopeType='GROUP' AND us.scopeId=st.groupId) OR (us.scopeType='SECTION' AND us.scopeId=st.sectionId) OR (us.scopeType='BATCH' AND us.scopeId=st.batchId) OR (us.scopeType='LEVEL' AND us.scopeId=st.levelId)))
        OR EXISTS(SELECT 1 FROM user_scopes us JOIN subjects sub ON us.scopeType='TEACHER' AND us.scopeId=sub.teacherId WHERE us.userId=:userId AND us.active=1 AND sub.archivedAt IS NULL AND sub.groupId=st.groupId)
    ) ORDER BY ar.updatedAt DESC""") fun observeScopedStudentRecords(userId:String,studentId:String):Flow<List<AttendanceRecordEntity>>
    @Query("SELECT * FROM attendance_records WHERE lectureId=:lectureId") suspend fun getLectureRecords(lectureId:String): List<AttendanceRecordEntity>
    @Query("SELECT * FROM attendance_records WHERE id=:id LIMIT 1") suspend fun getAttendanceRecord(id:String):AttendanceRecordEntity?
    @Query("SELECT * FROM attendance_records ORDER BY id") suspend fun getAttendanceRecordsOnce():List<AttendanceRecordEntity>
    @Query("SELECT * FROM attendance_records WHERE lectureId=:lectureId AND studentId=:studentId LIMIT 1") suspend fun getAttendanceRecord(lectureId:String,studentId:String):AttendanceRecordEntity?
    @Query("SELECT * FROM attendance_records WHERE studentId=:studentId ORDER BY createdAt DESC") fun observeStudentRecords(studentId:String):Flow<List<AttendanceRecordEntity>>
    @Query("SELECT * FROM attendance_records WHERE studentId=:studentId ORDER BY createdAt DESC") suspend fun getStudentRecordsOnce(studentId:String):List<AttendanceRecordEntity>
    @Query("SELECT ar.* FROM attendance_records ar JOIN lectures l ON l.id=ar.lectureId WHERE ar.finalStatus=:status AND l.scheduledStart>=:start AND l.scheduledStart<:end ORDER BY l.scheduledStart DESC") suspend fun getRecordsByStatusRange(status:FinalAttendanceStatus,start:Long,end:Long):List<AttendanceRecordEntity>
    @Query("SELECT ar.* FROM attendance_records ar JOIN lectures l ON l.id=ar.lectureId WHERE ar.finalStatus='MANUAL_REVIEW' AND l.scheduledStart>=:start AND l.scheduledStart<:end ORDER BY l.scheduledStart DESC") suspend fun getNeedsReviewRange(start:Long,end:Long):List<AttendanceRecordEntity>
    @Query("SELECT ar.* FROM attendance_records ar INNER JOIN lectures l ON l.id=ar.lectureId WHERE (:subjectId IS NULL OR l.subjectId=:subjectId) AND l.scheduledStart>=:periodStart AND l.scheduledStart<:periodEnd") suspend fun getReportRecords(subjectId:String?,periodStart:Long,periodEnd:Long):List<AttendanceRecordEntity>
    @Query("""SELECT ar.id AS recordId, ar.lectureId AS lectureId, ar.studentId AS studentId, st.fullName AS studentName, st.universityNumber AS universityNumber,
        ar.finalStatus AS finalStatus, ar.attendancePercentage AS attendancePercentage, ar.verifiedPresenceSeconds AS verifiedPresenceSeconds, ar.firstSeenAt AS firstSeenAt, ar.lastSeenAt AS lastSeenAt
        FROM attendance_records ar
        JOIN lectures l ON l.id=ar.lectureId
        JOIN students st ON st.id=ar.studentId
        JOIN teacher_subjects ts ON ts.subjectId=l.subjectId AND ts.teacherId=:teacherId
        WHERE l.teacherId=:teacherId AND (:subjectId IS NULL OR l.subjectId=:subjectId)
        AND l.scheduledStart>=:periodStart AND l.scheduledStart<:periodEnd
        AND l.status IN ('COMPLETED','FROZEN') AND ar.approvalStatus IN ('APPROVED','FROZEN')
        ORDER BY l.scheduledStart,st.normalizedName""") suspend fun getOfficialTeacherReportRows(teacherId:String,subjectId:String?,periodStart:Long,periodEnd:Long):List<ReportAttendanceRow>
    @Query("""SELECT COUNT(*) FROM lectures l WHERE l.teacherId=:teacherId AND (:subjectId IS NULL OR l.subjectId=:subjectId)
        AND l.scheduledStart>=:periodStart AND l.scheduledStart<:periodEnd AND l.status IN ('COMPLETED','FROZEN')
        AND EXISTS(SELECT 1 FROM teacher_subjects ts WHERE ts.teacherId=:teacherId AND ts.subjectId=l.subjectId)""") suspend fun countLecturesForReport(teacherId:String,subjectId:String?,periodStart:Long,periodEnd:Long):Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertRecord(v:AttendanceRecordEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertPresenceEvent(v:PresenceEventEntity):Long
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertPresenceInterval(v:PresenceIntervalEntity)
    @Query("SELECT * FROM presence_intervals WHERE attendanceRecordId=:recordId AND endAt IS NULL ORDER BY startAt DESC LIMIT 1") suspend fun getOpenInterval(recordId:String):PresenceIntervalEntity?
    @Query("SELECT * FROM presence_intervals WHERE attendanceRecordId=:recordId AND endAt IS NULL ORDER BY startAt") suspend fun getOpenIntervals(recordId:String):List<PresenceIntervalEntity>
    @Query("SELECT * FROM presence_intervals WHERE attendanceRecordId=:recordId ORDER BY startAt") suspend fun getIntervals(recordId:String):List<PresenceIntervalEntity>
    @Query("SELECT * FROM presence_events WHERE attendanceSessionId=:sessionId AND studentId=:studentId ORDER BY timestamp") suspend fun getPresenceEvents(sessionId:String,studentId:String):List<PresenceEventEntity>
    @Query("SELECT * FROM presence_events WHERE attendanceSessionId=:sessionId AND studentId=:studentId ORDER BY timestamp DESC LIMIT 1") suspend fun getLastPresenceEvent(sessionId:String,studentId:String):PresenceEventEntity?
    @Query("SELECT COUNT(*) FROM attendance_records ar JOIN lectures l ON l.id=ar.lectureId WHERE ar.studentId=:studentId AND ar.lectureId!=:excludeLectureId AND l.status!='CANCELLED' AND l.scheduledStart<:endAt AND l.scheduledEnd>:startAt AND ar.finalStatus!='ABSENT'") suspend fun countOverlappingAttendance(studentId:String,excludeLectureId:String,startAt:Long,endAt:Long):Int
    @Query("SELECT COUNT(*) FROM student_devices WHERE studentId=:studentId AND registeredAt>=:since") suspend fun countRecentDeviceRegistrations(studentId:String,since:Long):Int

    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertAudit(v:AuditLogEntity)
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit") fun observeAudit(limit:Int=200):Flow<List<AuditLogEntity>>
    @Query("SELECT * FROM audit_logs WHERE entityType=:entityType AND entityId=:entityId ORDER BY timestamp DESC LIMIT :limit") fun observeEntityAudit(entityType:String,entityId:String,limit:Int=100):Flow<List<AuditLogEntity>>
    @Query("SELECT * FROM audit_logs WHERE (:start IS NULL OR timestamp>=:start) AND (:end IS NULL OR timestamp<=:end) AND (:actorId IS NULL OR actorId=:actorId) AND (:actorRole IS NULL OR actorRole=:actorRole) AND (:entityType IS NULL OR entityType=:entityType) AND (:action IS NULL OR action=:action) ORDER BY timestamp DESC LIMIT :limit") suspend fun queryAudit(start:Long?,end:Long?,actorId:String?,actorRole:String?,entityType:String?,action:String?,limit:Int=5000):List<AuditLogEntity>

    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertAppeal(v:AttendanceAppealEntity)
    @Update suspend fun updateAppeal(v:AttendanceAppealEntity)
    @Query("SELECT * FROM attendance_appeals WHERE id=:id LIMIT 1") suspend fun getAppeal(id:String):AttendanceAppealEntity?
    @Query("SELECT COUNT(*) FROM attendance_appeals WHERE attendanceRecordId=:recordId AND status='PENDING'") suspend fun countPendingAppealsForRecord(recordId:String):Int
    @Query("UPDATE attendance_appeals SET syncStatus=:syncStatus, updatedAt=:updatedAt WHERE id=:id") suspend fun updateAppealSyncStatus(id:String,syncStatus:AppealSyncStatus,updatedAt:Long)
    @Query("UPDATE attendance_appeals SET syncStatus=:syncStatus WHERE id=:id") suspend fun updateAppealSyncStatusOnly(id:String,syncStatus:AppealSyncStatus)
    @Query("SELECT * FROM attendance_appeals WHERE attendanceRecordId=:recordId ORDER BY submittedAt DESC") fun observeAppealsForRecord(recordId:String):Flow<List<AttendanceAppealEntity>>
    @Query("SELECT * FROM attendance_appeals WHERE studentId=:studentId ORDER BY submittedAt DESC") fun observeStudentAppeals(studentId:String):Flow<List<AttendanceAppealEntity>>
    @Query("SELECT * FROM attendance_appeals WHERE (:status IS NULL OR status=:status) ORDER BY submittedAt DESC") fun observeAppeals(status:AppealStatus?):Flow<List<AttendanceAppealEntity>>
    @Query("""SELECT aa.id AS appealId, aa.studentId AS studentId, st.fullName AS studentName,
        st.universityNumber AS universityNumber, s.name AS subjectName, t.fullName AS teacherName,
        aa.lectureId AS lectureId, l.scheduledStart AS lectureDate, ar.finalStatus AS currentAttendanceStatus,
        ar.attendancePercentage AS currentAttendancePercentage, aa.reasonType AS reasonType,
        aa.description AS description, aa.attachmentLocalUri AS attachmentLocalUri, aa.status AS status,
        aa.submittedAt AS submittedAt, aa.decisionNote AS decisionNote
        FROM attendance_appeals aa
        JOIN students st ON st.id=aa.studentId
        JOIN attendance_records ar ON ar.id=aa.attendanceRecordId
        JOIN lectures l ON l.id=aa.lectureId
        JOIN subjects s ON s.id=aa.subjectId
        LEFT JOIN teachers t ON t.id=l.teacherId
        WHERE (:status IS NULL OR aa.status=:status)
        ORDER BY aa.submittedAt DESC""") fun observeAppealReviewRows(status:AppealStatus?):Flow<List<AppealReviewRow>>
    @Query("""SELECT aa.id AS appealId, st.id AS studentId, st.fullName AS studentName, st.universityNumber AS universityNumber,
        s.name AS subjectName, t.fullName AS teacherName, l.id AS lectureId, l.scheduledStart AS lectureDate,
        ar.finalStatus AS currentAttendanceStatus, ar.attendancePercentage AS currentAttendancePercentage, aa.reasonType AS reasonType,
        aa.description AS description, aa.attachmentLocalUri AS attachmentLocalUri, aa.status AS status,
        aa.submittedAt AS submittedAt, aa.decisionNote AS decisionNote
        FROM attendance_appeals aa JOIN students st ON st.id=aa.studentId JOIN attendance_records ar ON ar.id=aa.attendanceRecordId
        JOIN lectures l ON l.id=aa.lectureId JOIN subjects s ON s.id=aa.subjectId LEFT JOIN teachers t ON t.id=l.teacherId
        WHERE (:status IS NULL OR aa.status=:status) AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
            EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
            OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND ((us.scopeType='STUDENT' AND us.scopeId=st.id) OR (us.scopeType='GROUP' AND us.scopeId=st.groupId) OR (us.scopeType='SECTION' AND us.scopeId=st.sectionId) OR (us.scopeType='BATCH' AND us.scopeId=st.batchId) OR (us.scopeType='LEVEL' AND us.scopeId=st.levelId) OR (us.scopeType='TEACHER' AND us.scopeId=l.teacherId)))
        ) ORDER BY aa.submittedAt DESC""") fun observeScopedAppealReviewRows(userId:String,status:AppealStatus?):Flow<List<AppealReviewRow>>

    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueueNotification(v:NotificationEntity):Long
    @Query("SELECT * FROM notifications WHERE ((status='PENDING' OR (status='FAILED' AND retryCount<5)) AND scheduledAt<=:now) OR (status='PROCESSING' AND scheduledAt<=:staleBefore) ORDER BY scheduledAt LIMIT :limit") suspend fun pendingNotifications(now:Long,staleBefore:Long=now-900000L,limit:Int=25):List<NotificationEntity>
    @Query("UPDATE notifications SET status='PROCESSING', scheduledAt=:now, version=version+1 WHERE id=:id AND (status='PENDING' OR (status='FAILED' AND retryCount<5) OR (status='PROCESSING' AND scheduledAt<=:staleBefore))") suspend fun claimNotification(id:String,now:Long,staleBefore:Long=now-900000L):Int
    @Update suspend fun updateNotification(v:NotificationEntity)

    @Query("SELECT * FROM teacher_report_settings WHERE enabled=1 ORDER BY teacherId, subjectId") fun observeReportSettings():Flow<List<TeacherReportSettingEntity>>
    @Query("SELECT * FROM teacher_report_settings ORDER BY teacherId,subjectId") fun observeAllReportSettings():Flow<List<TeacherReportSettingEntity>>
    @Query("""SELECT DISTINCT t.* FROM teachers t WHERE t.archivedAt IS NULL AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=t.id)
        OR EXISTS(SELECT 1 FROM subjects s JOIN groups g ON g.id=s.groupId JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId
            JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE s.teacherId=t.id AND s.archivedAt IS NULL AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY t.fullName""") fun observeReportTeachersForUser(userId:String):Flow<List<TeacherEntity>>
    @Query("""SELECT DISTINCT s.* FROM subjects s JOIN groups g ON g.id=s.groupId JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId WHERE s.archivedAt IS NULL AND EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=s.teacherId)
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY s.name""") fun observeReportSubjectsForUser(userId:String):Flow<List<SubjectEntity>>
    @Query("""SELECT DISTINCT trs.* FROM teacher_report_settings trs LEFT JOIN subjects s ON s.id=trs.subjectId LEFT JOIN groups g ON g.id=s.groupId LEFT JOIN sections sec ON sec.id=g.sectionId LEFT JOIN batches b ON b.id=sec.batchId LEFT JOIN levels lv ON lv.id=b.levelId WHERE EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=trs.teacherId)
        OR (trs.subjectId IS NOT NULL AND EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id))))
    ) ORDER BY trs.teacherId,trs.subjectId""") fun observeReportSettingsForUser(userId:String):Flow<List<TeacherReportSettingEntity>>
    @Query("SELECT * FROM teacher_report_settings ORDER BY teacherId,subjectId") suspend fun getAllReportSettings():List<TeacherReportSettingEntity>
    @Query("SELECT * FROM teacher_report_settings WHERE enabled=1 ORDER BY teacherId, subjectId") suspend fun getEnabledReportSettings():List<TeacherReportSettingEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertTeacherReportSetting(v:TeacherReportSettingEntity)
    @Query("SELECT * FROM teacher_report_settings WHERE teacherId=:teacherId AND ((subjectId IS NULL AND :subjectId IS NULL) OR subjectId=:subjectId) AND enabled=1 ORDER BY updatedAt DESC LIMIT 1") suspend fun getReportSetting(teacherId:String,subjectId:String?):TeacherReportSettingEntity?
    @Query("SELECT * FROM report_jobs ORDER BY scheduledAt DESC LIMIT 100") fun observeReportHistory():Flow<List<ReportJobEntity>>
    @Query("""SELECT DISTINCT rj.* FROM report_jobs rj LEFT JOIN subjects sub ON sub.id=rj.subjectId WHERE EXISTS(SELECT 1 FROM users u WHERE u.id=:userId AND u.isActive=1) AND (
        EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.roleId WHERE ur.userId=:userId AND r.name IN ('SYSTEM_OWNER','Administrator'))
        OR EXISTS(SELECT 1 FROM user_scopes us WHERE us.userId=:userId AND us.active=1 AND us.scopeType='TEACHER' AND us.scopeId=rj.teacherId)
        OR EXISTS(SELECT 1 FROM groups g JOIN sections sec ON sec.id=g.sectionId JOIN batches b ON b.id=sec.batchId JOIN levels lv ON lv.id=b.levelId
            JOIN user_scopes us ON us.userId=:userId AND us.active=1 WHERE g.id=sub.groupId AND ((us.scopeType='GROUP' AND us.scopeId=g.id) OR (us.scopeType='SECTION' AND us.scopeId=sec.id) OR (us.scopeType='BATCH' AND us.scopeId=b.id) OR (us.scopeType='LEVEL' AND us.scopeId=lv.id)))
    ) ORDER BY rj.scheduledAt DESC LIMIT 100""") fun observeScopedReportHistory(userId:String):Flow<List<ReportJobEntity>>
    @Query("SELECT * FROM report_jobs WHERE id=:id LIMIT 1") suspend fun getReportJob(id:String):ReportJobEntity?
    @Query("SELECT * FROM generated_reports WHERE reportJobId=:jobId ORDER BY generatedAt DESC LIMIT 1") suspend fun getGeneratedReport(jobId:String):GeneratedReportEntity?
    @Query("SELECT * FROM generated_reports WHERE hash=:hash LIMIT 1") suspend fun getGeneratedReportByHash(hash:String):GeneratedReportEntity?
    @Query("SELECT * FROM generated_reports WHERE filePath=:filePath LIMIT 1") suspend fun getGeneratedReportByFilePath(filePath:String):GeneratedReportEntity?
    @Query("DELETE FROM generated_reports WHERE reportJobId=:jobId") suspend fun deleteGeneratedReports(jobId:String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueueReport(v:ReportJobEntity):Long
    @Query("SELECT * FROM report_jobs WHERE (status IN ('SCHEDULED','PENDING_SEND') OR (status='FAILED' AND generatedAt IS NULL AND retryCount<5)) AND scheduledAt<=:now ORDER BY scheduledAt LIMIT :limit") suspend fun pendingReports(now:Long,limit:Int=25):List<ReportJobEntity>
    @Query("UPDATE report_jobs SET status='GENERATING', errorMessage=:claimMarker, version=version+1 WHERE id=:id AND status=:expectedStatus AND version=:expectedVersion") suspend fun claimReportJob(id:String,expectedStatus:ReportJobStatus,expectedVersion:Long,claimMarker:String):Int
    @Query("SELECT * FROM report_jobs WHERE status='GENERATING'") suspend fun generatingReports():List<ReportJobEntity>
    @Query("UPDATE report_jobs SET status=:restoreStatus, errorMessage=NULL, version=version+1 WHERE id=:id AND status='GENERATING' AND version=:expectedVersion AND errorMessage=:claimMarker") suspend fun recoverReportClaim(id:String,expectedVersion:Long,claimMarker:String,restoreStatus:ReportJobStatus):Int
    @Update suspend fun updateReport(v:ReportJobEntity)
    @Query("""UPDATE report_jobs SET status=CASE WHEN status IN ('SENT','CANCELLED') THEN status ELSE 'FAILED' END, errorMessage=:error, version=version+1 WHERE teacherId=:teacherId AND (:subjectId IS NULL OR subjectId=:subjectId) AND periodStart<=:lectureAt AND periodEnd>:lectureAt AND status!='CANCELLED'""") suspend fun markReportJobsStaleForLecture(teacherId:String,subjectId:String?,lectureAt:Long,error:String):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertGeneratedReport(v:GeneratedReportEntity)

    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueueSync(v:SyncQueueEntity):Long
    @Query("""SELECT * FROM sync_queue WHERE status='PENDING' OR (status='FAILED' AND retryCount<5) ORDER BY CASE entityType WHEN 'Teacher' THEN 5 WHEN 'AttendancePolicy' THEN 5 WHEN 'University' THEN 10 WHEN 'AcademicYear' THEN 10 WHEN 'Faculty' THEN 20 WHEN 'Semester' THEN 20 WHEN 'Department' THEN 30 WHEN 'Level' THEN 40 WHEN 'Batch' THEN 50 WHEN 'Section' THEN 60 WHEN 'Group' THEN 70 WHEN 'Student' THEN 80 WHEN 'Subject' THEN 82 WHEN 'Lecture' THEN 90 WHEN 'AttendanceRecord' THEN 95 WHEN 'AttendanceAppeal' THEN 100 ELSE 98 END, createdAt, id LIMIT :limit""") suspend fun pendingSync(limit:Int=50):List<SyncQueueEntity>
    @Query("UPDATE sync_queue SET status='PROCESSING', lastAttempt=:claimAt, version=version+1 WHERE id=:id AND status=:expectedStatus AND version=:expectedVersion") suspend fun claimSync(id:String,expectedStatus:QueueStatus,expectedVersion:Long,claimAt:Long):Int
    @Query("SELECT * FROM sync_queue WHERE status='PROCESSING' AND lastAttempt IS NOT NULL AND lastAttempt<=:staleBefore") suspend fun staleProcessingSync(staleBefore:Long):List<SyncQueueEntity>
    @Query("SELECT COUNT(*) FROM sync_queue WHERE entityType=:entityType AND entityId=:entityId AND status IN ('PENDING','FAILED','PROCESSING','NEEDS_MANUAL_REVIEW')") suspend fun countUnresolvedSyncForEntity(entityType:String,entityId:String):Int
    @Query("SELECT COUNT(*) FROM sync_queue WHERE entityType=:entityType AND entityId=:entityId AND status='SENT'") suspend fun countSentSyncForEntity(entityType:String,entityId:String):Int
    @Query("UPDATE sync_queue SET status='NEEDS_MANUAL_REVIEW', error=:error, version=version+1 WHERE entityType=:entityType AND entityId=:entityId AND status IN ('PENDING','FAILED','PROCESSING')") suspend fun markEntitySyncConflict(entityType:String,entityId:String,error:String):Int
    @Query("UPDATE sync_queue SET status=:restoreStatus, error='SYNC_CLAIM_RECOVERED', version=version+1 WHERE id=:id AND status='PROCESSING' AND version=:expectedVersion AND lastAttempt=:claimAt") suspend fun recoverSyncClaim(id:String,expectedVersion:Long,claimAt:Long,restoreStatus:QueueStatus):Int
    @Update suspend fun updateSync(v:SyncQueueEntity)
    @Query("SELECT * FROM sync_entity_metadata WHERE entityType=:entityType AND entityId=:entityId LIMIT 1") suspend fun getSyncEntityMetadata(entityType:String,entityId:String):SyncEntityMetadataEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertSyncEntityMetadata(v:SyncEntityMetadataEntity):Long
    @Update suspend fun updateSyncEntityMetadata(v:SyncEntityMetadataEntity)
    @Query("UPDATE sync_entity_metadata SET lastSyncedServerVersion=:serverVersion WHERE entityType=:entityType AND entityId=:entityId") suspend fun markSyncEntityServerVersion(entityType:String,entityId:String,serverVersion:Long):Int
    @Query("SELECT * FROM sync_entity_metadata ORDER BY entityType,entityId") suspend fun getAllSyncEntityMetadata():List<SyncEntityMetadataEntity>

    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertSetting(v:AppSettingEntity)
    @Query("SELECT * FROM app_settings WHERE key=:key LIMIT 1") suspend fun getSetting(key:String):AppSettingEntity?
    @Query("SELECT * FROM app_settings WHERE key LIKE :prefix || '%' ORDER BY key") fun observeSettingsByPrefix(prefix:String):Flow<List<AppSettingEntity>>
    @Query("SELECT * FROM feature_flags ORDER BY code") fun observeFeatureFlags():Flow<List<FeatureFlagEntity>>
    @Query("SELECT * FROM feature_flags ORDER BY code") suspend fun getFeatureFlags():List<FeatureFlagEntity>
    @Query("SELECT enabled FROM feature_flags WHERE code=:code LIMIT 1") suspend fun isFeatureEnabled(code:String):Boolean?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertFeatureFlag(v:FeatureFlagEntity)

    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertBackupHistory(v:BackupHistoryEntity)
    @Query("SELECT * FROM backup_history ORDER BY createdAt DESC LIMIT 1") fun observeLastBackup():Flow<BackupHistoryEntity?>

    @Query("SELECT COUNT(*) FROM students WHERE archivedAt IS NULL") fun observeStudentCount():Flow<Int>
    @Query("SELECT COUNT(*) FROM notifications WHERE status IN ('PENDING','FAILED')") fun observePendingNotificationCount():Flow<Int>
    @Query("SELECT COUNT(*) FROM report_jobs WHERE status IN ('SCHEDULED','PENDING_APPROVAL','PENDING_SEND','FAILED')") fun observePendingReportCount():Flow<Int>
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING','FAILED')") fun observePendingSyncCount():Flow<Int>
    @Query("SELECT MAX(lastAttempt) FROM sync_queue WHERE status='SENT'") suspend fun getLastSuccessfulSync():Long?
    @Query("SELECT * FROM sync_queue ORDER BY COALESCE(lastAttempt,createdAt) DESC LIMIT 1") suspend fun getLatestSyncQueueItem():SyncQueueEntity?
    @Query("SELECT * FROM notifications WHERE channel=:channel ORDER BY COALESCE(sentAt,scheduledAt) DESC LIMIT 1") suspend fun getLatestNotificationForChannel(channel:String):NotificationEntity?
    @Query("SELECT error FROM sync_queue WHERE error IS NOT NULL ORDER BY COALESCE(lastAttempt,createdAt) DESC LIMIT 5") suspend fun getRecentSyncErrors():List<String>
    @Query("SELECT error FROM notifications WHERE error IS NOT NULL ORDER BY COALESCE(sentAt,scheduledAt) DESC LIMIT 5") suspend fun getRecentNotificationErrors():List<String>
    @Query("SELECT errorMessage FROM report_jobs WHERE errorMessage IS NOT NULL ORDER BY scheduledAt DESC LIMIT 5") suspend fun getRecentReportErrors():List<String>
    @Query("SELECT COUNT(*) FROM students WHERE archivedAt IS NULL AND (levelId IS NULL OR batchId IS NULL OR sectionId IS NULL OR groupId IS NULL)") suspend fun countStudentsWithoutScope():Int
    @Query("SELECT COUNT(*) FROM students st LEFT JOIN levels l ON l.id=st.levelId LEFT JOIN batches b ON b.id=st.batchId LEFT JOIN sections se ON se.id=st.sectionId LEFT JOIN groups g ON g.id=st.groupId WHERE st.archivedAt IS NULL AND (st.levelId IS NULL OR st.batchId IS NULL OR st.sectionId IS NULL OR st.groupId IS NULL OR l.id IS NULL OR b.id IS NULL OR se.id IS NULL OR g.id IS NULL OR l.archivedAt IS NOT NULL OR b.archivedAt IS NOT NULL OR se.archivedAt IS NOT NULL OR g.archivedAt IS NOT NULL OR b.levelId!=st.levelId OR se.batchId!=st.batchId OR g.sectionId!=st.sectionId)") suspend fun countStudentsWithInvalidScope():Int
    @Query("SELECT COUNT(*) FROM teacher_subjects ts LEFT JOIN teachers t ON t.id=ts.teacherId LEFT JOIN subjects s ON s.id=ts.subjectId WHERE t.id IS NULL OR s.id IS NULL") suspend fun countBrokenTeacherSubjectRelations():Int
    @Query("SELECT COUNT(*) FROM subjects s LEFT JOIN teachers t ON t.id=s.teacherId WHERE s.archivedAt IS NULL AND s.teacherId IS NOT NULL AND (t.id IS NULL OR t.archivedAt IS NOT NULL)") suspend fun countSubjectsWithInvalidTeacher():Int
    @Query("SELECT COUNT(*) FROM timetables tt JOIN subjects s ON s.id=tt.subjectId WHERE tt.isActive=1 AND s.archivedAt IS NOT NULL") suspend fun countTimetablesUsingArchivedSubject():Int
    @Query("SELECT COUNT(*) FROM report_jobs r WHERE (r.status='SENT' AND r.sentAt IS NULL) OR (r.status='PENDING_APPROVAL' AND r.generatedAt IS NULL) OR r.periodStart>r.periodEnd") suspend fun countInconsistentReportJobs():Int
    @Query("SELECT COUNT(*) FROM students WHERE universityNumber IS NOT NULL GROUP BY universityNumber HAVING COUNT(*)>1") suspend fun duplicateUniversityNumberGroups():List<Int>
    @Query("SELECT COUNT(*) FROM subjects WHERE archivedAt IS NULL AND teacherId IS NULL") suspend fun countSubjectsWithoutTeacher():Int
    @Query("SELECT COUNT(*) FROM subjects s LEFT JOIN attendance_policies p ON p.id=s.attendancePolicyId WHERE s.archivedAt IS NULL AND (s.attendancePolicyId IS NULL OR p.id IS NULL)") suspend fun countSubjectsWithInvalidAttendancePolicy():Int
    @Query("SELECT COUNT(*) FROM attendance_records ar LEFT JOIN lectures l ON l.id=ar.lectureId LEFT JOIN students s ON s.id=ar.studentId WHERE l.id IS NULL OR s.id IS NULL") suspend fun countOrphanAttendance():Int
    @Query("SELECT COUNT(*) FROM attendance_appeals aa LEFT JOIN attendance_records ar ON ar.id=aa.attendanceRecordId WHERE ar.id IS NULL") suspend fun countOrphanAppeals():Int
    @Query("SELECT COUNT(*) FROM student_devices WHERE status='ACTIVE' GROUP BY studentId HAVING COUNT(*)>1") suspend fun multipleActiveDeviceGroups():List<Int>
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status='FAILED' AND retryCount>=5") suspend fun countRepeatedlyFailedSync():Int
    @Query("SELECT COUNT(*) FROM lectures WHERE status='ACTIVE' AND actualStart IS NOT NULL AND actualStart<:cutoff") suspend fun countStuckActiveLectures(cutoff:Long):Int
    @Query("SELECT COUNT(*) FROM semesters WHERE startDate>=endDate") suspend fun countInvalidSemesterDates():Int
}
