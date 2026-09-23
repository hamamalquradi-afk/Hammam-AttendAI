package com.hammam.attendai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.data.local.entity.AttendanceAppealEntity
import com.hammam.attendai.data.local.dao.AppealReviewRow
import com.hammam.attendai.data.repository.AppealOperationResult
import com.hammam.attendai.data.repository.AttendanceAppealContext
import com.hammam.attendai.domain.appeals.*
import com.hammam.attendai.domain.model.AppealStatus
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import com.hammam.attendai.appeals.AppealLocalNotifier
import com.hammam.attendai.security.LocalAccessRules
import com.hammam.attendai.domain.model.StudentStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

data class AppealUiState(
    val context:AttendanceAppealContext?=null,
    val loading:Boolean=false,
    val message:String?=null,
    val savedAppealId:String?=null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AttendanceAppealViewModel(app:Application,private val savedStateHandle:SavedStateHandle):AndroidViewModel(app){
    private val container=(app as HammamAttendAiApplication).container
    private val dao=container.database.coreDao()
    private val repository=container.appeals
    private val getContext=GetAttendanceAppealContextUseCase(repository)
    private val submitUseCase=SubmitAttendanceAppealUseCase(repository)
    private val reviewUseCase=ReviewAttendanceAppealUseCase(repository)
    private val _state=MutableStateFlow(AppealUiState())
    val state:StateFlow<AppealUiState> = _state.asStateFlow()
    private val _filter=MutableStateFlow<AppealStatus?>(savedStateHandle.get<String>("appealFilter")?.let{runCatching{AppealStatus.valueOf(it)}.getOrNull()}?:AppealStatus.PENDING)
    val filter:StateFlow<AppealStatus?> = _filter.asStateFlow()
    private val reviewInFlight=ConcurrentHashMap.newKeySet<String>()
    private var contextLoadJob:Job?=null

    private val sessionState=container.preferences.userId.flatMapLatest{storedId->
        if(storedId==null)flowOf(LocalAccessRules.resolve(null,false,false,emptyList()))
        else combine(dao.observeUserById(storedId),dao.observeRoleNames(storedId)){user,roles->LocalAccessRules.resolve(storedId,user!=null,user?.isActive==true,roles)}
    }.stateIn(viewModelScope,SharingStarted.Eagerly,LocalAccessRules.checking())
    private val currentActor=sessionState.map{it.activeUserId}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    private val currentStudent=currentActor.flatMapLatest{actor->flow{
        val student=actor?.let{dao.getStudentForUser(it)}?.takeIf{it.status==StudentStatus.ACTIVE}
        emit(student)
    }}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)

    val appeals:StateFlow<List<AttendanceAppealEntity>> = currentStudent.flatMapLatest{student->
        if(student==null)flowOf(emptyList()) else repository.observeForStudent(student.id)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())

    private val reviewAccess=currentActor.flatMapLatest{user->
        if(user==null)flowOf(null to emptySet<String>()) else dao.observePermissionCodes(user).map{user to it.toSet()}
    }
    val reviewRows:StateFlow<List<AppealReviewRow>> = combine(reviewAccess,_filter){access,status->access to status}.flatMapLatest{(access,status)->
        val (user,permissions)=access
        if(user==null || "REVIEW_APPEALS" !in permissions)flowOf(emptyList()) else repository.observeReviewRows(user,status)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())

    fun setFilter(status:AppealStatus?){savedStateHandle["appealFilter"]=status?.name;_filter.value=status}

    init {
        viewModelScope.launch{
            var previous:String?=null
            currentActor.collect{actor->
                if(previous!=null && previous!=actor){
                    contextLoadJob?.cancel();savedStateHandle.remove<String>("appealRecordId");_state.value=AppealUiState();reviewInFlight.clear()
                } else if(previous==null && actor!=null){
                    savedStateHandle.get<String>("appealRecordId")?.let{loadRecord(it)}
                }
                previous=actor
            }
        }
    }

    fun openRecord(recordId:String){savedStateHandle["appealRecordId"]=recordId;loadRecord(recordId)}
    private fun loadRecord(recordId:String){
        contextLoadJob?.cancel()
        contextLoadJob=viewModelScope.launch{
            val actor=currentActor.value
            if(actor==null){_state.value=AppealUiState(message="LOCAL_SESSION_REQUIRED");return@launch}
            _state.value=AppealUiState(loading=true)
            val context=getContext(recordId,actor)
            if(currentActor.value!=actor || savedStateHandle.get<String>("appealRecordId")!=recordId)return@launch
            _state.value=if(context==null)AppealUiState(message="ATTENDANCE_CONTEXT_NOT_FOUND") else AppealUiState(context=context)
        }
    }
    fun clearForm(){contextLoadJob?.cancel();savedStateHandle.remove<String>("appealRecordId");_state.value=AppealUiState()}
    fun submit(reasonType:String,description:String,attachmentUri:String?){
        val context=_state.value.context?:return
        viewModelScope.launch{
            _state.value=_state.value.copy(loading=true,message=null)
            val actorId=currentActor.value
            val r=if(actorId==null)AppealOperationResult.Failure("LOCAL_SESSION_REQUIRED") else submitUseCase(context.attendanceRecord.id,reasonType,description,attachmentUri,actorId)
            _state.value=when(r){
                is AppealOperationResult.Success->_state.value.copy(loading=false,message="APPEAL_SAVED_OFFLINE",savedAppealId=r.appealId)
                is AppealOperationResult.Failure->_state.value.copy(loading=false,message=r.code)
            }
        }
    }
    fun review(appealId:String,accept:Boolean,note:String,newStatus:FinalAttendanceStatus?,newPercentage:Double?){
        if(!reviewInFlight.add(appealId))return
        viewModelScope.launch{try{
            val reviewerId=currentActor.value
            if(reviewerId==null || !container.authorization.hasPermission(reviewerId,"REVIEW_APPEALS")){
                _state.value=_state.value.copy(message="REVIEW_PERMISSION_REQUIRED");return@launch
            }
            val r=reviewUseCase(appealId,accept,note,reviewerId,newStatus,newPercentage,false)
            if(r is AppealOperationResult.Success) AppealLocalNotifier(getApplication()).notifyReviewed(appealId,accept)
            _state.value=_state.value.copy(message=when(r){is AppealOperationResult.Success->"APPEAL_REVIEW_SAVED";is AppealOperationResult.Failure->r.code})
        }finally{reviewInFlight.remove(appealId)}}
    }
}
