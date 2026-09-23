package com.hammam.attendai.domain.appeals

import com.hammam.attendai.data.repository.*
import com.hammam.attendai.domain.model.FinalAttendanceStatus

class GetAttendanceAppealContextUseCase(private val repository:AttendanceAppealRepository){
    suspend operator fun invoke(recordId:String,actorId:String?)=repository.loadContext(recordId,actorId)
}
class SubmitAttendanceAppealUseCase(private val repository:AttendanceAppealRepository){
    suspend operator fun invoke(recordId:String,reasonType:String,description:String,attachmentUri:String?,actorId:String?)=
        repository.submit(recordId,reasonType,description,attachmentUri,actorId)
}
class ReviewAttendanceAppealUseCase(private val repository:AttendanceAppealRepository){
    suspend operator fun invoke(appealId:String,accept:Boolean,note:String,reviewerId:String,newStatus:FinalAttendanceStatus?,newPercentage:Double?,canEditFrozen:Boolean)=
        repository.review(appealId,accept,note,reviewerId,newStatus,newPercentage,canEditFrozen)
}
