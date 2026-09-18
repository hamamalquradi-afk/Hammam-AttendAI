package com.hammam.attendai.data.local.dao

import com.hammam.attendai.domain.model.AppealStatus
import com.hammam.attendai.domain.model.FinalAttendanceStatus

data class AppealReviewRow(
    val appealId:String,
    val studentId:String,
    val studentName:String,
    val universityNumber:String?,
    val subjectName:String,
    val teacherName:String?,
    val lectureId:String,
    val lectureDate:Long,
    val currentAttendanceStatus:FinalAttendanceStatus,
    val currentAttendancePercentage:Double,
    val reasonType:String,
    val description:String,
    val attachmentLocalUri:String?,
    val status:AppealStatus,
    val submittedAt:Long,
    val decisionNote:String?,
)

data class RolePermissionExportRow(val roleName:String,val permissionCode:String)


data class UserAccessRow(val id:String,val displayName:String,val isActive:Boolean,val roleName:String?)


data class ReportAttendanceRow(
    val recordId:String,
    val lectureId:String,
    val studentId:String,
    val studentName:String,
    val universityNumber:String?,
    val finalStatus:FinalAttendanceStatus,
    val attendancePercentage:Double,
    val verifiedPresenceSeconds:Long,
    val firstSeenAt:Long?,
    val lastSeenAt:Long?,
)
