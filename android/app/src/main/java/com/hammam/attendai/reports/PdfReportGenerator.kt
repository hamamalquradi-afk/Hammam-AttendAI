package com.hammam.attendai.reports

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.hammam.attendai.data.local.entity.AuditLogEntity
import com.hammam.attendai.data.local.dao.ReportAttendanceRow
import com.hammam.attendai.domain.model.FinalAttendanceStatus
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class ReportPdfLabels(
    val appName:String,
    val title:String,
    val period:String,
    val totalStudents:String,
    val present:String,
    val absent:String,
    val late:String,
    val partial:String,
    val leftEarly:String,
    val attendanceRate:String,
    val minuteUnit:String,
    val statusLabels:Map<FinalAttendanceStatus,String>,
)

class PdfReportGenerator {
    data class Output(val file:File,val sha256:String)
    private data class PageState(val page:PdfDocument.Page,val canvas:Canvas)

    fun generate(file:File,labels:ReportPdfLabels,summary:ReportSummary,rows:List<ReportAttendanceRow>):Output{
        val doc=PdfDocument()
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK}
        var pageNumber=0
        var current:PageState?=null
        var y=40
        var success=false
        fun finishCurrent(){current?.let{doc.finishPage(it.page)};current=null}
        fun beginPage(){
            finishCurrent()
            pageNumber++
            val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create())
            current=PageState(page,page.canvas)
            y=40
        }
        fun layout(text:String,size:Float,direction:TextDirectionHeuristic):StaticLayout{
            paint.textSize=size
            return StaticLayout.Builder.obtain(text,0,text.length,paint,515).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(direction).setIncludePad(false).build()
        }
        fun draw(text:String,size:Float=16f,direction:TextDirectionHeuristic=TextDirectionHeuristics.FIRSTSTRONG_LTR){
            var block=layout(text,size,direction)
            if(current==null)beginPage()
            if(y+block.height+12>805){
                beginPage()
                val header=layout(labels.appName,16f,TextDirectionHeuristics.FIRSTSTRONG_LTR)
                val target=checkNotNull(current)
                target.canvas.save();target.canvas.translate(40f,y.toFloat());header.draw(target.canvas);target.canvas.restore();y+=header.height+12
                block=layout(text,size,direction)
            }
            val target=checkNotNull(current)
            target.canvas.save();target.canvas.translate(40f,y.toFloat());block.draw(target.canvas);target.canvas.restore();y+=block.height+12
        }
        try{
            beginPage()
            draw(labels.appName,20f)
            draw(labels.title,22f)
            draw(labels.period,14f)
            draw("${labels.totalStudents}: ${summary.totalStudents} | ${labels.present}: ${summary.present} | ${labels.absent}: ${summary.absent}")
            draw("${labels.late}: ${summary.late} | ${labels.partial}: ${summary.partial} | ${labels.leftEarly}: ${summary.leftEarly}")
            draw("${labels.attendanceRate}: ${"%.1f".format(java.util.Locale.ROOT,summary.attendanceRate*100)}%")
            val timeFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
            rows.forEach{r->
                val identity=listOfNotNull(r.studentName,r.universityNumber?.takeIf{it.isNotBlank()}?:r.studentId).joinToString(" | ")
                val entry=r.firstSeenAt?.let{timeFormat.format(java.util.Date(it))}?:"—"
                val exit=r.lastSeenAt?.let{timeFormat.format(java.util.Date(it))}?:"—"
                val status=labels.statusLabels[r.finalStatus]?:r.finalStatus.name
                draw("$identity | $status | ${"%.1f".format(java.util.Locale.ROOT,r.attendancePercentage*100)}% | ${r.verifiedPresenceSeconds/60} ${labels.minuteUnit} | $entry → $exit",12f)
            }
            finishCurrent()
            file.parentFile?.mkdirs()
            FileOutputStream(file).use{doc.writeTo(it)}
            success=true
        }finally{
            runCatching{finishCurrent()}
            runCatching{doc.close()}
            if(!success)file.delete()
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        return Output(file,hash)
    }

    fun generateAudit(file:File,rows:List<AuditLogEntity>):Output{
        val doc=PdfDocument()
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=12f}
        var pageNumber=0
        var current:PageState?=null
        var y=36
        var success=false
        fun finishCurrent(){current?.let{doc.finishPage(it.page)};current=null}
        fun beginPage(){finishCurrent();pageNumber++;val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create());current=PageState(page,page.canvas);y=36}
        fun line(text:String){
            if(current==null)beginPage()
            val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,515).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setIncludePad(false).build()
            if(y+layout.height+8>805)beginPage()
            val target=checkNotNull(current)
            target.canvas.save();target.canvas.translate(40f,y.toFloat());layout.draw(target.canvas);target.canvas.restore();y+=layout.height+8
        }
        try{
            line("Hammam AttendAI | Audit Log")
            // Audit export intentionally caps at 1000 entries; attendance reports do not truncate rows.
            rows.take(1000).forEach{r->line("${r.timestamp} | ${r.actorRole.orEmpty()} | ${r.action} | ${r.entityType}/${r.entityId.orEmpty()} | ${r.reason.orEmpty()}")}
            finishCurrent();file.parentFile?.mkdirs();FileOutputStream(file).use{doc.writeTo(it)};success=true
        }finally{
            runCatching{finishCurrent()};runCatching{doc.close()};if(!success)file.delete()
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        return Output(file,hash)
    }
}
