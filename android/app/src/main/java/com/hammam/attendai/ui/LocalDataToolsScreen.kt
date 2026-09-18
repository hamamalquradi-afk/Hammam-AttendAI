package com.hammam.attendai.ui

import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hammam.attendai.R
import java.util.Date

@Composable
fun LocalDataToolsPane(permissions:Set<String>,vm:AdminOperationsViewModel){
    val context=LocalContext.current
    val backupStatus by vm.backupStatus.collectAsState()
    val shareUri by vm.shareBackupUri.collectAsState()
    val configPreview by vm.configPreview.collectAsState()
    val studentImport by vm.studentImportPreview.collectAsState()
    val integrity by vm.integrityResult.collectAsState()
    val pendingReplacements by vm.pendingDeviceReplacements.collectAsState()
    var passphrase by rememberSaveable{mutableStateOf("")}
    var pairingCode by rememberSaveable{mutableStateOf("")}
    var deviceDecisionNote by rememberSaveable{mutableStateOf("")}
    val createBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)vm.exportEncryptedBackup(uri,passphrase)}
    val openBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.validateBackup(uri,passphrase)}
    val createConfig=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)vm.exportConfiguration(uri)}
    val openConfig=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.previewConfiguration(uri)}
    val openStudentCsv=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.previewStudentCsv(uri)}
    val exportStudentCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null)vm.exportStudentsCsv(uri)}
    LaunchedEffect(shareUri){shareUri?.let{uri->context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},context.getString(R.string.share_backup)));vm.consumeShareBackup()}}
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text(stringResource(R.string.backup_restore),style=MaterialTheme.typography.titleLarge)}
        item{OutlinedTextField(passphrase,{passphrase=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.backup_passphrase))},singleLine=true,supportingText={Text(stringResource(R.string.backup_passphrase_note))})}
        if("MANAGE_BACKUP" in permissions){
            item{Button(onClick={createBackup.launch("Hammam-AttendAI.hammamattend")},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.create_backup))}}
            item{OutlinedButton(onClick={vm.createShareableBackup(passphrase)},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.share_backup))}}
            item{OutlinedButton(onClick={openBackup.launch(arrayOf("application/octet-stream","application/zip","*/*"))},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.select_validate_backup))}}
        }
        if(("RESTORE_BACKUP" in permissions||"MANAGE_BACKUP" in permissions))item{Button(onClick={vm.restoreSelectedBackup(passphrase)},enabled=passphrase.length>=8,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.restore_validated_backup))}}
        backupStatus?.let{item{Text(it,style=MaterialTheme.typography.bodySmall)}}
        if("RUN_DATA_INTEGRITY" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.data_integrity),style=MaterialTheme.typography.titleLarge)}
            item{Button(onClick={vm.runDataIntegrity()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.run_integrity_check))}}
            integrity?.let{r->item{Text(if(r.healthy)stringResource(R.string.integrity_healthy) else stringResource(R.string.integrity_needs_attention),color=if(r.healthy)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)};items(r.issues.size){i->val issue=r.issues[i];ListItem(headlineContent={Text(issue.code)},supportingContent={Text("${issue.count} • ${issue.suggestedAction}")})}}
        }
        if("MANAGE_DEVICE_ENROLLMENT" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.device_management),style=MaterialTheme.typography.titleLarge)}
            item{Text(stringResource(R.string.pending_device_pairings),style=MaterialTheme.typography.titleMedium)}
            item{OutlinedTextField(pairingCode,{pairingCode=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.one_time_pairing_code))},minLines=2,supportingText={Text(stringResource(R.string.pairing_review_note))})}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vm.acceptDevicePairing(pairingCode);pairingCode=""},enabled=pairingCode.isNotBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.accept))};OutlinedButton(onClick={vm.rejectDevicePairing(pairingCode);pairingCode=""},enabled=pairingCode.isNotBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.reject))}}}
            item{HorizontalDivider();Text(stringResource(R.string.pending_replacement_requests),style=MaterialTheme.typography.titleMedium)}
            item{OutlinedTextField(deviceDecisionNote,{deviceDecisionNote=it},modifier=Modifier.fillMaxWidth(),label={Text(stringResource(R.string.reason))},minLines=2)}
            if(pendingReplacements.isEmpty())item{Text(stringResource(R.string.no_pending_device_requests),style=MaterialTheme.typography.bodySmall)}
            items(pendingReplacements.size){i->val row=pendingReplacements[i];Card{Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(row.studentName,style=MaterialTheme.typography.titleMedium);row.universityNumber?.let{Text(it)};Text("••••${row.request.newDevicePublicId.takeLast(8)}");Text(DateFormat.format("yyyy-MM-dd HH:mm",Date(row.request.requestedAt)).toString(),style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vm.approveDeviceReplacement(row.request.id,deviceDecisionNote)},enabled=deviceDecisionNote.isNotBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.accept))};OutlinedButton(onClick={vm.rejectDeviceReplacement(row.request.id,deviceDecisionNote)},enabled=deviceDecisionNote.isNotBlank(),modifier=Modifier.weight(1f)){Text(stringResource(R.string.reject))}}}}}
        }
        if("EDIT_STUDENTS" in permissions||"VIEW_STUDENTS" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.student_csv_tools),style=MaterialTheme.typography.titleLarge)}
            if("EDIT_STUDENTS" in permissions)item{Button(onClick={openStudentCsv.launch(arrayOf("text/csv","text/plain","*/*"))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.select_student_csv))}}
            if("VIEW_STUDENTS" in permissions)item{OutlinedButton(onClick={exportStudentCsv.launch("Hammam-AttendAI-students.csv")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.export_students_csv))}}
            studentImport?.let{p->item{Text(stringResource(R.string.csv_preview_counts,p.valid.size,p.invalid.size));p.invalid.take(8).forEach{r->Text("${r.line}: ${r.errors.joinToString()}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}};if(p.invalid.isEmpty()&&p.valid.isNotEmpty())item{Button(onClick={vm.confirmStudentCsvImport()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.confirm_csv_import))}}}
        }
        if("MANAGE_SETTINGS" in permissions||"MANAGE_BACKUP" in permissions){
            item{HorizontalDivider();Text(stringResource(R.string.configuration_backup),style=MaterialTheme.typography.titleLarge)}
            item{Text(stringResource(R.string.configuration_backup_note),style=MaterialTheme.typography.bodySmall)}
            item{Button(onClick={createConfig.launch("Hammam-AttendAI-config.hconf")},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.export_configuration))}}
            item{OutlinedButton(onClick={openConfig.launch(arrayOf("text/plain","*/*"))},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.import_configuration_preview))}}
            configPreview?.let{p->item{Text("${stringResource(R.string.preview)}: policies=${p.policies}, reports=${p.reportSettings}, flags=${p.featureFlags}, roles=${p.rolePermissions}, years=${p.academicYears}, semesters=${p.semesters}");p.issues.forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}};if(p.valid)item{Button(onClick={vm.importSelectedConfiguration()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.confirm_import_configuration))}}}
        }
    }
}
