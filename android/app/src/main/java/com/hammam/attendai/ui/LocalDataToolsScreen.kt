package com.hammam.attendai.ui

import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hammam.attendai.R
import com.hammam.attendai.importexport.FileIoSafety
import java.util.Date

internal fun launchFileActionSafely(primary:()->Unit,fallback:(()->Unit)?=null,onFailure:(String)->Unit){
    try{primary()}
    catch(e:RuntimeException){
        Log.e("LocalDataToolsPane","PRIMARY_FILE_ACTION_FAILED",e)
        val primaryDecision=FileIoSafety.decision(FileIoSafety.fromExceptionClass(e.javaClass.simpleName))
        if(primaryDecision.tryFallback&&fallback!=null){
            try{fallback()}
            catch(fallbackError:RuntimeException){
                Log.e("LocalDataToolsPane","FALLBACK_FILE_ACTION_FAILED",fallbackError)
                onFailure(FileIoSafety.decision(FileIoSafety.fromExceptionClass(fallbackError.javaClass.simpleName)).messageCode)
            }
        }else onFailure(primaryDecision.messageCode)
    }
}

@Composable
fun LocalDataToolsPane(permissions:Set<String>,vm:AdminOperationsViewModel){
    val context=LocalContext.current
    val sessionUserId by vm.sessionUserId.collectAsState()
    val backupStatus by vm.backupStatus.collectAsState()
    val selectedBackupUri by vm.selectedBackupUri.collectAsState()
    val shareUri by vm.shareBackupUri.collectAsState()
    val shareableExport by vm.shareableExport.collectAsState()
    val configPreview by vm.configPreview.collectAsState()
    val studentImport by vm.studentImportPreview.collectAsState()
    val importTarget by vm.studentImportTarget.collectAsState()
    val levels by vm.levels.collectAsState();val batches by vm.batches.collectAsState();val sections by vm.sections.collectAsState();val groups by vm.groups.collectAsState()
    val integrity by vm.integrityResult.collectAsState()
    val pendingReplacements by vm.pendingDeviceReplacements.collectAsState()
    val deviceReviewBusy by vm.deviceReviewBusy.collectAsState()
    val deviceActionResult by vm.deviceActionResult.collectAsState()
    var passphrase by rememberSaveable{mutableStateOf("")}
    var confirmRestore by rememberSaveable{mutableStateOf(false)}
    var pairingCode by rememberSaveable{mutableStateOf("")}
    var deviceDecisionNote by rememberSaveable{mutableStateOf("")}
    var importLevel by rememberSaveable{mutableStateOf("")};var importBatch by rememberSaveable{mutableStateOf("")};var importSection by rememberSaveable{mutableStateOf("")};var importGroup by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(sessionUserId){passphrase="";pairingCode="";deviceDecisionNote="";importLevel="";importBatch="";importSection="";importGroup="";confirmRestore=false}
    LaunchedEffect(studentImport){if(studentImport==null){importLevel="";importBatch="";importSection="";importGroup=""}}
    LaunchedEffect(importLevel,importBatch,importSection,importGroup){vm.setStudentImportTarget(importLevel,importBatch,importSection,importGroup)}
    LaunchedEffect(deviceActionResult){when(deviceActionResult){"PAIRING_DONE"->pairingCode="";"REPLACEMENT_DONE"->deviceDecisionNote=""};if(deviceActionResult!=null)vm.consumeDeviceActionResult()}

    val createBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)vm.exportEncryptedBackup(uri,passphrase)}
    val openBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.validateBackup(uri,passphrase)}
    val getBackupContent=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)vm.validateBackup(uri,passphrase)}
    val createConfig=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)vm.exportConfiguration(uri)}
    val openConfig=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.previewConfiguration(uri)}
    val getConfigContent=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)vm.previewConfiguration(uri)}
    val openStudentCsv=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.previewStudentCsv(uri)}
    val getStudentCsv=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)vm.previewStudentCsv(uri)}
    val exportStudentCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null)vm.exportStudentsCsv(uri)}

    LaunchedEffect(shareUri){shareUri?.let{uri->
        try{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},context.getString(R.string.share_backup)))}
        catch(e:RuntimeException){Log.e("LocalDataToolsPane","BACKUP_SHARE_FAILED",e);vm.reportFileActionFailure(FileIoSafety.decision(FileIoSafety.fromExceptionClass(e.javaClass.simpleName)).messageCode)}
        finally{vm.consumeShareBackup()}
    }}
    LaunchedEffect(shareableExport){shareableExport?.let{request->
        val title=when(request.chooserTitleCode){"SHARE_CONFIGURATION"->context.getString(R.string.share_configuration);"SHARE_STUDENT_CSV"->context.getString(R.string.share_student_csv);else->context.getString(R.string.share_file)}
        try{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=request.mimeType;putExtra(Intent.EXTRA_STREAM,request.uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},title))}
        catch(e:RuntimeException){Log.e("LocalDataToolsPane","EXPORT_SHARE_FAILED",e);vm.reportFileActionFailure(FileIoSafety.decision(FileIoSafety.fromExceptionClass(e.javaClass.simpleName)).messageCode)}
        finally{vm.consumeShareableExport()}
    }}

    if(confirmRestore){AlertDialog(onDismissRequest={confirmRestore=false},title={Text(stringResource(R.string.restore_backup_confirm_title))},text={Text(stringResource(R.string.restore_backup_confirm_body))},confirmButton={Button(onClick={confirmRestore=false;vm.restoreSelectedBackup(passphrase)}){Text(stringResource(R.string.restore_backup_confirm_action))}},dismissButton={TextButton(onClick={confirmRestore=false}){Text(stringResource(R.string.cancel))}})}

    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text(stringResource(R.string.backup_restore),style=MaterialTheme.typography.titleLarge)}
        item{OutlinedTextField(passphrase,{passphrase=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.backup_passphrase))},singleLine=true,visualTransformation=PasswordVisualTransformation(),supportingText={Text(stringResource(R.string.backup_passphrase_note))})}
        if("MANAGE_BACKUP" in permissions){
            item{Button(onClick={launchFileActionSafely({createBackup.launch("Hammam-AttendAI.hammamattend")},{vm.createShareableBackup(passphrase)},vm::reportFileActionFailure)},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.create_backup))}}
            item{OutlinedButton(onClick={vm.createShareableBackup(passphrase)},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.share_backup))}}
            item{OutlinedButton(onClick={launchFileActionSafely({openBackup.launch(arrayOf("application/octet-stream","application/zip","*/*"))},{getBackupContent.launch("*/*")},vm::reportFileActionFailure)},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.select_validate_backup))}}
        }
        if(("RESTORE_BACKUP" in permissions||"MANAGE_BACKUP" in permissions))item{Button(onClick={confirmRestore=true},enabled=passphrase.length>=8&&selectedBackupUri!=null,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.restore_validated_backup))}}
        backupStatus?.let{code->item{Text(userMessageText(code),style=MaterialTheme.typography.bodySmall)}}
        if("RUN_DATA_INTEGRITY" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.data_integrity),style=MaterialTheme.typography.titleLarge)}
            item{Button(onClick={vm.runDataIntegrity()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.run_integrity_check))}}
            integrity?.let{r->item{Text(if(r.healthy)stringResource(R.string.integrity_healthy) else stringResource(R.string.integrity_needs_attention),color=if(r.healthy)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)};items(r.issues.size){i->val issue=r.issues[i];ListItem(headlineContent={Text(userMessageText(issue.code))},supportingContent={Text("${issue.count} • ${userMessageText(issue.suggestedAction)}")})}}
        }
        if("MANAGE_DEVICE_ENROLLMENT" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.device_management),style=MaterialTheme.typography.titleLarge)}
            item{Text(stringResource(R.string.pending_device_pairings),style=MaterialTheme.typography.titleMedium)}
            item{OutlinedTextField(pairingCode,{pairingCode=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.one_time_pairing_code))},minLines=2,supportingText={Text(stringResource(R.string.pairing_review_note))},textStyle=LocalTextStyle.current.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr))}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vm.acceptDevicePairing(pairingCode)},enabled=pairingCode.isNotBlank()&&!deviceReviewBusy,modifier=Modifier.weight(1f)){Text(stringResource(R.string.accept))};OutlinedButton(onClick={vm.rejectDevicePairing(pairingCode)},enabled=pairingCode.isNotBlank()&&!deviceReviewBusy,modifier=Modifier.weight(1f)){Text(stringResource(R.string.reject))}}}
            item{HorizontalDivider();Text(stringResource(R.string.pending_replacement_requests),style=MaterialTheme.typography.titleMedium)}
            item{OutlinedTextField(deviceDecisionNote,{deviceDecisionNote=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.reason))},minLines=2)}
            if(pendingReplacements.isEmpty())item{Text(stringResource(R.string.no_pending_device_requests),style=MaterialTheme.typography.bodySmall)}
            items(pendingReplacements.size){i->val row=pendingReplacements[i];Card{Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(row.studentName,style=MaterialTheme.typography.titleMedium);row.universityNumber?.let{Text(it,style=LocalTextStyle.current.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr))};Text("••••${row.request.newDevicePublicId.takeLast(8)}",style=LocalTextStyle.current.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr));Text(DateFormat.format("yyyy-MM-dd HH:mm",Date(row.request.requestedAt)).toString(),style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vm.approveDeviceReplacement(row.request.id,deviceDecisionNote)},enabled=deviceDecisionNote.isNotBlank()&&!deviceReviewBusy,modifier=Modifier.weight(1f)){Text(stringResource(R.string.accept))};OutlinedButton(onClick={vm.rejectDeviceReplacement(row.request.id,deviceDecisionNote)},enabled=deviceDecisionNote.isNotBlank()&&!deviceReviewBusy,modifier=Modifier.weight(1f)){Text(stringResource(R.string.reject))}}}}}
        }
        if("EDIT_STUDENTS" in permissions||"VIEW_STUDENTS" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.student_csv_tools),style=MaterialTheme.typography.titleLarge)}
            if("EDIT_STUDENTS" in permissions)item{Button(onClick={launchFileActionSafely({openStudentCsv.launch(arrayOf("text/csv","text/plain","*/*"))},{getStudentCsv.launch("text/*")},vm::reportFileActionFailure)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.select_student_csv))}}
            if("VIEW_STUDENTS" in permissions)item{OutlinedButton(onClick={launchFileActionSafely({exportStudentCsv.launch("Hammam-AttendAI-students.csv")},{vm.createShareableStudentCsv()},vm::reportFileActionFailure)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.export_students_csv))}}
            studentImport?.let{p->
                item{Text(stringResource(R.string.csv_preview_counts,p.valid.size,p.invalid.size));p.invalid.take(8).forEach{r->Text(stringResource(R.string.csv_row_error,r.line,userMessageText(r.errors.firstOrNull()?:"CSV_HAS_INVALID_ROWS")),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}
                if(p.invalid.isEmpty()&&p.valid.isNotEmpty()){
                    item{Text(stringResource(R.string.student_import_academic_scope),style=MaterialTheme.typography.titleMedium)}
                    item{LocalEntityChoice(stringResource(R.string.level),importLevel,levels.map{it.id to it.name}){importLevel=it;importBatch="";importSection="";importGroup=""}}
                    item{LocalEntityChoice(stringResource(R.string.batch),importBatch,batches.filter{it.levelId==importLevel}.map{it.id to it.name}){importBatch=it;importSection="";importGroup=""}}
                    item{LocalEntityChoice(stringResource(R.string.section),importSection,sections.filter{it.batchId==importBatch}.map{it.id to it.name}){importSection=it;importGroup=""}}
                    item{LocalEntityChoice(stringResource(R.string.group),importGroup,groups.filter{it.sectionId==importSection}.map{it.id to it.name}){importGroup=it}}
                    item{Button(onClick={vm.confirmStudentCsvImport()},enabled=importTarget!=null,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.confirm_csv_import))}}
                }
            }
        }
        if("MANAGE_CONFIGURATION" in permissions||"MANAGE_SETTINGS" in permissions||"MANAGE_BACKUP" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.configuration_backup),style=MaterialTheme.typography.titleLarge)}
            item{Text(stringResource(R.string.configuration_backup_note),style=MaterialTheme.typography.bodySmall)}
            item{Button(onClick={launchFileActionSafely({createConfig.launch("Hammam-AttendAI-config.hconf")},{vm.createShareableConfiguration()},vm::reportFileActionFailure)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.export_configuration))}}
            item{OutlinedButton(onClick={launchFileActionSafely({openConfig.launch(arrayOf("text/plain","*/*"))},{getConfigContent.launch("text/plain")},vm::reportFileActionFailure)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.import_configuration_preview))}}
            configPreview?.let{p->item{Text(stringResource(R.string.configuration_preview_summary,p.policies,p.reportSettings,p.featureFlags,p.rolePermissions,p.academicYears,p.semesters));p.issues.forEach{Text(userMessageText(it.substringAfter(':',it)),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}};if(p.valid)item{Button(onClick={vm.importSelectedConfiguration()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.confirm_import_configuration))}}}
        }
    }
}

@Composable
private fun LocalEntityChoice(label:String,value:String,options:List<Pair<String,String>>,onSelect:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    val shown=options.firstOrNull{it.first==value}?.second?:stringResource(R.string.select_value)
    Box(Modifier.fillMaxWidth()){
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text("$label:");Spacer(Modifier.width(6.dp));Text(shown,Modifier.weight(1f));Icon(Icons.Default.ArrowDropDown,null)}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={onSelect(id);expanded=false})}}
    }
}
