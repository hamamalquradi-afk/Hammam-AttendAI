package com.hammam.attendai.data.local

import androidx.room.TypeConverter
import com.hammam.attendai.domain.model.*

class Converters {
    @TypeConverter fun fromSemesterStatus(v: SemesterStatus) = v.name
    @TypeConverter fun toSemesterStatus(v: String) = SemesterStatus.valueOf(v)
    @TypeConverter fun fromStudentStatus(v: StudentStatus) = v.name
    @TypeConverter fun toStudentStatus(v: String) = StudentStatus.valueOf(v)
    @TypeConverter fun fromDeviceStatus(v: DeviceStatus) = v.name
    @TypeConverter fun toDeviceStatus(v: String) = DeviceStatus.valueOf(v)
    @TypeConverter fun fromLectureStatus(v: LectureStatus) = v.name
    @TypeConverter fun toLectureStatus(v: String) = LectureStatus.valueOf(v)
    @TypeConverter fun fromFinal(v: FinalAttendanceStatus) = v.name
    @TypeConverter fun toFinal(v: String) = FinalAttendanceStatus.valueOf(v)
    @TypeConverter fun fromApproval(v: ApprovalStatus) = v.name
    @TypeConverter fun toApproval(v: String) = ApprovalStatus.valueOf(v)
    @TypeConverter fun fromEventType(v: PresenceEventType) = v.name
    @TypeConverter fun toEventType(v: String) = PresenceEventType.valueOf(v)
    @TypeConverter fun fromSource(v: PresenceSource) = v.name
    @TypeConverter fun toSource(v: String) = PresenceSource.valueOf(v)
    @TypeConverter fun fromQueue(v: QueueStatus) = v.name
    @TypeConverter fun toQueue(v: String) = QueueStatus.valueOf(v)
    @TypeConverter fun fromReport(v: ReportJobStatus) = v.name
    @TypeConverter fun toReport(v: String) = ReportJobStatus.valueOf(v)
    @TypeConverter fun fromAppeal(v: AppealStatus) = v.name
    @TypeConverter fun toAppeal(v: String) = AppealStatus.valueOf(v)
    @TypeConverter fun fromAppealSync(v: AppealSyncStatus) = v.name
    @TypeConverter fun toAppealSync(v: String) = AppealSyncStatus.valueOf(v)
}
