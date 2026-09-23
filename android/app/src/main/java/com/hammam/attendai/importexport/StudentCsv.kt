package com.hammam.attendai.importexport

data class StudentImportRow(val line:Int,val universityNumber:String?,val fullName:String,val phone:String?,val errors:List<String>)
data class StudentImportPreview(val valid:List<StudentImportRow>,val invalid:List<StudentImportRow>)
data class StudentImportTarget(val levelId:String,val batchId:String,val sectionId:String,val groupId:String)

object StudentCsv {
    private val acceptedHeaders=setOf(
        listOf("university_number","full_name"),
        listOf("university_number","full_name","phone"),
    )

    fun preview(csv:String):StudentImportPreview{
        val parsed=CsvCodec.parse(csv)
        if(parsed.error!=null)return StudentImportPreview(emptyList(),listOf(StudentImportRow(1,null,"",null,listOf(parsed.error))))
        val rows=parsed.rows.filterNot{r->r.all{it.isBlank()}}
        if(rows.isEmpty())return StudentImportPreview(emptyList(),emptyList())
        val header=rows.first().map{it.trim().lowercase()}
        if(header !in acceptedHeaders)return StudentImportPreview(emptyList(),listOf(StudentImportRow(1,null,"",null,listOf("CSV_HEADER_INVALID"))))
        val expectedColumns=header.size
        val seen=mutableSetOf<String>()
        val data=rows.drop(1).mapIndexed{idx,cells->
            val line=idx+2
            if(cells.size!=expectedColumns)return@mapIndexed StudentImportRow(line,null,"",null,listOf("CSV_COLUMN_COUNT_INVALID"))
            val num=cells[0].trim().ifBlank{null}
            val name=cells[1].trim()
            val phone=cells.getOrNull(2)?.trim()?.ifBlank{null}
            val errors=mutableListOf<String>()
            if(name.isBlank())errors+="EMPTY_NAME"
            if(num!=null&&!seen.add(num))errors+="DUPLICATE_UNIVERSITY_NUMBER"
            if(phone!=null&&!phone.matches(Regex("[+0-9 -]{6,20}")))errors+="INVALID_PHONE"
            StudentImportRow(line,num,name,phone,errors)
        }
        return StudentImportPreview(data.filter{it.errors.isEmpty()},data.filter{it.errors.isNotEmpty()})
    }

    fun export(rows:List<Pair<String?,String>>):String=buildString{
        appendLine("university_number,full_name")
        rows.forEach{(number,name)->appendLine("${CsvCodec.escape(number.orEmpty())},${CsvCodec.escape(name)}")}
    }
}
