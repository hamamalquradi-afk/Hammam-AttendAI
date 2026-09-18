package com.hammam.attendai.reports

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.hammam.attendai.data.local.entity.AuditLogEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PdfReportGenerator {
    data class Output(val file:File,val sha256:String)
    fun generate(file:File,title:String,period:String,summary:ReportSummary,rows:List<com.hammam.attendai.data.local.dao.ReportAttendanceRow>):Output{
        val doc=PdfDocument();val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK};var pageNumber=0;var page:PdfDocument.Page?=null;var canvas:Canvas?=null;var y=40
        fun beginPage(){
            page?.let{doc.finishPage(it)}
            pageNumber++;page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create());canvas=page!!.canvas;y=40
        }
        fun layout(text:String,size:Float,direction:android.text.TextDirectionHeuristic):StaticLayout{
            paint.textSize=size
            return StaticLayout.Builder.obtain(text,0,text.length,paint,515).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(direction).setIncludePad(false).build()
        }
        fun draw(text:String,size:Float=16f,direction:android.text.TextDirectionHeuristic=TextDirectionHeuristics.RTL){
            var block=layout(text,size,direction)
            if(y+block.height+12>805){
                beginPage()
                val header=layout("Hammam AttendAI | همّام للحضور الذكي",16f,TextDirectionHeuristics.FIRSTSTRONG_LTR)
                canvas!!.save();canvas!!.translate(40f,y.toFloat());header.draw(canvas!!);canvas!!.restore();y+=header.height+12
                block=layout(text,size,direction)
            }
            canvas!!.save();canvas!!.translate(40f,y.toFloat());block.draw(canvas!!);canvas!!.restore();y+=block.height+12
        }
        beginPage()
        draw("Hammam AttendAI | همّام للحضور الذكي",20f,TextDirectionHeuristics.FIRSTSTRONG_LTR);draw(title,22f);draw(period,14f)
        draw("إجمالي الطلاب: ${summary.totalStudents} | الحاضرون: ${summary.present} | الغائبون: ${summary.absent}")
        draw("المتأخرون: ${summary.late} | حضور جزئي: ${summary.partial} | خروج مبكر: ${summary.leftEarly}")
        draw("نسبة الحضور: ${"%.1f".format(summary.attendanceRate*100)}%")
        val timeFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
        rows.forEach{r->
            val identity=listOfNotNull(r.studentName,r.universityNumber?.takeIf{it.isNotBlank()}?:r.studentId).joinToString(" | ")
            val entry=r.firstSeenAt?.let{timeFormat.format(java.util.Date(it))}?:"—";val exit=r.lastSeenAt?.let{timeFormat.format(java.util.Date(it))}?:"—"
            draw("$identity | ${r.finalStatus.name} | ${"%.1f".format(r.attendancePercentage*100)}% | ${r.verifiedPresenceSeconds/60} min | $entry → $exit",12f,TextDirectionHeuristics.FIRSTSTRONG_LTR)
        }
        page?.let{doc.finishPage(it)};file.parentFile?.mkdirs();FileOutputStream(file).use{doc.writeTo(it)};doc.close()
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        return Output(file,hash)
    }
    fun generateAudit(file:File,rows:List<AuditLogEntity>):Output{
        val doc=PdfDocument();val pageInfo=PdfDocument.PageInfo.Builder(595,842,1).create();var pageNumber=1;var page=doc.startPage(pageInfo);var canvas=page.canvas
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=12f};var y=36
        fun line(text:String){
            if(y>800){doc.finishPage(page);pageNumber++;page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create());canvas=page.canvas;y=36}
            val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,515).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setIncludePad(false).build()
            canvas.save();canvas.translate(40f,y.toFloat());layout.draw(canvas);canvas.restore();y+=layout.height+8
        }
        line("Hammam AttendAI | Audit Log")
        rows.take(1000).forEach{r->line("${r.timestamp} | ${r.actorRole.orEmpty()} | ${r.action} | ${r.entityType}/${r.entityId.orEmpty()} | ${r.reason.orEmpty()}")}
        doc.finishPage(page);file.parentFile?.mkdirs();FileOutputStream(file).use{doc.writeTo(it)};doc.close()
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        return Output(file,hash)
    }

}
