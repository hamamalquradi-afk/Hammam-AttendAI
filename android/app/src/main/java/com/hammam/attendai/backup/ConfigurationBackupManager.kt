package com.hammam.attendai.backup

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.repository.FeatureFlagCatalog
import com.hammam.attendai.importexport.LocalAtomicFile
import com.hammam.attendai.data.settings.AppPreferences
import com.hammam.attendai.domain.model.SemesterStatus
import com.hammam.attendai.reports.ReportLifecycleRules
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.sync.AcademicHierarchySyncOutbox
import com.hammam.attendai.sync.AcademicReferenceSyncOutbox
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Non-secret HAMMAM_CONFIG_V1 bundle. Parsing/semantic validation is shared by preview and import. */
class ConfigurationBackupManager(
    private val db:HammamDatabase,
    private val preferences:AppPreferences,
    private val authorization:AuthorizationRepository,
    cipher:KeystoreCipher?=null,
){
    private val hierarchySyncOutbox=cipher?.let{AcademicHierarchySyncOutbox(db,it)}
    private val referenceSyncOutbox=cipher?.let{AcademicReferenceSyncOutbox(db,it)}
    data class Preview(val valid:Boolean,val policies:Int,val reportSettings:Int,val featureFlags:Int,val rolePermissions:Int,val academicYears:Int,val semesters:Int,val issues:List<String>)
    private data class Parsed(
        val policies:List<AttendancePolicyEntity>,val reports:List<TeacherReportSettingEntity>,val flags:List<FeatureFlagEntity>,
        val rolePerms:List<Pair<String,String>>,val years:List<AcademicYearEntity>,val semesters:List<SemesterEntity>,val ui:Pair<String,String>?,val issues:List<String>,
    )
    private val dao=db.coreDao()
    private fun e(v:String?)=ConfigurationBackupFormat.encode(v)
    private fun d(v:String)=ConfigurationBackupFormat.decode(v)

    suspend fun export(target:File,actorId:String){
        require(dao.isUserActive(actorId)){"LOCAL_SESSION_INVALID"}
        require(authorization.hasPermission(actorId,"MANAGE_CONFIGURATION")||authorization.hasPermission(actorId,"MANAGE_BACKUP")){"MANAGE_CONFIGURATION_PERMISSION_REQUIRED"}
        target.parentFile?.mkdirs();val lines=mutableListOf(ConfigurationBackupFormat.HEADER)
        dao.getAttendancePolicies().forEach{p->lines+="POLICY|${listOf(p.id,p.name,p.scopeType,p.scopeId,p.fullAttendanceThreshold,p.partialAttendanceThreshold,p.lateAfterMinutes,p.earlyLeaveThresholdMinutes,p.absenceThreshold,p.temporaryMissingGraceSeconds,p.minimumPresenceVerificationSeconds,p.confidenceThreshold,p.createdAt,p.updatedAt,p.version).joinToString("|"){e(it.toString())}}"}
        dao.getAllReportSettings().forEach{r->lines+="REPORT|${listOf(r.id,r.teacherId,r.subjectId,r.enabled,r.frequency,r.sendTime,r.weeklyDay,r.monthlyDay,r.semesterReportEnabled,r.customRule,r.timezone,r.channel,r.reportFormat,r.includeStudentDetails,r.requireApproval,r.aiSummaryEnabled,r.sendIfNoLecture,r.updatedAt,r.version).joinToString("|"){e(it?.toString())}}"}
        dao.getFeatureFlags().filter{it.code in FeatureFlagCatalog.codes}.forEach{f->lines+="FLAG|${e(f.id)}|${e(f.code)}|${e(f.enabled.toString())}|${e(f.updatedAt.toString())}"}
        dao.getRolePermissionExport().forEach{rp->lines+="ROLEPERM|${e(rp.roleName)}|${e(rp.permissionCode)}"}
        dao.getAcademicYearsOnce().forEach{a->lines+="YEAR|${listOf(a.id,a.name,a.startDate,a.endDate,a.isActive).joinToString("|"){e(it.toString())}}"}
        dao.getSemestersOnce().forEach{s->lines+="SEMESTER|${listOf(s.id,s.name,s.academicYearId,s.startDate,s.endDate,s.status.name,s.createdAt,s.updatedAt).joinToString("|"){e(it.toString())}}"}
        lines+="UI|${e(preferences.language.first())}|${e(preferences.theme.first())}"
        val partial=File(target.parentFile?:target.absoluteFile.parentFile,".${target.name}.partial")
        try{partial.writeText(lines.joinToString("\n"),Charsets.UTF_8);LocalAtomicFile.replaceFrom(partial,target,"CONFIGURATION_EXPORT_REPLACE_FAILED")}finally{partial.delete()}
    }

    suspend fun preview(source:File,actorId:String):Preview{
        val parsed=parse(source,actorId)
        return Preview(parsed.issues.isEmpty(),parsed.policies.size,parsed.reports.size,parsed.flags.size,parsed.rolePerms.size,parsed.years.size,parsed.semesters.size,parsed.issues)
    }

    suspend fun importConfirmed(source:File,actorId:String){
        require(dao.isUserActive(actorId)){"LOCAL_SESSION_INVALID"}
        require(authorization.hasPermission(actorId,"MANAGE_CONFIGURATION")||authorization.hasPermission(actorId,"MANAGE_SETTINGS")||authorization.hasPermission(actorId,"MANAGE_BACKUP")){"MANAGE_CONFIGURATION_PERMISSION_REQUIRED"}
        val parsed=parse(source,actorId);require(parsed.issues.isEmpty()){parsed.issues.firstOrNull()?:"CONFIGURATION_INVALID"}
        db.withTransaction{
            parsed.policies.forEach{row->dao.upsertAttendancePolicy(row);referenceSyncOutbox?.enqueueAttendancePolicyIfEnabled(row)}
            parsed.reports.forEach{row->dao.upsertTeacherReportSetting(row)}
            parsed.flags.forEach{row->dao.upsertFeatureFlag(row)}
            parsed.rolePerms.forEach{(roleName,permissionCode)->
                require(authorization.isSystemOwner(actorId)){"SYSTEM_OWNER_REQUIRED_FOR_ROLEPERM_IMPORT"}
                val role=dao.getRoleIdByName(roleName)?:error("ROLE_NOT_FOUND");val perm=dao.getPermissionIdByCode(permissionCode)?:error("PERMISSION_NOT_FOUND")
                dao.insertRolePermission(RolePermissionEntity(role,perm))
            }
            parsed.years.forEach{row->if(dao.getAcademicYearById(row.id)==null)dao.insertAcademicYear(row) else dao.updateAcademicYear(row);hierarchySyncOutbox?.recordAcademicYear(row)}
            parsed.semesters.forEach{row->if(dao.getSemesterById(row.id)==null)dao.insertSemester(row) else dao.updateSemester(row);hierarchySyncOutbox?.recordSemester(row)}
            dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"CONFIGURATION_IMPORTED","Configuration",null,null,"{\"policies\":${parsed.policies.size},\"reports\":${parsed.reports.size},\"flags\":${parsed.flags.size},\"rolePermissions\":${parsed.rolePerms.size},\"years\":${parsed.years.size},\"semesters\":${parsed.semesters.size}}",null,System.currentTimeMillis(),authorization.roleNames(actorId).firstOrNull()))
        }
        // UI preferences are intentionally changed only after the DB transaction committed successfully.
        parsed.ui?.let{preferences.setLanguage(it.first);preferences.setTheme(it.second)}
    }

    private suspend fun parse(source:File,actorId:String):Parsed{
        val lines=runCatching{source.readLines(Charsets.UTF_8)}.getOrElse{return Parsed(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),null,listOf("CONFIG_READ_FAILED"))}
        ConfigurationBackupFormat.headerIssue(lines)?.let{return Parsed(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),null,listOf(it))}
        val policies=mutableListOf<AttendancePolicyEntity>();val reports=mutableListOf<TeacherReportSettingEntity>();val flags=mutableListOf<FeatureFlagEntity>();val rolePerms=mutableListOf<Pair<String,String>>();val years=mutableListOf<AcademicYearEntity>();val semesters=mutableListOf<SemesterEntity>();var ui:Pair<String,String>?=null
        val issues=mutableListOf<String>();val ids=mutableSetOf<String>();val existingYears=dao.getAcademicYearsOnce().associateBy{it.id}.toMutableMap()
        lines.drop(1).forEachIndexed{idx,line->
            if(line.isBlank())return@forEachIndexed
            val lineNo=idx+2;val p=line.split('|');val section=p.firstOrNull().orEmpty()
            if(!ConfigurationBackupFormat.isAllowedSection(section)){issues+="LINE_$lineNo:UNKNOWN_SECTION";return@forEachIndexed}
            fun issue(code:String){issues+="LINE_$lineNo:$code"}
            try{
                when(section){
                    "POLICY"->{
                        require(p.size==16);val v=p.drop(1).map(::d);require(ids.add("POLICY:${v[0]}")){"DUPLICATE_CONFIG_ID"};require(v[1].isNotBlank()){"ATTENDANCE_POLICY_NAME_REQUIRED"}
                        val full=v[4].toDouble();val partial=v[5].toDouble();val late=v[6].toInt();val early=v[7].toInt();val absence=v[8].toDouble();val grace=v[9].toLong();val min=v[10].toLong();val confidence=v[11].toDouble()
                        require(full in 0.0..1.0&&partial in 0.0..1.0&&absence in 0.0..1.0&&confidence in 0.0..1.0){"ATTENDANCE_POLICY_THRESHOLD_OUT_OF_RANGE"};require(full>=partial&&partial>=absence){"ATTENDANCE_POLICY_THRESHOLD_ORDER_INVALID"};require(late>=0&&early>=0&&grace>=0&&min>=0){"ATTENDANCE_POLICY_DURATION_INVALID"}
                        require(v[2].matches(Regex("[A-Z_]{2,32}"))){"ATTENDANCE_POLICY_SCOPE_INVALID"};if(v[2]!="GLOBAL")require(v[3].isNotBlank()){"ATTENDANCE_POLICY_SCOPE_ID_REQUIRED"}
                        policies+=AttendancePolicyEntity(v[0],v[1],v[2],v[3].ifBlank{null},full,partial,late,early,absence,grace,min,confidence,v[12].toLong(),v[13].toLong(),v[14].toLong().also{require(it>=1){"VERSION_INVALID"}})
                    }
                    "REPORT"->{
                        require(p.size==20);val v=p.drop(1).map(::d);require(ids.add("REPORT:${v[0]}")){"DUPLICATE_CONFIG_ID"}
                        val teacher=dao.getTeacherById(v[1])?:error("REPORT_TEACHER_MISSING");require(teacher.archivedAt==null){"REPORT_TEACHER_ARCHIVED"}
                        val subject=v[2].ifBlank{null}?.let{dao.getSubjectById(it)?:error("REPORT_SUBJECT_MISSING")};if(subject!=null){require(subject.archivedAt==null&&subject.status!="ARCHIVED"){"REPORT_SUBJECT_ARCHIVED"};require(subject.teacherId==null||subject.teacherId==teacher.id||dao.isTeacherLinkedToSubject(teacher.id,subject.id)){"REPORT_TEACHER_SUBJECT_MISMATCH"}}
                        val enabled=strictBool(v[3]);val frequencies=v[4].split(',').filter{it.isNotBlank()}.toSet();require(frequencies.isNotEmpty()&&frequencies.all{it in setOf("DAILY","WEEKLY","MONTHLY","END_OF_SEMESTER")}){"REPORT_FREQUENCY_INVALID"}
                        val weekly=v[6].toIntOrNull();val monthly=v[7].toIntOrNull();ReportLifecycleRules.validateSchedule(frequencies,v[5],weekly,monthly,v[10],v[9].ifBlank{null})?.let{error(it)}
                        require(v[11] in setOf("EMAIL","WHATSAPP")){"REPORT_CHANNEL_INVALID"};require(v[12] in setOf("PDF","CSV","SUMMARY")){"REPORT_FORMAT_INVALID"}
                        reports+=TeacherReportSettingEntity(v[0],teacher.id,subject?.id,enabled,v[4],v[5],weekly,monthly,strictBool(v[8]),v[9].ifBlank{null},v[10],v[11],v[12],strictBool(v[13]),strictBool(v[14]),strictBool(v[15]),strictBool(v[16]),v[17].toLong(),v[18].toLong().also{require(it>=1){"VERSION_INVALID"}})
                    }
                    "FLAG"->{require(p.size==5);val v=p.drop(1).map(::d);require(v[1] in FeatureFlagCatalog.codes){"UNKNOWN_FEATURE_FLAG"};require(ids.add("FLAG:${v[1]}")){"DUPLICATE_CONFIG_ID"};flags+=FeatureFlagEntity(v[0],v[1],strictBool(v[2]),v[3].toLong())}
                    "ROLEPERM"->{require(p.size==3);val role=d(p[1]);val perm=d(p[2]);require(dao.getRoleIdByName(role)!=null){"ROLE_MISSING"};require(dao.getPermissionIdByCode(perm)!=null){"PERMISSION_MISSING"};require(rolePerms.addUnique(role to perm)){"DUPLICATE_ROLE_PERMISSION"};if(!authorization.isSystemOwner(actorId))error("SYSTEM_OWNER_REQUIRED_FOR_ROLEPERM_IMPORT")}
                    "YEAR"->{require(p.size==6);val v=p.drop(1).map(::d);require(v[1].isNotBlank()){"ACADEMIC_YEAR_NAME_REQUIRED"};require(ids.add("YEAR:${v[0]}")){"DUPLICATE_CONFIG_ID"};val start=LocalDate.parse(v[2]);val end=LocalDate.parse(v[3]);require(!end.isBefore(start)){"ACADEMIC_YEAR_DATE_RANGE_INVALID"};val row=AcademicYearEntity(v[0],v[1],v[2],v[3],strictBool(v[4]));years+=row;existingYears[row.id]=row}
                    "SEMESTER"->{require(p.size==9);val v=p.drop(1).map(::d);require(v[1].isNotBlank()){"SEMESTER_NAME_REQUIRED"};require(ids.add("SEMESTER:${v[0]}")){"DUPLICATE_CONFIG_ID"};val year=existingYears[v[2]]?:error("ACADEMIC_YEAR_NOT_FOUND");val start=LocalDate.parse(v[3]);val end=LocalDate.parse(v[4]);val ys=LocalDate.parse(year.startDate);val ye=LocalDate.parse(year.endDate);require(!end.isBefore(start)&&!start.isBefore(ys)&&!end.isAfter(ye)){"SEMESTER_OUTSIDE_ACADEMIC_YEAR"};val status=SemesterStatus.valueOf(v[5]);semesters+=SemesterEntity(v[0],v[1],v[2],v[3],v[4],status,v[6].toLong(),v[7].toLong())}
                    "UI"->{require(p.size==3);val language=d(p[1]);val theme=d(p[2]);require(language in setOf("AR","EN")){"LANGUAGE_INVALID"};require(theme in setOf("SYSTEM","LIGHT","DARK")){"THEME_INVALID"};require(ui==null){"DUPLICATE_UI_SECTION"};ui=language to theme}
                }
            }catch(t:Throwable){issue(t.message?.takeIf{it.matches(Regex("[A-Z][A-Z0-9_]{2,80}"))}?:"INVALID_ROW")}
        }
        if(issues.isEmpty()){
            if(policies.isNotEmpty()&&!authorization.hasPermission(actorId,"MANAGE_SUBJECTS"))issues+="MANAGE_SUBJECTS_PERMISSION_REQUIRED"
            if(reports.isNotEmpty()&&!authorization.hasPermission(actorId,"MANAGE_REPORT_SETTINGS"))issues+="MANAGE_REPORT_SETTINGS_PERMISSION_REQUIRED"
            if(flags.isNotEmpty()&&!authorization.hasPermission(actorId,"MANAGE_SETTINGS"))issues+="MANAGE_SETTINGS_PERMISSION_REQUIRED"
            if(rolePerms.isNotEmpty()&&!authorization.isSystemOwner(actorId))issues+="SYSTEM_OWNER_REQUIRED_FOR_ROLEPERM_IMPORT"
            if((years.isNotEmpty()||semesters.isNotEmpty())&&!authorization.hasPermission(actorId,"MANAGE_ACADEMIC_STRUCTURE"))issues+="MANAGE_ACADEMIC_STRUCTURE_PERMISSION_REQUIRED"
        }
        return Parsed(policies,reports,flags,rolePerms,years,semesters,ui,issues.distinct())
    }

    private fun strictBool(value:String):Boolean=when(value){"true"->true;"false"->false;else->error("INVALID_BOOLEAN")}
    private fun <T> MutableList<T>.addUnique(value:T):Boolean=if(value in this)false else{add(value);true}
}
