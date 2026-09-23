package com.hammam.attendai.ai

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.repository.ArabicNormalizer
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import com.hammam.attendai.sync.BackendClient
import com.hammam.attendai.security.AuthorizationRepository
import java.time.*

sealed interface SafeIntent {
    data class StudentAttendance(val query:String):SafeIntent
    data object AbsentToday:SafeIntent
    data object LateToday:SafeIntent
    data class SubjectStatistics(val subject:String):SafeIntent
    data object NeedsReview:SafeIntent
    data object Unknown:SafeIntent
}

class OfflineIntentParser {
    fun parse(text:String):SafeIntent{
        val raw=text.trim()
        val n=ArabicNormalizer.normalize(raw)
        val lower=raw.lowercase()
        return when {
            ("يحتاج" in n && "مراجعه" in n) || "needs review" in lower || "need review" in lower -> SafeIntent.NeedsReview
            (("غائب" in n || "غياب" in n) && "اليوم" in n) || (("absent" in lower || "absence" in lower) && "today" in lower) -> SafeIntent.AbsentToday
            ("متاخر" in n && "اليوم" in n) || ("late" in lower && "today" in lower) -> SafeIntent.LateToday
            "سجل" in n || "نسبه حضور" in n || "attendance record" in lower || "attendance rate" in lower || Regex("\\d{5,}").containsMatchIn(n) -> SafeIntent.StudentAttendance(raw)
            "احصائيات" in n || "لخص حضور" in n || "attendance statistics" in lower || "attendance summary" in lower -> SafeIntent.SubjectStatistics(raw)
            else -> SafeIntent.Unknown
        }
    }
}

interface AiProvider { suspend fun summarizeGrounded(prompt:String, facts:Map<String,Any?>):String? }
class DisabledCloudAiProvider:AiProvider { override suspend fun summarizeGrounded(prompt:String,facts:Map<String,Any?>):String?=null }

class BackendAiProvider(private val backend:BackendClient):AiProvider{
    override suspend fun summarizeGrounded(prompt:String,facts:Map<String,Any?>):String?{
        val payload="{\"prompt\":\"${esc(prompt)}\",\"facts\":${jsonValue(facts)}}"
        val result=backend.post("/api/v1/ai/grounded-summary",payload,"ai:${payload.hashCode()}")
        if(!result.ok)return null
        return extractJsonString(result.responseBody.orEmpty(),"provider_response")
    }
    private fun jsonValue(v:Any?):String=when(v){null->"null";is Number,is Boolean->v.toString();is Map<*,*>->v.entries.joinToString(prefix="{",postfix="}"){"\"${esc(it.key.toString())}\":${jsonValue(it.value)}"};is Iterable<*>->v.joinToString(prefix="[",postfix="]"){jsonValue(it)};else->"\"${esc(v.toString())}\""}
    private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")
    private fun extractJsonString(json:String,key:String):String?{
        val m=Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(json)?:return null
        return m.groupValues[1].replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\")
    }
}

interface AttendanceQueryTools {
    suspend fun searchStudents(query:String):List<Map<String,Any?>>
    suspend fun getStudentAttendance(studentId:String):Map<String,Any?>
    suspend fun getLectureAttendance(lectureId:String):Map<String,Any?>
    suspend fun getSubjectStatistics(subjectId:String,start:Long,end:Long):Map<String,Any?>
    suspend fun getAbsenceSummary(start:Long,end:Long):Map<String,Any?>
    suspend fun getLateStudents(start:Long,end:Long):List<Map<String,Any?>>
    suspend fun getPartialAttendance(start:Long,end:Long):List<Map<String,Any?>>
    suspend fun getNeedsReview(start:Long,end:Long):List<Map<String,Any?>>
    suspend fun getReportData(subjectId:String?,start:Long,end:Long):Map<String,Any?>
}

