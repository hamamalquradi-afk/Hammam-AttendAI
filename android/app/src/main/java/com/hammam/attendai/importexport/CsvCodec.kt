package com.hammam.attendai.importexport

/** Small RFC-4180-style CSV codec used by local import/export paths. */
object CsvCodec {
    data class ParseResult(val rows:List<List<String>>,val error:String?=null)

    fun parse(text:String):ParseResult {
        val input=text.removePrefix("\uFEFF")
        if(input.isEmpty())return ParseResult(emptyList())
        val rows=mutableListOf<List<String>>()
        val row=mutableListOf<String>()
        val cell=StringBuilder()
        var quoted=false
        var quoteClosed=false
        var i=0
        fun finishCell(){row+=cell.toString();cell.setLength(0);quoteClosed=false}
        fun finishRow(){finishCell();rows+=row.toList();row.clear()}
        while(i<input.length){
            val ch=input[i]
            when {
                quoted && ch=='"' && i+1<input.length && input[i+1]=='"' -> {cell.append('"');i++}
                quoted && ch=='"' -> {quoted=false;quoteClosed=true}
                quoted -> cell.append(ch)
                ch=='"' && cell.isEmpty() && !quoteClosed -> quoted=true
                ch==',' -> finishCell()
                ch=='\r' -> {
                    if(i+1<input.length && input[i+1]=='\n')i++
                    finishRow()
                }
                ch=='\n' -> finishRow()
                quoteClosed && ch.isWhitespace() -> Unit
                quoteClosed -> return ParseResult(emptyList(),"MALFORMED_CSV")
                else -> cell.append(ch)
            }
            i++
        }
        if(quoted)return ParseResult(emptyList(),"MALFORMED_CSV")
        if(cell.isNotEmpty()||row.isNotEmpty()||quoteClosed)finishRow()
        return ParseResult(rows)
    }

    fun spreadsheetSafe(value:String):String = if(value.trimStart().firstOrNull() in setOf('=','+','-','@'))"'$value" else value

    fun escape(value:String,protectFormula:Boolean=true):String {
        val safe=if(protectFormula)spreadsheetSafe(value) else value
        return if(safe.any{it==','||it=='"'||it=='\n'||it=='\r'})"\"${safe.replace("\"","\"\"")}\"" else safe
    }
}
