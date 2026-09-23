package com.hammam.attendai.ui

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsExportSafetyTest {
    @Test fun diagnosticsFormatterRedactsCredentialLikeValuesAndOmitsSecretFields(){
        val snapshot=DeviceSnapshot(
            sdk=35,androidRelease="15",appVersion="1.0",manufacturer="Vendor",model="Phone",
            bleSupported=true,blePermissionsGranted=true,bluetoothEnabled=true,notificationPermission=true,
            batteryUnrestricted=true,internet=true,databaseVersion=5,pendingSync=1,pendingNotifications=2,pendingReports=3,
            failedWorkManagerJobs=0,lastSuccessfulSync=123L,lastBackupAt=456L,
            providers=listOf(ProviderStatusSummary("OPENAI",true,"FAILED","model-id",789L,"Authorization: Bearer private-token")),
            recentSanitizedErrors=listOf("provider error sk-secret-value")
        )
        val text=diagnosticsText(snapshot)
        assertTrue(text.startsWith("Hammam AttendAI diagnostics"))
        assertFalse(text.contains("private-token"))
        assertFalse(text.contains("sk-secret-value"))
        assertFalse(text.contains("apiKey",ignoreCase=true))
        assertFalse(text.contains("backendAuthToken",ignoreCase=true))
        assertFalse(text.contains("pinHash",ignoreCase=true))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test fun diagnosticValuesCannotInjectExtraLines(){
        val sanitized=sanitizeDiagnosticValue("FAILED\ntoken=secret")
        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains("secret"))
        assertTrue(sanitized.startsWith("FAILED "))
    }
}
