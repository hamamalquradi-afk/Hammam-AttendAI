package com.hammam.attendai.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.ai.*
import com.hammam.attendai.data.local.entity.*
import com.hammam.attendai.data.local.dao.UserAccessRow
import com.hammam.attendai.data.repository.AcademicManagementRepository
import com.hammam.attendai.data.repository.TimetableRepository
import com.hammam.attendai.importexport.StudentCsv
import com.hammam.attendai.importexport.StudentImportPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File


data class DeviceReplacementReviewRow(
    val request:DeviceReplacementRequestEntity,
    val studentName:String,
    val universityNumber:String?,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdminOperationsViewModel(app:Application):AndroidViewModel(app){
    private val container=(app as HammamAttendAiApplication).container
    private val repo=container.academic
    private val timetable=container.timetable
    private val preferences=container.preferences
    private val dao=container.database.coreDao()

    val universities=repo.universities.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val faculties=repo.faculties.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val departments=repo.departments.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val academicYears=repo.academicYears.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val semesters=repo.semesters.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val levels=repo.levels.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val batches=repo.batches.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val sections=repo.sections.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val groups=repo.groups.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val teachers=repo.teachers.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val subjects=repo.subjects.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reportSettings=container.reports.observeAllSettings().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val accessUsers=dao.observeUserAccessRows().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val accessRoles=dao.observeRoles().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val permissionDefinitions=dao.observePermissions().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val pendingDeviceReplacements:StateFlow<List<DeviceReplacementReviewRow>> = preferences.userId.flatMapLatest{uid->
        if(uid==null)flowOf(emptyList()) else dao.observePendingDeviceReplacementRequests().map{requests->
            requests.filter{container.authorization.hasScopedPermission(uid,"MANAGE_DEVICE_ENROLLMENT","STUDENT",it.studentId)}.mapNotNull{request->
                dao.getStudentById(request.studentId)?.let{student->DeviceReplacementReviewRow(request,student.fullName,student.universityNumber)}
            }
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())

    private val _selectedGroup=MutableStateFlow<String?>(null);val selectedGroup=_selectedGroup.asStateFlow()
    val timetableVersions=selectedGroup.flatMapLatest{g->if(g==null)flowOf(emptyList()) else timetable.observeVersions(g)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _review=MutableStateFlow<TimetableRepository.DraftReview?>(null);val review=_review.asStateFlow()
    private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
    private val _providerHealth=MutableStateFlow<List<ProviderHealth>>(emptyList());val providerHealth=_providerHealth.asStateFlow()
    private val _defaultAiProvider=MutableStateFlow(AiProviderNames.OPENAI);val defaultAiProvider=_defaultAiProvider.asStateFlow()
    private val _reportModelAssignment=MutableStateFlow("USE_DEFAULT");val reportModelAssignment=_reportModelAssignment.asStateFlow()
    private val _complexModelAssignment=MutableStateFlow("USE_DEFAULT");val complexModelAssignment=_complexModelAssignment.asStateFlow()
    private val _backupStatus=MutableStateFlow<String?>(null);val backupStatus=_backupStatus.asStateFlow()
    private val _selectedBackupUri=MutableStateFlow<Uri?>(null);val selectedBackupUri=_selectedBackupUri.asStateFlow()
    private val _shareBackupUri=MutableStateFlow<Uri?>(null);val shareBackupUri=_shareBackupUri.asStateFlow()
    private val _configPreview=MutableStateFlow<com.hammam.attendai.backup.ConfigurationBackupManager.Preview?>(null);val configPreview=_configPreview.asStateFlow()
    private val _selectedConfigUri=MutableStateFlow<Uri?>(null);val selectedConfigUri=_selectedConfigUri.asStateFlow()
    private val _studentImportPreview=MutableStateFlow<StudentImportPreview?>(null);val studentImportPreview=_studentImportPreview.asStateFlow()
    private val _integrityResult=MutableStateFlow<com.hammam.attendai.data.repository.DataIntegrityRepository.Result?>(null);val integrityResult=_integrityResult.asStateFlow()
    init{viewModelScope.launch{loadAiState()}}

    fun clearMessage(){_message.value=null}
    private suspend fun loadAiState(){_providerHealth.value=AiProviderNames.all.map{container.aiProviderManager.health(it)};_defaultAiProvider.value=container.aiProviderManager.defaultProvider();_reportModelAssignment.value=container.aiProviderManager.assignment("report");_complexModelAssignment.value=container.aiProviderManager.assignment("complex")}
    private fun aiAction(action:suspend(String)->Unit)=viewModelScope.launch{try{val uid=actor();require(container.authorization.hasPermission(uid,"MANAGE_AI_PROVIDER")){"MANAGE_AI_PROVIDER_PERMISSION_REQUIRED"};action(uid);loadAiState()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Log.e("AdminOperationsViewModel","AI_PROVIDER_OPERATION_FAILED",e);_message.value=e.message?:"AI_PROVIDER_OPERATION_FAILED"}}
    fun setAiMode(provider:String,mode:AiKeyMode)=aiAction{container.aiProviderManager.setMode(provider,mode);_message.value="AI_PROVIDER_MODE_UPDATED"}
    fun setAiKey(provider:String,key:String)=aiAction{container.aiProviderManager.setKey(provider,key.ifBlank{null});_message.value="AI_PROVIDER_KEY_UPDATED"}
    fun deleteAiKey(provider:String)=aiAction{container.aiProviderManager.setKey(provider,null);_message.value="AI_PROVIDER_KEY_REMOVED"}
    fun testAiProvider(provider:String)=aiAction{val ok=container.aiProviderManager.test(provider);_message.value=if(ok)"AI_PROVIDER_CONNECTED" else "AI_PROVIDER_TEST_FAILED"}
    fun refreshAiModels(provider:String)=aiAction{container.aiProviderManager.refreshModels(provider);_message.value="AI_MODELS_REFRESHED"}
    fun selectAiModel(provider:String,model:String)=aiAction{container.aiProviderManager.setSelectedModel(provider,model);_message.value="AI_MODEL_SELECTED"}
    fun setDefaultAiProvider(provider:String)=aiAction{container.aiProviderManager.setDefaultProvider(provider);_message.value="AI_DEFAULT_PROVIDER_UPDATED"}
    fun setAiAssignment(kind:String,value:String)=aiAction{container.aiProviderManager.setAssignment(kind,value);_message.value="AI_MODEL_ASSIGNMENT_UPDATED"}
    fun selectGroup(id:String?){_selectedGroup.value=id}
    fun createManagedUser(name:String,role:String,scopeType:String?,scopeId:String?)=launch{uid->container.authorization.createManagedUser(name,role,scopeType?.ifBlank{null},scopeId?.ifBlank{null},uid);_message.value="USER_CREATED"}
    fun setUserActive(userId:String,active:Boolean,reason:String)=launch{uid->container.authorization.setUserActive(userId,active,uid,reason);_message.value=if(active)"USER_ENABLED" else "USER_DISABLED"}
    fun replaceUserRole(userId:String,role:String,reason:String)=launch{uid->container.authorization.replaceRole(userId,role,uid,reason);_message.value="ROLE_CHANGED"}
    fun grantUserScope(userId:String,scopeType:String,scopeId:String?,reason:String)=launch{uid->container.authorization.grantScope(userId,scopeType,scopeId?.ifBlank{null},uid,reason);_message.value="SCOPE_GRANTED"}
    fun grantTemporaryPermission(userId:String,permission:String,scopeType:String?,scopeId:String?,hours:Int?,reason:String)=launch{uid->val now=System.currentTimeMillis();container.authorization.grantTemporaryPermission(userId,permission,scopeType?.ifBlank{null},scopeId?.ifBlank{null},now,hours?.takeIf{it>0}?.let{now+it*3_600_000L},uid,reason);_message.value="PERMISSION_GRANTED"}
    fun acceptDevicePairing(payload:String)=launch{uid->container.devices.acceptPairingOffer(payload.trim(),uid);_message.value="DEVICE_PAIRING_ACCEPTED"}
    fun rejectDevicePairing(payload:String)=launch{uid->container.devices.rejectPairingOffer(payload.trim(),uid);_message.value="DEVICE_PAIRING_REJECTED"}
    fun approveDeviceReplacement(requestId:String,note:String)=launch{uid->require(note.isNotBlank()){"REASON_REQUIRED"};container.devices.approveReplacement(requestId,uid,note);_message.value="DEVICE_REPLACEMENT_APPROVED"}
    fun rejectDeviceReplacement(requestId:String,note:String)=launch{uid->require(note.isNotBlank()){"REASON_REQUIRED"};container.devices.rejectReplacement(requestId,uid,note);_message.value="DEVICE_REPLACEMENT_REJECTED"}

    private suspend fun actor():String=preferences.userId.first()?:error("USER_NOT_INITIALIZED")
    private fun launch(action:suspend(String)->Unit)=viewModelScope.launch{try{action(actor())}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Log.e("AdminOperationsViewModel","OPERATION_FAILED",e);_message.value=e.message?:"OPERATION_FAILED"}}

    fun addUniversity(name:String)=launch{repo.addUniversity(name,it);_message.value="UNIVERSITY_CREATED"}
    fun addFaculty(universityId:String,name:String)=launch{repo.addFaculty(universityId,name,it);_message.value="FACULTY_CREATED"}
    fun addDepartment(facultyId:String,name:String)=launch{repo.addDepartment(facultyId,name,it);_message.value="DEPARTMENT_CREATED"}
    fun addAcademicYear(name:String,start:String,end:String)=launch{repo.addAcademicYear(name,start,end,it);_message.value="ACADEMIC_YEAR_CREATED"}
    fun addSemester(name:String,academicYearId:String,start:String,end:String)=launch{repo.addSemester(name,academicYearId,start,end,it);_message.value="SEMESTER_CREATED"}
    fun addLevel(departmentId:String,name:String,order:Int)=launch{repo.addLevel(departmentId,name,order,it);_message.value="LEVEL_CREATED"}
    fun addBatch(levelId:String,name:String,academicYearId:String)=launch{repo.addBatch(levelId,name,academicYearId,it);_message.value="BATCH_CREATED"}
    fun addSection(batchId:String,name:String)=launch{repo.addSection(batchId,name,it);_message.value="SECTION_CREATED"}
    fun addGroup(sectionId:String,name:String)=launch{repo.addGroup(sectionId,name,it);_message.value="GROUP_CREATED"}
    fun addTeacher(name:String,phone:String?,wa:String?,email:String?)=launch{repo.addTeacher(name,phone,wa,email,it);_message.value="TEACHER_CREATED"}
    fun archiveTeacher(id:String,reason:String)=launch{repo.archiveTeacher(id,it,reason);_message.value="TEACHER_ARCHIVED"}
    fun addSubject(code:String,name:String,teacherId:String,levelId:String,semesterId:String,groupId:String,policyId:String?)=launch{repo.addSubject(code,name,teacherId,levelId,semesterId,groupId,policyId,it);_message.value="SUBJECT_CREATED"}
    fun archiveSubject(id:String,reason:String)=launch{repo.archiveSubject(id,it,reason);_message.value="SUBJECT_ARCHIVED"}
    fun saveReportSetting(teacherId:String,subjectId:String?,enabled:Boolean,frequencies:Set<String>,sendTime:String,weeklyDay:Int?,monthlyDay:Int?,timezone:String,channel:String,format:String,includeDetails:Boolean,requireApproval:Boolean,aiSummary:Boolean,sendIfNoLecture:Boolean)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_REPORT_SETTINGS")){"MANAGE_REPORT_SETTINGS_PERMISSION_REQUIRED"}
        require(teacherId.isNotBlank() && frequencies.isNotEmpty()){"REPORT_SETTING_REQUIRED_FIELDS"}
        val existing=reportSettings.value.firstOrNull{it.teacherId==teacherId&&it.subjectId==subjectId}
        val now=System.currentTimeMillis();val row=TeacherReportSettingEntity(existing?.id?:java.util.UUID.randomUUID().toString(),teacherId,subjectId,enabled,frequencies.sorted().joinToString(","),sendTime,weeklyDay,monthlyDay,"END_OF_SEMESTER" in frequencies,null,timezone,channel,format,includeDetails,requireApproval,aiSummary,sendIfNoLecture,now,(existing?.version?:0)+1)
        container.reports.saveSetting(row,uid);_message.value="REPORT_SETTING_SAVED"
    }

    fun rollover(oldSemesterId:String,newName:String,newYearId:String,start:String,end:String,copySubjects:Boolean,copyTimetable:Boolean)=launch{
        repo.closeSemesterAndStartNew(oldSemesterId,newName,newYearId,start,end,it,AcademicManagementRepository.RolloverOptions(copySubjects,copyTimetable));_message.value="SEMESTER_ROLLOVER_COMPLETE"
    }

    fun createManualDraft(groupId:String,weekStart:String)=launch{uid->val id=timetable.createDraft(groupId,weekStart,"MANUAL",null,uid);_selectedGroup.value=groupId;loadReview(id);_message.value="TIMETABLE_DRAFT_CREATED"}
    fun importDocument(groupId:String,weekStart:String,uri:Uri,mime:String?)=launch{uid->
        val type=when{mime?.contains("pdf",true)==true->"PDF";mime?.startsWith("image/")==true->"IMAGE";else->"DOCUMENT"}
        val id=timetable.createDraft(groupId,weekStart,type,uri.toString(),uid);_selectedGroup.value=groupId
        val text=if(type=="DOCUMENT")readText(uri) else tryVision(uri,mime?:if(type=="PDF")"application/pdf" else "image/jpeg")
        if(!text.isNullOrBlank())timetable.saveManualCsv(id,text,uid) else timetable.markNeedsReview(id,uid,"SOURCE_SAVED_MANUAL_REVIEW_REQUIRED")
        loadReview(id);_message.value=if(text.isNullOrBlank())"SOURCE_SAVED_MANUAL_REVIEW_REQUIRED" else "TIMETABLE_IMPORTED_FOR_REVIEW"
    }
    fun importCameraImage(groupId:String,weekStart:String,uri:Uri)=launch{uid->val id=timetable.createDraft(groupId,weekStart,"CAMERA",uri.toString(),uid);val text=tryVision(uri,"image/jpeg");if(!text.isNullOrBlank())timetable.saveManualCsv(id,text,uid) else timetable.markNeedsReview(id,uid,"IMAGE_REQUIRES_REVIEW_OR_VISION_EXTRACTION");_selectedGroup.value=groupId;loadReview(id);_message.value=if(text.isNullOrBlank())"IMAGE_DRAFT_SAVED" else "TIMETABLE_IMPORTED_FOR_REVIEW"}
    fun saveRows(scheduleId:String,rows:List<TimetableRepository.DraftRow>)=launch{uid->val issues=timetable.replaceDraftRows(scheduleId,rows,uid);loadReview(scheduleId);_message.value=if(issues.isEmpty())"TIMETABLE_DRAFT_VALID" else "TIMETABLE_NEEDS_REVIEW"}
    fun approve(scheduleId:String,reason:String)=launch{uid->timetable.approve(scheduleId,uid,reason);loadReview(scheduleId);_message.value="TIMETABLE_APPROVED"}
    fun loadReview(id:String){viewModelScope.launch{runCatching{timetable.review(id)}.onSuccess{_review.value=it}.onFailure{_message.value=it.message?:"TIMETABLE_LOAD_FAILED"}}}

    fun runDataIntegrity()=launch{uid->require(container.authorization.hasPermission(uid,"RUN_DATA_INTEGRITY")){"RUN_DATA_INTEGRITY_PERMISSION_REQUIRED"};_integrityResult.value=container.dataIntegrity.run();_message.value=if(_integrityResult.value?.healthy==true)"DATA_INTEGRITY_HEALTHY" else "DATA_INTEGRITY_NEEDS_ATTENTION"}

    fun previewStudentCsv(uri:Uri)=launch{uid->
        require(container.authorization.hasPermission(uid,"EDIT_STUDENTS")){"EDIT_STUDENTS_PERMISSION_REQUIRED"}
        val text=readText(uri)?:error("CSV_READ_FAILED");_studentImportPreview.value=container.students.validateImport(StudentCsv.preview(text));_message.value="STUDENT_CSV_PREVIEW_READY"
    }
    fun confirmStudentCsvImport()=launch{uid->
        require(container.authorization.hasPermission(uid,"EDIT_STUDENTS")){"EDIT_STUDENTS_PERMISSION_REQUIRED"}
        val preview=_studentImportPreview.value?:error("CSV_PREVIEW_REQUIRED");require(preview.invalid.isEmpty()){"CSV_HAS_INVALID_ROWS"};val count=container.students.confirmImport(preview.valid,uid);_studentImportPreview.value=null;_message.value="STUDENT_CSV_IMPORTED_$count"
    }
    fun exportStudentsCsv(uri:Uri)=launch{uid->
        require(container.authorization.hasPermission(uid,"VIEW_STUDENTS")){"VIEW_STUDENTS_PERMISSION_REQUIRED"}
        val rows=container.students.observeScoped(uid).first().map{it.universityNumber to it.fullName};val csv=StudentCsv.export(rows);getApplication<Application>().contentResolver.openOutputStream(uri,"w")!!.bufferedWriter(Charsets.UTF_8).use{it.write(csv)};_message.value="STUDENT_CSV_EXPORTED"
    }
    fun clearStudentCsvPreview(){_studentImportPreview.value=null}

    fun exportConfiguration(uri:Uri)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_SETTINGS_PERMISSION_REQUIRED"}
        val tmp=File(getApplication<Application>().cacheDir,"hammam-config-${System.currentTimeMillis()}.hconf")
        try{container.configurationBackup.export(tmp);getApplication<Application>().contentResolver.openOutputStream(uri,"w")!!.use{out->tmp.inputStream().use{it.copyTo(out)}};_message.value="CONFIGURATION_EXPORTED"}finally{tmp.delete()}
    }
    fun previewConfiguration(uri:Uri)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_SETTINGS_PERMISSION_REQUIRED"}
        val tmp=copyUriToCache(uri,"config-preview")?:error("CONFIG_READ_FAILED");try{val preview=container.configurationBackup.preview(tmp);_configPreview.value=preview;if(preview.valid)_selectedConfigUri.value=uri;_message.value=if(preview.valid)"CONFIGURATION_VALIDATED" else "CONFIGURATION_INVALID"}finally{tmp.delete()}
    }
    fun importSelectedConfiguration()=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_SETTINGS_PERMISSION_REQUIRED"}
        val uri=_selectedConfigUri.value?:error("CONFIGURATION_NOT_SELECTED");val tmp=copyUriToCache(uri,"config-import")?:error("CONFIG_READ_FAILED");try{container.configurationBackup.importConfirmed(tmp);_message.value="CONFIGURATION_IMPORTED"}finally{tmp.delete()}
    }

    fun exportEncryptedBackup(uri:Uri,passphrase:String)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_BACKUP_PERMISSION_REQUIRED"}
        val app=getApplication<Application>();val tmp=File(app.cacheDir,"hammam-export-${System.currentTimeMillis()}.hammamattend")
        try{container.backupManager.createBackup(app.getDatabasePath("hammam_attendai.db"),tmp,passphrase.toCharArray());app.contentResolver.openOutputStream(uri,"w")!!.use{out->tmp.inputStream().use{it.copyTo(out)}};_backupStatus.value="BACKUP_CREATED"}finally{tmp.delete()}
    }
    fun validateBackup(uri:Uri,passphrase:String)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_BACKUP_PERMISSION_REQUIRED"}
        val tmp=copyUriToCache(uri,"backup-validate")?:error("BACKUP_READ_FAILED");try{val result=container.backupManager.validate(tmp,passphrase.toCharArray());require(result.valid){result.error?:"BACKUP_INVALID"};_selectedBackupUri.value=uri;_backupStatus.value="BACKUP_VALIDATED_DB_${result.databaseVersion}"}finally{tmp.delete()}
    }
    fun restoreSelectedBackup(passphrase:String)=launch{uid->
        require(container.authorization.hasPermission(uid,"RESTORE_BACKUP")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"RESTORE_BACKUP_PERMISSION_REQUIRED"}
        val uri=_selectedBackupUri.value?:error("BACKUP_NOT_SELECTED");val tmp=copyUriToCache(uri,"backup-restore")?:error("BACKUP_READ_FAILED")
        try{val result=container.backupManager.stageRestore(tmp,passphrase.toCharArray());require(result.valid){result.error?:"BACKUP_RESTORE_VALIDATION_FAILED"};_backupStatus.value="BACKUP_RESTORE_STAGED_RESTART_REQUIRED"}finally{tmp.delete()}
    }
    fun createShareableBackup(passphrase:String)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_BACKUP_PERMISSION_REQUIRED"}
        val app=getApplication<Application>();val dir=File(app.filesDir,"backups").apply{mkdirs()};val target=File(dir,"Hammam-AttendAI-${System.currentTimeMillis()}.hammamattend")
        container.backupManager.createBackup(app.getDatabasePath("hammam_attendai.db"),target,passphrase.toCharArray());_shareBackupUri.value=FileProvider.getUriForFile(app,"${app.packageName}.files",target);_backupStatus.value="BACKUP_READY_TO_SHARE"
    }
    fun consumeShareBackup(){_shareBackupUri.value=null}
    private fun copyUriToCache(uri:Uri,prefix:String):File?=runCatching{val app=getApplication<Application>();val f=File(app.cacheDir,"$prefix-${System.currentTimeMillis()}.bin");app.contentResolver.openInputStream(uri)!!.use{input->f.outputStream().use{input.copyTo(it)}};f}.getOrNull()

    private suspend fun tryVision(uri:Uri,mime:String):String?{
        val bytes=readBytesLimited(uri,4*1024*1024)?:return null
        val provider=container.aiProviderManager.defaultProvider();val health=container.aiProviderManager.health(provider);if(!health.configured||health.selectedModel.isNullOrBlank())return null
        val prompt="Extract the weekly university timetable. Return CSV only, no markdown. Columns: day,subjectCode,teacher,start,end,room,lectureType. day must be 1..7 and time HH:mm. Do not invent missing values."
        return container.aiProviderManager.extractTimetableVision(provider,mime,Base64.encodeToString(bytes,Base64.NO_WRAP),prompt)?.trim()?.removePrefix("```csv")?.removePrefix("```")?.removeSuffix("```")?.trim()
    }
    private fun readBytesLimited(uri:Uri,max:Int):ByteArray?=runCatching{getApplication<Application>().contentResolver.openInputStream(uri)?.use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);var total=0;while(true){val n=input.read(buf);if(n<0)break;total+=n;if(total>max)return@use null;out.write(buf,0,n)};out.toByteArray()}}.getOrNull()
    private fun readText(uri:Uri):String?=runCatching{getApplication<Application>().contentResolver.openInputStream(uri)?.use{stream->BufferedReader(InputStreamReader(stream,Charsets.UTF_8)).readText()}}.getOrNull()
}