class RoomAttendanceQueryTools(
    private val db:HammamDatabase,
    private val authorization:AuthorizationRepository?=null,
    private val userIdProvider:suspend ()->String?={null},
):AttendanceQueryTools{
    private val dao=db.coreDao()
    private suspend fun user()=userIdProvider()
    private suspend fun canStudent(studentId:String):Boolean{val a=authorization?:return true;val u=user()?:return false;return a.canAccessStudent(u,studentId)}
    private suspend fun canGroup(groupId:String):Boolean{val a=authorization?:return true;val u=user()?:return false;return a.canAccessGroup(u,groupId)}
    private suspend fun <T> scopedRows(rows:List<T>,studentId:(T)->String):List<T>{if(authorization==null)return rows;val allowed=mutableMapOf<String,Boolean>();return rows.filter{r->val id=studentId(r);allowed.getOrPut(id){false}.let{cached->if(cached)true else canStudent(id).also{allowed[id]=it}}}}

    override suspend fun searchStudents(query:String)=dao.searchStudentsOnce(ArabicNormalizer.normalize(query)).filter{canStudent(it.id)}.map{mapOf("id" to it.id,"universityNumber" to it.universityNumber,"fullName" to it.fullName,"status" to it.status.name)}
    override suspend fun getStudentAttendance(studentId:String):Map<String,Any?>{
        if(!canStudent(studentId))return mapOf("error" to "OUTSIDE_AUTHORIZED_SCOPE")
        val records=dao.getStudentRecordsOnce(studentId);val student=dao.getStudentById(studentId)
        return mapOf("studentId" to studentId,"studentName" to student?.fullName,"universityNumber" to student?.universityNumber,"total" to records.size,"present" to records.count{it.finalStatus==FinalAttendanceStatus.PRESENT},"absent" to records.count{it.finalStatus==FinalAttendanceStatus.ABSENT},"late" to records.count{it.finalStatus==FinalAttendanceStatus.LATE},"partial" to records.count{it.finalStatus==FinalAttendanceStatus.PARTIAL},"attendanceRate" to if(records.isEmpty())0.0 else records.map{it.attendancePercentage}.average())
    }
    override suspend fun getLectureAttendance(lectureId:String):Map<String,Any?> {val lecture=dao.getLectureById(lectureId)?:return mapOf("error" to "LECTURE_NOT_FOUND");if(!canGroup(lecture.groupId))return mapOf("error" to "OUTSIDE_AUTHORIZED_SCOPE");val r=dao.getLectureRecords(lectureId);return mapOf("lectureId" to lectureId,"records" to r.size,"present" to r.count{it.finalStatus==FinalAttendanceStatus.PRESENT},"absent" to r.count{it.finalStatus==FinalAttendanceStatus.ABSENT},"needsReview" to r.count{it.finalStatus==FinalAttendanceStatus.MANUAL_REVIEW}) }
    override suspend fun getSubjectStatistics(subjectId:String,start:Long,end:Long):Map<String,Any?> {val subject=dao.getSubjectById(subjectId)?:return mapOf("error" to "SUBJECT_NOT_FOUND");if(!canGroup(subject.groupId))return mapOf("error" to "OUTSIDE_AUTHORIZED_SCOPE");return reportStats(subjectId,start,end)}
    override suspend fun getAbsenceSummary(start:Long,end:Long):Map<String,Any?> { val r=scopedRows(dao.getRecordsByStatusRange(FinalAttendanceStatus.ABSENT,start,end)){it.studentId};return mapOf("count" to r.size,"studentIds" to r.map{it.studentId}.distinct()) }
    override suspend fun getLateStudents(start:Long,end:Long)=scopedRows(dao.getRecordsByStatusRange(FinalAttendanceStatus.LATE,start,end)){it.studentId}.map{mapOf("studentId" to it.studentId,"lectureId" to it.lectureId,"lateMinutes" to it.lateMinutes)}
    override suspend fun getPartialAttendance(start:Long,end:Long)=scopedRows(dao.getRecordsByStatusRange(FinalAttendanceStatus.PARTIAL,start,end)){it.studentId}.map{mapOf("studentId" to it.studentId,"lectureId" to it.lectureId,"attendancePercentage" to it.attendancePercentage)}
    override suspend fun getNeedsReview(start:Long,end:Long)=scopedRows(dao.getNeedsReviewRange(start,end)){it.studentId}.map{mapOf("studentId" to it.studentId,"lectureId" to it.lectureId,"confidence" to it.confidenceScore)}
    override suspend fun getReportData(subjectId:String?,start:Long,end:Long):Map<String,Any?> {
        if(subjectId!=null){val subject=dao.getSubjectById(subjectId)?:return mapOf("error" to "SUBJECT_NOT_FOUND");if(!canGroup(subject.groupId))return mapOf("error" to "OUTSIDE_AUTHORIZED_SCOPE")}
        else if(authorization!=null){val u=user()?:return mapOf("error" to "USER_NOT_INITIALIZED");if(!authorization.isGlobal(u))return mapOf("error" to "SUBJECT_REQUIRED_FOR_SCOPED_USER")}
        return reportStats(subjectId,start,end)
    }
    private suspend fun reportStats(subjectId:String?,start:Long,end:Long):Map<String,Any?> { val r=dao.getReportRecords(subjectId,start,end);return mapOf("subjectId" to subjectId,"periodStart" to start,"periodEnd" to end,"records" to r.size,"attendanceRate" to if(r.isEmpty())0.0 else r.map{it.attendancePercentage}.average(),"absent" to r.count{it.finalStatus==FinalAttendanceStatus.ABSENT},"late" to r.count{it.finalStatus==FinalAttendanceStatus.LATE},"partial" to r.count{it.finalStatus==FinalAttendanceStatus.PARTIAL},"leftEarly" to r.count{it.finalStatus==FinalAttendanceStatus.LEFT_EARLY}) }
}

