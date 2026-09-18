package com.hammam.attendai.importexport

data class StudentImportRow(val line:Int,val universityNumber:String?,val fullName:String,val phone:String?,val errors:List<String>)
data class StudentImportPreview(val valid:List<StudentImportRow>,val invalid:List<StudentImportRow>)
object StudentCsv {
    fun preview(csv:String):StudentImportPreview{
        val seen=mutableSetOf<String>(); val rows=csv.lineSequence().drop(1).filter{it.isNotBlank()}.mapIndexed{idx,line->
            val c=line.split(',').map{it.trim().trim('"')}; val num=c.getOrNull(0)?.ifBlank{null}; val name=c.getOrNull(1).orEmpty(); val phone=c.getOrNull(2)?.ifBlank{null}; val e=mutableListOf<String>()
            if(name.isBlank())e+="EMPTY_NAME"; if(num!=null&&!seen.add(num))e+="DUPLICATE_UNIVERSITY_NUMBER"; if(phone!=null&&!phone.matches(Regex("[+0-9 -]{6,20}")))e+="INVALID_PHONE"
            StudentImportRow(idx+2,num,name,phone,e)
        }.toList(); return StudentImportPreview(rows.filter{it.errors.isEmpty()},rows.filter{it.errors.isNotEmpty()})
    }
    fun export(rows:List<Pair<String?,String>>):String=buildString{appendLine("university_number,full_name");rows.forEach{(n,name)->appendLine("${n.orEmpty()},\"${name.replace("\"","\"\"")}\"")}}
}
