package com.hammam.attendai.security

enum class LocalSessionStatus {
    CHECKING,
    READY,
    MISSING_USER,
    DISABLED_USER,
    MISSING_ROLE,
}

data class LocalSessionSnapshot(
    val storedUserId:String?,
    val activeUserId:String?,
    val primaryRole:String?,
    val status:LocalSessionStatus,
)

enum class ManagementSectionKey { USERS, ACADEMIC, TEACHERS, SUBJECTS, TIMETABLE, REPORTS, AI, DATA }

/** Pure access/session presentation rules. Repository authorization remains authoritative. */
object LocalAccessRules {
    private val rolePriority=listOf(
        "SYSTEM_OWNER",
        "Administrator",
        "Academic Supervisor",
        "Teacher",
        "Representative",
        "Assistant Representative",
        "Student",
    )

    val appLockConfigurationRoles=setOf("SYSTEM_OWNER","Administrator","Representative")

    fun primaryRole(roles:Collection<String>):String? = rolePriority.firstOrNull(roles::contains) ?: roles.sorted().firstOrNull()

    fun resolve(storedUserId:String?,userExists:Boolean,userActive:Boolean,roles:Collection<String>):LocalSessionSnapshot {
        if(storedUserId==null)return LocalSessionSnapshot(null,null,null,LocalSessionStatus.MISSING_USER)
        if(!userExists)return LocalSessionSnapshot(storedUserId,null,null,LocalSessionStatus.MISSING_USER)
        if(!userActive)return LocalSessionSnapshot(storedUserId,null,null,LocalSessionStatus.DISABLED_USER)
        val role=primaryRole(roles)?:return LocalSessionSnapshot(storedUserId,null,null,LocalSessionStatus.MISSING_ROLE)
        return LocalSessionSnapshot(storedUserId,storedUserId,role,LocalSessionStatus.READY)
    }

    fun checking()=LocalSessionSnapshot(null,null,null,LocalSessionStatus.CHECKING)

    fun canConfigureAppLock(role:String?):Boolean=role in appLockConfigurationRoles

    fun allowedManagementSections(permissions:Set<String>):List<ManagementSectionKey> = buildList {
        if(permissions.any{it in setOf("MANAGE_USERS","MANAGE_ROLES","MANAGE_PERMISSIONS")})add(ManagementSectionKey.USERS)
        if("MANAGE_ACADEMIC_STRUCTURE" in permissions)add(ManagementSectionKey.ACADEMIC)
        if("MANAGE_TEACHERS" in permissions)add(ManagementSectionKey.TEACHERS)
        if("MANAGE_SUBJECTS" in permissions)add(ManagementSectionKey.SUBJECTS)
        if("MANAGE_TIMETABLE" in permissions)add(ManagementSectionKey.TIMETABLE)
        if("MANAGE_REPORT_SETTINGS" in permissions)add(ManagementSectionKey.REPORTS)
        if("MANAGE_AI_PROVIDER" in permissions)add(ManagementSectionKey.AI)
        if(permissions.any{it in setOf("MANAGE_BACKUP","MANAGE_CONFIGURATION","RUN_DATA_INTEGRITY","EDIT_STUDENTS","MANAGE_DEVICE_ENROLLMENT")})add(ManagementSectionKey.DATA)
    }

    fun hasManagementAccess(permissions:Set<String>):Boolean=allowedManagementSections(permissions).isNotEmpty()

    fun revalidateManagementSection(current:ManagementSectionKey?,permissions:Set<String>):ManagementSectionKey?{
        val allowed=allowedManagementSections(permissions)
        return current?.takeIf{it in allowed} ?: allowed.firstOrNull()
    }

    fun validPin(pin:String):Boolean=pin.length in 4..12&&pin.all(Char::isDigit)

    fun safeErrorCode(message:String?,fallback:String):String {
        val code=message?.trim().orEmpty()
        return if(code.matches(Regex("[A-Z][A-Z0-9_]{2,79}")))code else fallback
    }
}

object FirstRunBootstrapRules {
    fun canCreateBootstrapOwner(userCount:Int,anySystemOwnerExists:Boolean):Boolean=userCount==0&&!anySystemOwnerExists
    fun mayOfferAdministratorRecovery(userCount:Int,anySystemOwnerExists:Boolean,activeAdministratorExists:Boolean):Boolean=
        userCount>0&&!anySystemOwnerExists&&activeAdministratorExists
}
