package com.hammam.attendai.importexport

object TimetableCsvFormat {
    data class RawRow(
        val line:Int,val dayOfWeek:Int,val subjectRef:String,val teacherRef:String,
        val startTime:String,val endTime:String,val room:String?,val lectureType:String,val scheduleKind:String,
    )
    data class Issue(val line:Int,val code:String)
    data class Result(val rows:List<RawRow>,val issues:List<Issue>)

    private val header=listOf("day","subjectcode","teacher","start","end","room","lecturetype","schedulekind")

    fun parse(text:String):Result{
        val parsed=CsvCodec.parse(text)
        parsed.error?.let{return Result(emptyList(),listOf(Issue(1,"TIMETABLE_CSV_MALFORMED")))}
        val meaningful=parsed.rows.map{it.map(String::trim)}.filterNot{row->row.all(String::isBlank)||row.firstOrNull()?.startsWith("#")==true}
        if(meaningful.isEmpty())return Result(emptyList(),listOf(Issue(1,"EMPTY_TIMETABLE")))
        val actualHeader=meaningful.first().map(String::lowercase)
        if(actualHeader.size !in 5..8||actualHeader!=header.take(actualHeader.size))return Result(emptyList(),listOf(Issue(1,"TIMETABLE_CSV_HEADER_INVALID")))
        val rows=mutableListOf<RawRow>();val issues=mutableListOf<Issue>()
        meaningful.drop(1).forEachIndexed{index,cells->
            val line=index+2
            if(cells.size!=actualHeader.size){issues+=Issue(line,"TIMETABLE_CSV_COLUMN_COUNT_INVALID");return@forEachIndexed}
            val day=cells[0].toIntOrNull()
            if(day==null){issues+=Issue(line,"INVALID_DAY");return@forEachIndexed}
            rows+=RawRow(line,day,cells[1],cells[2],cells[3],cells[4],cells.getOrNull(5)?.ifBlank{null},cells.getOrNull(6)?.ifBlank{"THEORY"}?:"THEORY",cells.getOrNull(7)?.ifBlank{"REGULAR"}?:"REGULAR")
        }
        if(rows.isEmpty()&&issues.isEmpty())issues+=Issue(2,"EMPTY_TIMETABLE")
        return Result(rows,issues)
    }
}
