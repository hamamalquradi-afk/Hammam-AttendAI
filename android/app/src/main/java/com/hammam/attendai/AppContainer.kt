package com.hammam.attendai

import android.content.Context
import androidx.room.Room
import com.hammam.attendai.ai.*
import com.hammam.attendai.ble.*
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.DatabaseMigrationGuard
import com.hammam.attendai.data.repository.*
import com.hammam.attendai.reports.ReportProcessor
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.security.SecureSecretStore
import com.hammam.attendai.security.AuthorizationRepository
import com.hammam.attendai.data.settings.AppPreferences
import com.hammam.attendai.sync.*
import kotlinx.coroutines.flow.first

class AppContainer(context:Context){
    private val migrationGuardCheck=DatabaseMigrationGuard.requireCompletePath(BuildConfig.DATABASE_VERSION,HammamDatabase.ALL_MIGRATIONS)
    val database:HammamDatabase=Room.databaseBuilder(context,HammamDatabase::class.java,"hammam_attendai.db")
        .addMigrations(*HammamDatabase.ALL_MIGRATIONS).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    val cipher=KeystoreCipher()
    val secretStore=SecureSecretStore(context,cipher)
    val appLock=com.hammam.attendai.security.AppLockManager(secretStore)
    val preferences=AppPreferences(context)
    val authorization=AuthorizationRepository(database)
    val students=StudentRepository(database)
    val dataIntegrity=DataIntegrityRepository(database)
    val devices=DeviceEnrollmentRepository(database,cipher,authorization)
    val attendance=AttendanceRepository(database,authorization,cipher)
    val lectureScheduler=LectureSchedulerRepository(database)
    val academic=AcademicManagementRepository(database,authorization)
    val timetable=TimetableRepository(database,authorization,lectureScheduler)
    val backupManager=com.hammam.attendai.backup.EncryptedBackupManager(context,database)
    val configurationBackup=com.hammam.attendai.backup.ConfigurationBackupManager(database,preferences)
    val reports=ReportRepository(database)
    val audit=AuditRepository(database)
    val featureFlags=FeatureFlagRepository(database)
    val appeals=AttendanceAppealRepository(database,cipher,authorization)
    val bleDetector:PresenceDetector=BlePresenceDetector(context)
    val presenceTokenResolver:PresenceTokenResolver=RoomPresenceTokenResolver(database,cipher)
    val backend:BackendClient=HttpBackendClient({preferences.backendBaseUrl.first()},{secretStore.get("backend.auth.token")})
    val syncProcessor=SyncProcessor(database,backend,cipher::decrypt)
    val notificationProcessor=NotificationProcessor(database,backend,cipher::decrypt)
    val reportProcessor=ReportProcessor(context,database,backend)
    val offlineIntentParser=OfflineIntentParser()
    val attendanceQueryTools:AttendanceQueryTools=RoomAttendanceQueryTools(database,authorization){preferences.userId.first()}
    val offlineAssistant=OfflineAssistantEngine(offlineIntentParser,attendanceQueryTools)
    val aiProviderManager=AiProviderManager(database,cipher,secretStore,backend)
    val aiProvider:AiProvider=aiProviderManager
}
