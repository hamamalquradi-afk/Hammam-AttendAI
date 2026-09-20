package com.hammam.attendai.backup

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.settings.AppPreferences
import com.hammam.attendai.domain.model.SemesterStatus
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.AcademicHierarchySyncOutbox
import com.hammam.attendai.sync.AcademicReferenceSyncOutbox
import kotlinx.coroutines.flow.first
import java.io.File
import android.util.Base64

/** Non-secret configuration bundle. It deliberately excludes users, students, attendance history and provider secrets. */
class ConfigurationBackupManager(private val db:HammamDatabase,private val preferences:AppPreferences,cipher:KeystoreCipher?=null){
    private val hierarchySyncOutbox=cipher?.let{AcademicHierarchySyncOutbox(db,it)}
    private val referenceSyncOutbox=cipher?.let{AcademicReferenceSyncOutbox(db,it)}
    data class Preview(val valid:Boolean,val policies:Int,val reportSettings:Int,val featureFlags:Int,val rolePermissions:Int,val academicYears:Int,val semesters:Int,val issues:List<String>)
    private val dao=db.coreDao()
    private fun e(v:String?)=Base64.encodeToString((v?:"").toByteArray(Charsets.UTF_8),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun d(v:String)=String(Base64.decode(v,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8)

    suspend fun export(target:File){
        target.parentFile?.mkdirs();val lines=mutableListOf("HAMMAM_CONFIG_V1")
        dao.getAttendancePolicies().forEach{p->lines+="POLICY|${listOf(p.id,p.name,p.scopeType,p.scopeId,p.fullAttendanceThreshold,p.partialAttendanceThreshold,p.lateAfterMinutes,p.earlyLeaveThresholdMinutes,p.absenceThreshold,p.temporaryMissingGraceSeconds,p.minimumPresenceVerificationSeconds,p.confidenceThreshold,p.createdAt,p.updatedAt,p.version).joinToString("|"){e(it.toString())}}"}
        dao.getAllReportSettings().forEach{r->lines+="REPORT|${listOf(r.id,r.teacherId,r.subjectId,r.enabled,r.frequency,r.sendTime,r.weeklyDay,r.monthlyDay,r.semesterReportEnabled,r.customRule,r.timezone,r.channel,r.reportFormat,r.includeStudentDetails,r.requireApproval,r.aiSummaryEnabled,r.sendIfNoLecture,r.updatedAt,r.version).joinToString("|"){e(it?.toString())}}"}
        dao.getFeatureFlags().forEach{f->lines+="FLAG|${e(f.id)}|${e(f.code)}|${e(f.enabled.toString())}|${e(f.updatedAt.toString())}"}
        dao.getRolePermissionExport().forEach{rp->lines+="ROLEPERM|${e(rp.roleName)}|${e(rp.permissionCode)}"}
        dao.getAcademicYearsOnce().forEach{a->lines+="YEAR|${listOf(a.id,a.name,a.startDate,a.endDate,a.isActive).joinToString("|"){e(it.toString())}}"}
        dao.getSemestersOnce().forEach{s->lines+="SEMESTER|${listOf(s.id,s.name,s.academicYearId,s.startDate,s.endDate,s.status.name,s.createdAt,s.updatedAt).joinToString("|"){e(it.toString())}}"}
        lines+="UI|${e(preferences.language.first())}|${e(preferences.theme.first())}"
        target.writeText(lines.joinToString("\n"),Charsets.UTF_8)
    }

    suspend fun preview(source:File):Preview{
        val lines=runCatching{source.readLines(Charsets.UTF_8)}.getOrElse{return Preview(false,0,0,0,0,0,0,listOf("READ_FAILED"))};if(lines.firstOrNull()!="HAMMAM_CONFIG_V1")return Preview(false,0,0,0,0,0,0,listOf("INVALID_CONFIG_FORMAT"))
        var p=0;var r=0;var f=0;var rp=0;var y=0;var sm=0;val issues=mutableListOf<String>()
        lines.drop(1).forEachIndexed{i,line->val parts=line.split('|');runCatching{when(parts.firstOrNull()){ "POLICY"->{require(parts.size==16);p++};"REPORT"->{require(parts.size==20);val teacher=d(parts[2]);val subject=d(parts[3]).ifBlank{null};if(dao.getTeacherById(teacher)==null)issues+="LINE_${i+2}:REPORT_TEACHER_MISSING";if(subject!=null&&dao.getSubjectById(subject)==null)issues+="LINE_${i+2}:REPORT_SUBJECT_MISSING";r++};"FLAG"->{require(parts.size==5);f++};"ROLEPERM"->{require(parts.size==3);if(dao.getRoleIdByName(d(parts[1]))==null)issues+="LINE_${i+2}:ROLE_MISSING";if(dao.getPermissionIdByCode(d(parts[2]))==null)issues+="LINE_${i+2}:PERMISSION_MISSING";rp++};"YEAR"->{require(parts.size==6);y++};"SEMESTER"->{require(parts.size==9);sm++};"UI"->{require(parts.size==3)};""->Unit;else->issues+="LINE_${i+2}:UNKNOWN_SECTION"}}.onFailure{issues+="LINE_${i+2}:INVALID_ROW"}}
        return Preview(issues.isEmpty(),p,r,f,rp,y,sm,issues)
    }

    suspend fun importConfirmed(source:File){
        val check=preview(source);require(check.valid){check.issues.joinToString(",")};val lines=source.readLines(Charsets.UTF_8);var ui:Pair<String,String>?=null
        db.withTransaction{lines.drop(1).forEach{line->val p=line.split('|');when(p.firstOrNull()){
            "POLICY"->{val v=p.drop(1).map(::d);val row=AttendancePolicyEntity(v[0],v[1],v[2],v[3].ifBlank{null},v[4].toDouble(),v[5].toDouble(),v[6].toInt(),v[7].toInt(),v[8].toDouble(),v[9].toLong(),v[10].toLong(),v[11].toDouble(),v[12].toLong(),v[13].toLong(),v[14].toLong());dao.upsertAttendancePolicy(row);referenceSyncOutbox?.enqueueAttendancePolicyIfEnabled(row)}
            "REPORT"->{val v=p.drop(1).map(::d);dao.upsertTeacherReportSetting(TeacherReportSettingEntity(v[0],v[1],v[2].ifBlank{null},v[3].toBoolean(),v[4],v[5],v[6].toIntOrNull(),v[7].toIntOrNull(),v[8].toBoolean(),v[9].ifBlank{null},v[10],v[11],v[12],v[13].toBoolean(),v[14].toBoolean(),v[15].toBoolean(),v[16].toBoolean(),v[17].toLong(),v[18].toLong()))}
            "FLAG"->{val v=p.drop(1).map(::d);dao.upsertFeatureFlag(FeatureFlagEntity(v[0],v[1],v[2].toBoolean(),v[3].toLong()))}
            "ROLEPERM"->{val role=dao.getRoleIdByName(d(p[1]));val perm=dao.getPermissionIdByCode(d(p[2]));if(role!=null&&perm!=null)dao.insertRolePermission(RolePermissionEntity(role,perm))}
            "YEAR"->{val v=p.drop(1).map(::d);val start=java.time.LocalDate.parse(v[2]);val end=java.time.LocalDate.parse(v[3]);require(!end.isBefore(start)){"ACADEMIC_YEAR_DATE_RANGE_INVALID"};val row=AcademicYearEntity(v[0],v[1],v[2],v[3],v[4].toBoolean());if(dao.getAcademicYearById(row.id)==null)dao.insertAcademicYear(row) else dao.updateAcademicYear(row);hierarchySyncOutbox?.recordAcademicYear(row)}
            "SEMESTER"->{val v=p.drop(1).map(::d);val year=dao.getAcademicYearById(v[2])?:error("ACADEMIC_YEAR_NOT_FOUND");val start=java.time.LocalDate.parse(v[3]);val end=java.time.LocalDate.parse(v[4]);val yearStart=java.time.LocalDate.parse(year.startDate);val yearEnd=java.time.LocalDate.parse(year.endDate);require(!end.isBefore(start)&&!start.isBefore(yearStart)&&!end.isAfter(yearEnd)){"SEMESTER_OUTSIDE_ACADEMIC_YEAR"};val row=SemesterEntity(v[0],v[1],v[2],v[3],v[4],SemesterStatus.valueOf(v[5]),v[6].toLong(),v[7].toLong());if(dao.getSemesterById(row.id)==null)dao.insertSemester(row) else dao.updateSemester(row);hierarchySyncOutbox?.recordSemester(row)}
            "UI"->ui=d(p[1]) to d(p[2])
        }}}
        ui?.let{preferences.setLanguage(it.first);preferences.setTheme(it.second)}
    }
}