enum class AssistantReplyCode{
    STUDENT_NOT_FOUND,
    STUDENT_AMBIGUOUS,
    STUDENT_ATTENDANCE,
    ABSENT_TODAY,
    LATE_TODAY,
    NEEDS_REVIEW,
    SUBJECT_SELECTION_REQUIRED,
    LOCAL_CAPABILITIES,
}

data class AssistantAnswer(
    val replyCode:AssistantReplyCode,
    val values:Map<String,String> = emptyMap(),
    val periodStart:Long?=null,
    val periodEnd:Long?=null,
    val groundedFacts:Map<String,Any?> = emptyMap(),
)

class OfflineAssistantEngine(private val parser:OfflineIntentParser,private val tools:AttendanceQueryTools){
    suspend fun answer(query:String,nowMillis:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):AssistantAnswer{
        val day=Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val start=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return when(val intent=parser.parse(query)){
            is SafeIntent.StudentAttendance->{
                val search=Regex("\\d{5,}").find(query)?.value ?: query.replace(Regex("(?i)(سجل|نسبة|نسبه|حضور|الطالب|اعرض|attendance|record|rate|student|show)")," ").trim()
                val matches=tools.searchStudents(search)
                if(matches.isEmpty())AssistantAnswer(AssistantReplyCode.STUDENT_NOT_FOUND)
                else if(matches.size>1)AssistantAnswer(AssistantReplyCode.STUDENT_AMBIGUOUS,groundedFacts=mapOf("matches" to matches.take(10)))
                else {
                    val facts=tools.getStudentAttendance(matches.first()["id"].toString())
                    val rate=((facts["attendanceRate"] as? Number)?.toDouble()?:0.0)*100.0
                    AssistantAnswer(
                        AssistantReplyCode.STUDENT_ATTENDANCE,
                        values=mapOf(
                            "studentName" to facts["studentName"].toString(),
                            "present" to facts["present"].toString(),
                            "absent" to facts["absent"].toString(),
                            "late" to facts["late"].toString(),
                            "partial" to facts["partial"].toString(),
                            "attendanceRate" to String.format(java.util.Locale.ROOT,"%.1f",rate),
                        ),
                        groundedFacts=facts,
                    )
                }
            }
            SafeIntent.AbsentToday->{
                val facts=tools.getAbsenceSummary(start,end)
                AssistantAnswer(AssistantReplyCode.ABSENT_TODAY,mapOf("count" to facts["count"].toString()),start,end,facts)
            }
            SafeIntent.LateToday->{
                val rows=tools.getLateStudents(start,end)
                AssistantAnswer(AssistantReplyCode.LATE_TODAY,mapOf("count" to rows.size.toString()),start,end,mapOf("rows" to rows))
            }
            SafeIntent.NeedsReview->{
                val rows=tools.getNeedsReview(start,end)
                AssistantAnswer(AssistantReplyCode.NEEDS_REVIEW,mapOf("count" to rows.size.toString()),start,end,mapOf("rows" to rows))
            }
            is SafeIntent.SubjectStatistics->{
                val monthStart=day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val monthEnd=day.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
                AssistantAnswer(AssistantReplyCode.SUBJECT_SELECTION_REQUIRED,periodStart=monthStart,periodEnd=monthEnd)
            }
            SafeIntent.Unknown->AssistantAnswer(AssistantReplyCode.LOCAL_CAPABILITIES)
        }
    }
}
