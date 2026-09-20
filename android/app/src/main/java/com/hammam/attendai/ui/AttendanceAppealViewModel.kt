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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class AppealUiState(
    val context:AttendanceAppealContext?=null,
    val loading:Boolean=false,
    val message:String?=null,
    val savedAppealId:String?=null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AttendanceAppealViewModel(app:Application,private val savedStateHandle:SavedStateHandle):AndroidViewModel(app){
    private val repository=(app as HammamAttendAiApplication).container.appeals
    private val getContext=GetAttendanceAppealContextUseCase(repository)
    private val submitUseCase=SubmitAttendanceAppealUseCase(repository)
    private val reviewUseCase=ReviewAttendanceAppealUseCase(repository)
    private val _state=MutableStateFlow(AppealUiState())
    val state:StateFlow<AppealUiState> = _state.asStateFlow()
    private val _filter=MutableStateFlow<AppealStatus?>(savedStateHandle.get<String>("appealFilter")?.let{runCatching{AppealStatus.valueOf(it)}.getOrNull()}?:AppealStatus.PENDING)
    val filter:StateFlow<AppealStatus?> = _filter.asStateFlow()
    val appeals:StateFlow<List<AttendanceAppealEntity>> = _filter.flatMapLatest{repository.observeByStatus(it)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    private val container=(app as HammamAttendAiApplication).container
    private val reviewInFlight=ConcurrentHashMap.newKeySet<String>()
    val reviewRows:StateFlow<List<AppealReviewRow>> = combine(container.preferences.userId,_filter){user,status->user to status}.flatMapLatest{(user,status)->
        if(user==null)flowOf(emptyList()) else repository.observeReviewRows(user,status)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())

    fun setFilter(status:AppealStatus?){savedStateHandle["appealFilter"]=status?.name;_filter.value=status}
    init { savedStateHandle.get<String>("appealRecordId")?.let{loadRecord(it)} }
    fun openRecord(recordId:String){savedStateHandle["appealRecordId"]=recordId;loadRecord(recordId)}
    private fun loadRecord(recordId:String){
        viewModelScope.launch{
            _state.value=AppealUiState(loading=true)
            val context=getContext(recordId)
            _state.value=if(context==null)AppealUiState(message="ATTENDANCE_CONTEXT_NOT_FOUND") else AppealUiState(context=context)
        }
    }
    fun clearForm(){savedStateHandle.remove<String>("appealRecordId");_state.value=AppealUiState()}
    fun submit(reasonType:String,description:String,attachmentUri:String?){
        val context=_state.value.context?:return
        viewModelScope.launch{
            _state.value=_state.value.copy(loading=true,message=null)
            val actorId=container.preferences.userId.first()
            when(val r=submitUseCase(context.attendanceRecord.id,reasonType,description,attachmentUri,actorId)){
                is AppealOperationResult.Success->_state.value=_state.value.copy(loading=false,message="APPEAL_SAVED_OFFLINE",savedAppealId=r.appealId)
                is AppealOperationResult.Failure->_state.value=_state.value.copy(loading=false,message=r.code)
            }
        }
    }
    fun review(appealId:String,accept:Boolean,note:String,newStatus:FinalAttendanceStatus?,newPercentage:Double?){
        if(!reviewInFlight.add(appealId))return
        viewModelScope.launch{try{
            val reviewerId=container.preferences.userId.first()
            if(reviewerId==null || !container.authorization.hasPermission(reviewerId,"REVIEW_APPEALS")){
                _state.value=_state.value.copy(message="REVIEW_PERMISSION_REQUIRED");return@launch
            }
            val canEditFrozen=container.authorization.hasPermission(reviewerId,"EDIT_FROZEN_ATTENDANCE")
            val r=reviewUseCase(appealId,accept,note,reviewerId,newStatus,newPercentage,canEditFrozen)
            if(r is AppealOperationResult.Success) AppealLocalNotifier(getApplication()).notifyReviewed(appealId,accept)
            _state.value=_state.value.copy(message=when(r){is AppealOperationResult.Success->"APPEAL_REVIEW_SAVED";is AppealOperationResult.Failure->r.code})
        }finally{reviewInFlight.remove(appealId)}}
    }
}
