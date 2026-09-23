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
import com.hammam.attendai.importexport.StudentImportTarget
import com.hammam.attendai.importexport.FileIoSafety
import com.hammam.attendai.importexport.LocalFileRetention
import com.hammam.attendai.security.LocalAccessRules
import com.hammam.attendai.reports.ReportLifecycleRules
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File


data class ShareableExport(val uri:Uri,val mimeType:String,val chooserTitleCode:String)

data class DeviceReplacementReviewRow(
    val request:DeviceReplacementRequestEntity,
    val studentName:String,
    val universityNumber:String?,
)
private data class DeviceReviewInputs(
    val active:Boolean,
    val permissions:Set<String>,
    val scopeRevision:Int,
    val requests:List<DeviceReplacementRequestEntity>,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdminOperationsViewModel(app:Application):AndroidViewModel(app){
    private val container=(app as HammamAttendAiApplication).container
    private val repo=container.academic
    private val timetable=container.timetable
    private val preferences=container.preferences
    private val dao=container.database.coreDao()
    val sessionUserId=preferences.userId.stateIn(viewModelScope,SharingStarted.Eagerly,null)

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
    val attendancePolicies=repo.attendancePolicies.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val reportActor=preferences.userId.flatMapLatest{uid->
        if(uid==null)flowOf<String?>(null) else combine(dao.observeUserById(uid),dao.observePermissionCodes(uid)){user,codes->uid.takeIf{user?.isActive==true && "MANAGE_REPORT_SETTINGS" in codes}}
    }
    val reportSettings=reportActor.flatMapLatest{uid->if(uid==null)flowOf(emptyList()) else container.reports.observeSettingsForUser(uid)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reportTeachers=reportActor.flatMapLatest{uid->if(uid==null)flowOf(emptyList()) else container.reports.observeReportTeachersForUser(uid)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reportSubjects=reportActor.flatMapLatest{uid->if(uid==null)flowOf(emptyList()) else container.reports.observeReportSubjectsForUser(uid)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val accessUsers=dao.observeUserAccessRows().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val accessRoles=dao.observeRoles().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val permissionDefinitions=dao.observePermissions().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val pendingDeviceReplacements:StateFlow<List<DeviceReplacementReviewRow>> = preferences.userId.flatMapLatest{uid->
        if(uid==null)flowOf(emptyList()) else combine(
            dao.observeUserById(uid),dao.observePermissionCodes(uid),dao.observeActiveUserScopes(uid),dao.observePendingDeviceReplacementRequests()
        ){user,permissionCodes,scopes,requests->DeviceReviewInputs(user?.isActive==true,permissionCodes.toSet(),scopes.size,requests)}.mapLatest{input->
            if(!input.active||"MANAGE_DEVICE_ENROLLMENT" !in input.permissions)emptyList() else input.requests
                .filter{container.authorization.hasScopedPermission(uid,"MANAGE_DEVICE_ENROLLMENT","STUDENT",it.studentId)}
                .mapNotNull{request->dao.getStudentById(request.studentId)?.let{student->DeviceReplacementReviewRow(request,student.fullName,student.universityNumber)}}
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
    private val _shareableExport=MutableStateFlow<ShareableExport?>(null);val shareableExport=_shareableExport.asStateFlow()
    private val _configPreview=MutableStateFlow<com.hammam.attendai.backup.ConfigurationBackupManager.Preview?>(null);val configPreview=_configPreview.asStateFlow()
    private val _selectedConfigUri=MutableStateFlow<Uri?>(null);val selectedConfigUri=_selectedConfigUri.asStateFlow()
    private val _studentImportPreview=MutableStateFlow<StudentImportPreview?>(null);val studentImportPreview=_studentImportPreview.asStateFlow()
    private val _studentImportTarget=MutableStateFlow<StudentImportTarget?>(null);val studentImportTarget=_studentImportTarget.asStateFlow()
    private val _integrityResult=MutableStateFlow<com.hammam.attendai.data.repository.DataIntegrityRepository.Result?>(null);val integrityResult=_integrityResult.asStateFlow()
    private val backupOperationInFlight=AtomicBoolean(false)
    private val configurationOperationInFlight=AtomicBoolean(false)
    private val studentTransferInFlight=AtomicBoolean(false)
    private val integrityScanInFlight=AtomicBoolean(false)
    private val timetableApprovalInFlight=AtomicBoolean(false)
    private val deviceReviewInFlight=AtomicBoolean(false)
    private val academicDestructiveInFlight=AtomicBoolean(false)
    private val _deviceReviewBusy=MutableStateFlow(false);val deviceReviewBusy=_deviceReviewBusy.asStateFlow()
    private val _deviceActionResult=MutableStateFlow<String?>(null);val deviceActionResult=_deviceActionResult.asStateFlow()
    private var reviewLoadJob:Job?=null
    init{viewModelScope.launch{loadAiState()}}

    fun clearMessage(){_message.value=null}
    fun consumeDeviceActionResult(){_deviceActionResult.value=null}
    fun clearSessionUiState(){_selectedGroup.value=null;_review.value=null;_configPreview.value=null;_selectedConfigUri.value=null;_studentImportPreview.value=null;_studentImportTarget.value=null;_selectedBackupUri.value=null;_shareBackupUri.value=null;_shareableExport.value=null;_integrityResult.value=null;_message.value=null}
    fun reportFileActionFailure(code:String){Log.w("AdminOperationsViewModel","FILE_ACTION_FAILURE:$code");_message.value=code}
    private suspend fun loadAiState(){_providerHealth.value=AiProviderNames.all.map{container.aiProviderManager.health(it)};_defaultAiProvider.value=container.aiProviderManager.defaultProvider();_reportModelAssignment.value=container.aiProviderManager.assignment("report");_complexModelAssignment.value=container.aiProviderManager.assignment("complex")}
    private fun aiAction(action:suspend(String)->Unit)=viewModelScope.launch{try{val uid=actor();require(container.authorization.hasPermission(uid,"MANAGE_AI_PROVIDER")){"MANAGE_AI_PROVIDER_PERMISSION_REQUIRED"};action(uid);loadAiState()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Log.w("AdminOperationsViewModel","AI_PROVIDER_OPERATION_FAILED");_message.value=when(e.message){"AI_PROVIDER_NOT_CONFIGURED"->"AI_PROVIDER_NOT_CONFIGURED";"AI_MODEL_NOT_SELECTED"->"AI_MODEL_NOT_SELECTED";else->AiProviderStateRules.failureStatus(e.message)}}}
    fun setAiMode(provider:String,mode:AiKeyMode)=aiAction{container.aiProviderManager.setMode(provider,mode);_message.value="AI_PROVIDER_MODE_UPDATED"}
    fun setAiKey(provider:String,key:String)=aiAction{container.aiProviderManager.setKey(provider,key.ifBlank{null});_message.value="AI_PROVIDER_KEY_UPDATED"}
    fun deleteAiKey(provider:String)=aiAction{container.aiProviderManager.setKey(provider,null);_message.value="AI_PROVIDER_KEY_REMOVED"}
    fun testAiProvider(provider:String)=aiAction{container.aiProviderManager.test(provider);_message.value=container.aiProviderManager.health(provider).connectionStatus}
    fun refreshAiModels(provider:String)=aiAction{container.aiProviderManager.refreshModels(provider);_message.value="AI_MODELS_REFRESHED"}
    fun selectAiModel(provider:String,model:String)=aiAction{container.aiProviderManager.setSelectedModel(provider,model.trim());_message.value="AI_MODEL_SELECTED"}
    fun setDefaultAiProvider(provider:String)=aiAction{container.aiProviderManager.setDefaultProvider(provider);_message.value="AI_DEFAULT_PROVIDER_UPDATED"}
    fun setAiAssignment(kind:String,value:String)=aiAction{container.aiProviderManager.setAssignment(kind,value);_message.value="AI_MODEL_ASSIGNMENT_UPDATED"}
    fun selectGroup(id:String?){_selectedGroup.value=id}
    fun createManagedUser(name:String,role:String,scopeType:String?,scopeId:String?)=launch{uid->container.authorization.createManagedUser(name,role,scopeType?.ifBlank{null},scopeId?.ifBlank{null},uid);_message.value="USER_CREATED"}
    fun setUserActive(userId:String,active:Boolean,reason:String)=launch{uid->container.authorization.setUserActive(userId,active,uid,reason);_message.value=if(active)"USER_ENABLED" else "USER_DISABLED"}
    fun replaceUserRole(userId:String,role:String,reason:String)=launch{uid->container.authorization.replaceRole(userId,role,uid,reason);_message.value="ROLE_CHANGED"}
    fun grantUserScope(userId:String,scopeType:String,scopeId:String?,reason:String)=launch{uid->container.authorization.grantScope(userId,scopeType,scopeId?.ifBlank{null},uid,reason);_message.value="SCOPE_GRANTED"}
    fun grantTemporaryPermission(userId:String,permission:String,scopeType:String?,scopeId:String?,hours:Int?,reason:String)=launch{uid->val now=System.currentTimeMillis();container.authorization.grantTemporaryPermission(userId,permission,scopeType?.ifBlank{null},scopeId?.ifBlank{null},now,hours?.takeIf{it>0}?.let{now+it*3_600_000L},uid,reason);_message.value="PERMISSION_GRANTED"}
    fun acceptDevicePairing(payload:String)=deviceReview{uid->container.devices.acceptPairingOffer(payload.trim(),uid);_deviceActionResult.value="PAIRING_DONE";_message.value="DEVICE_PAIRING_ACCEPTED"}
    fun rejectDevicePairing(payload:String)=deviceReview{uid->container.devices.rejectPairingOffer(payload.trim(),uid);_deviceActionResult.value="PAIRING_DONE";_message.value="DEVICE_PAIRING_REJECTED"}
    fun approveDeviceReplacement(requestId:String,note:String)=deviceReview{uid->require(note.isNotBlank()){"REASON_REQUIRED"};container.devices.approveReplacement(requestId,uid,note);_deviceActionResult.value="REPLACEMENT_DONE";_message.value="DEVICE_REPLACEMENT_APPROVED"}
    fun rejectDeviceReplacement(requestId:String,note:String)=deviceReview{uid->require(note.isNotBlank()){"REASON_REQUIRED"};container.devices.rejectReplacement(requestId,uid,note);_deviceActionResult.value="REPLACEMENT_DONE";_message.value="DEVICE_REPLACEMENT_REJECTED"}

    private suspend fun actor():String{
        val id=preferences.userId.first()?:error("LOCAL_SESSION_REQUIRED")
        require(dao.isUserActive(id)){"LOCAL_SESSION_INVALID"}
        return id
    }
    private fun launch(action:suspend(String)->Unit)=viewModelScope.launch{try{action(actor())}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Log.w("AdminOperationsViewModel","OPERATION_FAILED:${e.javaClass.simpleName}");_message.value=LocalAccessRules.safeErrorCode(e.message,"OPERATION_FAILED")}}
    private fun deviceReview(action:suspend(String)->Unit):Job{
        if(!deviceReviewInFlight.compareAndSet(false,true)){_message.value="DEVICE_ACTION_IN_PROGRESS";return viewModelScope.launch{}}
        _deviceReviewBusy.value=true
        return viewModelScope.launch{try{action(actor())}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Log.w("AdminOperationsViewModel","DEVICE_OPERATION_FAILED:${e.javaClass.simpleName}");_message.value=LocalAccessRules.safeErrorCode(e.message,"DEVICE_OPERATION_FAILED")}finally{_deviceReviewBusy.value=false;deviceReviewInFlight.set(false)}}
    }
    private fun guardedLaunch(guard:AtomicBoolean,action:suspend(String)->Unit):Job{
        if(!guard.compareAndSet(false,true)){_message.value="OPERATION_IN_PROGRESS";return viewModelScope.launch{}}
        return viewModelScope.launch{try{action(actor())}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Log.w("AdminOperationsViewModel","OPERATION_FAILED:${e.javaClass.simpleName}");_message.value=LocalAccessRules.safeErrorCode(e.message,"OPERATION_FAILED")}finally{guard.set(false)}}
    }

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
    fun archiveTeacher(id:String,reason:String)=guardedLaunch(academicDestructiveInFlight){repo.archiveTeacher(id,it,reason);_message.value="TEACHER_ARCHIVED"}
    fun addAttendancePolicy(name:String,full:Double,partial:Double,late:Int,early:Int,absence:Double,grace:Long,minimum:Long,confidence:Double)=launch{repo.addAttendancePolicy(name,full,partial,late,early,absence,grace,minimum,confidence,it);_message.value="ATTENDANCE_POLICY_CREATED"}
    fun addSubject(code:String,name:String,teacherId:String,levelId:String,semesterId:String,groupId:String,policyId:String)=launch{repo.addSubject(code,name,teacherId,levelId,semesterId,groupId,policyId,it);_message.value="SUBJECT_CREATED"}
    fun updateSubjectAttendancePolicy(subjectId:String,policyId:String)=launch{repo.updateSubjectAttendancePolicy(subjectId,policyId,it);_message.value="SUBJECT_ATTENDANCE_POLICY_UPDATED"}
    fun archiveSubject(id:String,reason:String)=guardedLaunch(academicDestructiveInFlight){repo.archiveSubject(id,it,reason);_message.value="SUBJECT_ARCHIVED"}
    fun saveReportSetting(teacherId:String,subjectId:String?,enabled:Boolean,frequencies:Set<String>,sendTime:String,weeklyDay:Int?,monthlyDay:Int?,timezone:String,channel:String,format:String,includeDetails:Boolean,requireApproval:Boolean,aiSummary:Boolean,sendIfNoLecture:Boolean)=launch{uid->
        require(container.authorization.hasPermission(uid,"MANAGE_REPORT_SETTINGS")){"MANAGE_REPORT_SETTINGS_PERMISSION_REQUIRED"}
        require(teacherId.isNotBlank() && frequencies.isNotEmpty()){"REPORT_SETTING_REQUIRED_FIELDS"}
        ReportLifecycleRules.validateSchedule(frequencies,sendTime,weeklyDay,monthlyDay,timezone,null)?.let{error(it)}
        require(!aiSummary){"AI_SUMMARY_NOT_IMPLEMENTED"}
        val existing=reportSettings.value.firstOrNull{it.teacherId==teacherId&&it.subjectId==subjectId}
        val now=System.currentTimeMillis();val row=TeacherReportSettingEntity(existing?.id?:java.util.UUID.randomUUID().toString(),teacherId,subjectId,enabled,frequencies.sorted().joinToString(","),sendTime,weeklyDay,monthlyDay,"END_OF_SEMESTER" in frequencies,null,timezone,channel,format,includeDetails,requireApproval,aiSummary,sendIfNoLecture,now,(existing?.version?:0)+1)
        container.reports.saveSetting(row,uid);_message.value="REPORT_SETTING_SAVED"
    }

    fun rollover(oldSemesterId:String,newName:String,newYearId:String,start:String,end:String,copySubjects:Boolean,copyTimetable:Boolean)=guardedLaunch(academicDestructiveInFlight){
        repo.closeSemesterAndStartNew(oldSemesterId,newName,newYearId,start,end,it,AcademicManagementRepository.RolloverOptions(copySubjects,copyTimetable));_message.value="SEMESTER_ROLLOVER_COMPLETE"
    }

    fun createManualDraft(groupId:String,weekStart:String)=launch{uid->val id=timetable.createDraft(groupId,weekStart,"MANUAL",null,uid);_selectedGroup.value=groupId;loadReview(id);_message.value="TIMETABLE_DRAFT_CREATED"}
    fun importDocument(groupId:String,weekStart:String,uri:Uri,mime:String?)=launch{uid->
        val normalizedMime=mime?.lowercase().orEmpty()
        val type=when{normalizedMime=="text/csv"||normalizedMime.startsWith("text/")->"DOCUMENT";normalizedMime=="application/pdf"->"PDF";normalizedMime.startsWith("image/")->"IMAGE";else->error("TIMETABLE_FILE_TYPE_UNSUPPORTED")}
        val id=timetable.createDraft(groupId,weekStart,type,uri.toString(),uid);_selectedGroup.value=groupId
        val text=withContext(Dispatchers.IO){if(type=="DOCUMENT")readTextLimited(uri,2*1024*1024) else tryVision(uri,normalizedMime)}
        if(text.isNullOrBlank()){timetable.markNeedsReview(id,uid,"SOURCE_SAVED_MANUAL_REVIEW_REQUIRED");_message.value="SOURCE_SAVED_MANUAL_REVIEW_REQUIRED"}
        else{val issues=timetable.saveManualCsv(id,text,uid);if(issues.isNotEmpty()){timetable.markNeedsReview(id,uid,issues.first().code);_message.value=issues.first().code}else _message.value="TIMETABLE_IMPORTED_FOR_REVIEW"}
        loadReview(id)
    }
    fun importCameraImage(groupId:String,weekStart:String,uri:Uri)=launch{uid->
        val id=timetable.createDraft(groupId,weekStart,"CAMERA",uri.toString(),uid);_selectedGroup.value=groupId
        val text=withContext(Dispatchers.IO){tryVision(uri,"image/jpeg")}
        if(text.isNullOrBlank()){timetable.markNeedsReview(id,uid,"IMAGE_REQUIRES_REVIEW_OR_VISION_EXTRACTION");_message.value="IMAGE_DRAFT_SAVED"}
        else{val issues=timetable.saveManualCsv(id,text,uid);if(issues.isNotEmpty()){timetable.markNeedsReview(id,uid,issues.first().code);_message.value=issues.first().code}else _message.value="TIMETABLE_IMPORTED_FOR_REVIEW"}
        loadReview(id)
    }
    fun saveRows(scheduleId:String,rows:List<TimetableRepository.DraftRow>)=launch{uid->val issues=timetable.replaceDraftRows(scheduleId,rows,uid);loadReview(scheduleId);_message.value=if(issues.isEmpty())"TIMETABLE_DRAFT_VALID" else "TIMETABLE_NEEDS_REVIEW"}
    fun approve(scheduleId:String,reason:String)=guardedLaunch(timetableApprovalInFlight){uid->timetable.approve(scheduleId,uid,reason);loadReview(scheduleId);_message.value="TIMETABLE_APPROVED"}
    fun loadReview(id:String){
        reviewLoadJob?.cancel()
        reviewLoadJob=viewModelScope.launch{
            try{_review.value=null;_review.value=timetable.review(id,actor())}
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){Log.w("AdminOperationsViewModel","TIMETABLE_LOAD_FAILED:${e.javaClass.simpleName}");_message.value=LocalAccessRules.safeErrorCode(e.message,"TIMETABLE_LOAD_FAILED")}
        }
    }

    fun runDataIntegrity()=guardedLaunch(integrityScanInFlight){uid->require(container.authorization.hasPermission(uid,"RUN_DATA_INTEGRITY")){"RUN_DATA_INTEGRITY_PERMISSION_REQUIRED"};_integrityResult.value=container.dataIntegrity.run(uid);_message.value=if(_integrityResult.value?.healthy==true)"DATA_INTEGRITY_HEALTHY" else "DATA_INTEGRITY_NEEDS_ATTENTION"}

    fun previewStudentCsv(uri:Uri)=guardedLaunch(studentTransferInFlight){uid->
        require(container.authorization.hasPermission(uid,"EDIT_STUDENTS")){"EDIT_STUDENTS_PERMISSION_REQUIRED"}
        val preview=withContext(Dispatchers.IO){val text=readText(uri)?:error("CSV_READ_FAILED");container.students.validateImport(StudentCsv.preview(text))};_studentImportTarget.value=null;_studentImportPreview.value=preview;_message.value="STUDENT_CSV_PREVIEW_READY"
    }
    fun setStudentImportTarget(levelId:String,batchId:String,sectionId:String,groupId:String){
        _studentImportTarget.value=if(listOf(levelId,batchId,sectionId,groupId).all{it.isNotBlank()})StudentImportTarget(levelId,batchId,sectionId,groupId) else null
    }
    fun confirmStudentCsvImport()=guardedLaunch(studentTransferInFlight){uid->
        require(container.authorization.hasPermission(uid,"EDIT_STUDENTS")){"EDIT_STUDENTS_PERMISSION_REQUIRED"}
        val preview=_studentImportPreview.value?:error("CSV_PREVIEW_REQUIRED");require(preview.invalid.isEmpty()){"CSV_HAS_INVALID_ROWS"}
        val target=_studentImportTarget.value?:error("STUDENT_ACADEMIC_SCOPE_REQUIRED")
        container.students.validateAcademicTarget(target,uid)
        val count=container.students.confirmImport(preview.valid,target,uid);_studentImportPreview.value=null;_studentImportTarget.value=null;_message.value="STUDENT_CSV_IMPORTED_$count"
    }
    fun exportStudentsCsv(uri:Uri)=guardedLaunch(studentTransferInFlight){uid->
        require(container.authorization.hasPermission(uid,"VIEW_STUDENTS")){"VIEW_STUDENTS_PERMISSION_REQUIRED"}
        withContext(Dispatchers.IO){val rows=container.students.observeScoped(uid).first().map{it.universityNumber to it.fullName};val csv=StudentCsv.export(rows);FileIoSafety.requireOpened(getApplication<Application>().contentResolver.openOutputStream(uri,"w"),"FILE_OUTPUT_STREAM_UNAVAILABLE").bufferedWriter(Charsets.UTF_8).use{it.write(csv)}};_message.value="STUDENT_CSV_EXPORTED"
    }
    fun clearStudentCsvPreview(){_studentImportPreview.value=null;_studentImportTarget.value=null}
    fun createShareableStudentCsv()=guardedLaunch(studentTransferInFlight){uid->
        require(container.authorization.hasPermission(uid,"VIEW_STUDENTS")){"VIEW_STUDENTS_PERMISSION_REQUIRED"}
        val app=getApplication<Application>()
        val target=withContext(Dispatchers.IO){
            val rows=container.students.observeScoped(uid).first().map{it.universityNumber to it.fullName}
            val dir=File(app.filesDir,"exports").apply{mkdirs()};LocalFileRetention.prune(dir,8,"Hammam-AttendAI-students-")
            File(dir,"Hammam-AttendAI-students-${System.currentTimeMillis()}.csv").also{it.writeText(StudentCsv.export(rows),Charsets.UTF_8)}
        }
        _shareableExport.value=ShareableExport(FileProvider.getUriForFile(app,"${app.packageName}.files",target),"text/csv","SHARE_STUDENT_CSV")
        _message.value="STUDENT_CSV_READY_TO_SHARE"
    }

    fun exportConfiguration(uri:Uri)=guardedLaunch(configurationOperationInFlight){uid->
        require(container.authorization.hasPermission(uid,"MANAGE_CONFIGURATION")||container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_CONFIGURATION_PERMISSION_REQUIRED"}
        val tmp=File(getApplication<Application>().cacheDir,"hammam-config-${System.currentTimeMillis()}.hconf")
        try{withContext(Dispatchers.IO){container.configurationBackup.export(tmp,uid);FileIoSafety.requireOpened(getApplication<Application>().contentResolver.openOutputStream(uri,"w"),"FILE_OUTPUT_STREAM_UNAVAILABLE").use{out->tmp.inputStream().use{it.copyTo(out)}}};_message.value="CONFIGURATION_EXPORTED"}finally{withContext(Dispatchers.IO){tmp.delete()}}
    }
    fun previewConfiguration(uri:Uri)=guardedLaunch(configurationOperationInFlight){uid->
        _configPreview.value=null;_selectedConfigUri.value=null
        require(container.authorization.hasPermission(uid,"MANAGE_CONFIGURATION")||container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_CONFIGURATION_PERMISSION_REQUIRED"}
        val tmp=withContext(Dispatchers.IO){copyUriToCache(uri,"config-preview",8L*1024*1024)};try{val preview=withContext(Dispatchers.IO){container.configurationBackup.preview(tmp,uid)};_configPreview.value=preview;if(preview.valid)_selectedConfigUri.value=uri;_message.value=if(preview.valid)"CONFIGURATION_VALIDATED" else "CONFIGURATION_INVALID"}finally{withContext(Dispatchers.IO){tmp.delete()}}
    }
    fun importSelectedConfiguration()=guardedLaunch(configurationOperationInFlight){uid->
        require(container.authorization.hasPermission(uid,"MANAGE_CONFIGURATION")||container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_CONFIGURATION_PERMISSION_REQUIRED"}
        val uri=_selectedConfigUri.value?:error("CONFIGURATION_NOT_SELECTED");val tmp=withContext(Dispatchers.IO){copyUriToCache(uri,"config-import",8L*1024*1024)};try{withContext(Dispatchers.IO){container.configurationBackup.importConfirmed(tmp,uid)};_message.value="CONFIGURATION_IMPORTED"}finally{withContext(Dispatchers.IO){tmp.delete()}}
    }
    fun createShareableConfiguration()=guardedLaunch(configurationOperationInFlight){uid->
        require(container.authorization.hasPermission(uid,"MANAGE_CONFIGURATION")||container.authorization.hasPermission(uid,"MANAGE_SETTINGS")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_CONFIGURATION_PERMISSION_REQUIRED"}
        val app=getApplication<Application>()
        val target=withContext(Dispatchers.IO){val dir=File(app.filesDir,"exports").apply{mkdirs()};LocalFileRetention.prune(dir,8,"Hammam-AttendAI-config-");File(dir,"Hammam-AttendAI-config-${System.currentTimeMillis()}.hconf").also{container.configurationBackup.export(it,uid)}}
        _shareableExport.value=ShareableExport(FileProvider.getUriForFile(app,"${app.packageName}.files",target),"text/plain","SHARE_CONFIGURATION")
        _message.value="CONFIGURATION_READY_TO_SHARE"
    }

    fun exportEncryptedBackup(uri:Uri,passphrase:String)=guardedLaunch(backupOperationInFlight){uid->
        require(container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_BACKUP_PERMISSION_REQUIRED"}
        val app=getApplication<Application>();val tmp=File(app.cacheDir,"hammam-export-${System.currentTimeMillis()}.hammamattend")
        val secret=passphrase.toCharArray();try{withContext(Dispatchers.IO){container.backupManager.createBackup(app.getDatabasePath("hammam_attendai.db"),tmp,secret);FileIoSafety.requireOpened(app.contentResolver.openOutputStream(uri,"w"),"FILE_OUTPUT_STREAM_UNAVAILABLE").use{out->tmp.inputStream().use{it.copyTo(out)}}};_backupStatus.value="BACKUP_CREATED"}finally{secret.fill('\u0000');withContext(Dispatchers.IO){tmp.delete()}}
    }
    fun validateBackup(uri:Uri,passphrase:String)=guardedLaunch(backupOperationInFlight){uid->
        _selectedBackupUri.value=null;_backupStatus.value=null
        require(container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_BACKUP_PERMISSION_REQUIRED"}
        val tmp=withContext(Dispatchers.IO){copyUriToCache(uri,"backup-validate",512L*1024*1024)};val secret=passphrase.toCharArray()
        try{val result=withContext(Dispatchers.IO){container.backupManager.validate(tmp,secret)};require(result.valid){result.error?:"BACKUP_INVALID"};_selectedBackupUri.value=uri;_backupStatus.value="BACKUP_VALIDATED_DB_${result.databaseVersion}"}finally{secret.fill('\u0000');withContext(Dispatchers.IO){tmp.delete()}}
    }
    fun restoreSelectedBackup(passphrase:String)=guardedLaunch(backupOperationInFlight){uid->
        require(container.authorization.hasPermission(uid,"RESTORE_BACKUP")||container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"RESTORE_BACKUP_PERMISSION_REQUIRED"}
        val uri=_selectedBackupUri.value?:error("BACKUP_NOT_SELECTED");val tmp=withContext(Dispatchers.IO){copyUriToCache(uri,"backup-restore",512L*1024*1024)};val secret=passphrase.toCharArray()
        try{val result=withContext(Dispatchers.IO){container.backupManager.stageRestore(tmp,secret)};require(result.valid){result.error?:"BACKUP_RESTORE_VALIDATION_FAILED"};_backupStatus.value="BACKUP_RESTORE_STAGED_RESTART_REQUIRED";_selectedBackupUri.value=null}finally{secret.fill('\u0000');withContext(Dispatchers.IO){tmp.delete()}}
    }
    fun createShareableBackup(passphrase:String)=guardedLaunch(backupOperationInFlight){uid->
        require(container.authorization.hasPermission(uid,"MANAGE_BACKUP")){"MANAGE_BACKUP_PERMISSION_REQUIRED"}
        val app=getApplication<Application>();val secret=passphrase.toCharArray()
        val target=try{withContext(Dispatchers.IO){val dir=File(app.filesDir,"backups").apply{mkdirs()};LocalFileRetention.prune(dir,4,"Hammam-AttendAI-");File(dir,"Hammam-AttendAI-${System.currentTimeMillis()}.hammamattend").also{container.backupManager.createBackup(app.getDatabasePath("hammam_attendai.db"),it,secret)}}}finally{secret.fill('\u0000')}
        _shareBackupUri.value=FileProvider.getUriForFile(app,"${app.packageName}.files",target);_backupStatus.value="BACKUP_READY_TO_SHARE"
    }
    fun consumeShareBackup(){_shareBackupUri.value=null}
    fun consumeShareableExport(){_shareableExport.value=null}
    private fun copyUriToCache(uri:Uri,prefix:String,maxBytes:Long=64L*1024*1024):File{
        val app=getApplication<Application>();val f=File(app.cacheDir,"$prefix-${System.currentTimeMillis()}.bin")
        try{FileIoSafety.requireOpened(app.contentResolver.openInputStream(uri),"FILE_URI_UNREADABLE").use{input->f.outputStream().use{out->val buf=ByteArray(8192);var total=0L;while(true){val n=input.read(buf);if(n<0)break;total+=n;if(total>maxBytes)error("FILE_TOO_LARGE");out.write(buf,0,n)}}};return f}catch(e:Exception){f.delete();throw e}
    }

    private suspend fun tryVision(uri:Uri,mime:String):String?{
        val bytes=readBytesLimited(uri,4*1024*1024)
        val provider=container.aiProviderManager.defaultProvider();val health=container.aiProviderManager.health(provider);if(!health.configured||health.selectedModel.isNullOrBlank())return null
        val prompt="Extract the weekly university timetable. Return CSV only, no markdown. Columns: day,subjectCode,teacher,start,end,room,lectureType. day must be 1..7 and time HH:mm. Do not invent missing values."
        return container.aiProviderManager.extractTimetableVision(provider,mime,Base64.encodeToString(bytes,Base64.NO_WRAP),prompt)?.trim()?.removePrefix("```csv")?.removePrefix("```")?.removeSuffix("```")?.trim()
    }
    private fun readBytesLimited(uri:Uri,max:Int):ByteArray=FileIoSafety.requireOpened(getApplication<Application>().contentResolver.openInputStream(uri),"FILE_URI_UNREADABLE").use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);var total=0;while(true){val n=input.read(buf);if(n<0)break;total+=n;if(total>max)error("TIMETABLE_FILE_TOO_LARGE");out.write(buf,0,n)};out.toByteArray()}
    private fun readTextLimited(uri:Uri,max:Int):String{val bytes=readBytesLimited(uri,max);return bytes.toString(Charsets.UTF_8)}
}
