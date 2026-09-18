package com.hammam.attendai.security

import androidx.room.withTransaction
import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class AuthorizationRepository(private val db:HammamDatabase){
    private val dao=db.coreDao()

    suspend fun hasPermission(userId:String?,permission:String):Boolean {
        if(userId==null || !dao.isUserActive(userId))return false
        expireGrants()
        return dao.hasPermission(userId,permission) || dao.hasActivePermissionGrant(userId,permission,System.currentTimeMillis(),null,null)
    }

    suspend fun hasScopedPermission(userId:String?,permission:String,scopeType:String,scopeId:String):Boolean {
        if(userId==null || !dao.isUserActive(userId))return false
        expireGrants()
        val now=System.currentTimeMillis()
        if(dao.hasActivePermissionGrant(userId,permission,now,scopeType,scopeId))return true
        if(!dao.hasPermission(userId,permission))return false
        if(isGlobal(userId))return true
        return when(scopeType.uppercase()){
            "GROUP"->dao.canAccessGroupScope(userId,scopeId)
            "STUDENT"->dao.canAccessStudent(userId,scopeId)
            else->dao.hasExactScope(userId,scopeType.uppercase(),scopeId)
        }
    }

    suspend fun canAccessStudent(userId:String?,studentId:String):Boolean = userId!=null && dao.isUserActive(userId) && dao.canAccessStudent(userId,studentId)
    suspend fun canAccessGroup(userId:String?,groupId:String):Boolean = userId!=null && dao.isUserActive(userId) && (isGlobal(userId) || dao.canAccessGroupScope(userId,groupId))
    suspend fun canAccessReportJob(userId:String?,jobId:String):Boolean {
        if(userId==null || !dao.isUserActive(userId))return false
        if(isGlobal(userId))return true
        val job=dao.getReportJob(jobId)?:return false
        if(dao.hasExactScope(userId,"TEACHER",job.teacherId))return true
        val subject=job.subjectId?.let{dao.getSubjectById(it)}?:return false
        return canAccessGroup(userId,subject.groupId)
    }
    suspend fun isGlobal(userId:String):Boolean = dao.isUserActive(userId) && dao.getRoleNames(userId).any{it=="SYSTEM_OWNER" || it=="Administrator"}
    suspend fun isSystemOwner(userId:String):Boolean = dao.getRoleNames(userId).contains("SYSTEM_OWNER")
    suspend fun roleNames(userId:String):List<String> = dao.getRoleNames(userId)
    fun observeRoleNames(userId:String):Flow<List<String>> = dao.observeRoleNames(userId)
    fun observePermissionGrants(userId:String)=dao.observePermissionGrants(userId)

    suspend fun createInitialOwner(displayName:String):String = db.withTransaction {
        seedAuthorizationModelInTransaction()
        check(dao.countUsers()==0){"INITIAL_OWNER_ALREADY_EXISTS"}
        createUserWithRole(displayName,"SYSTEM_OWNER",null,null)
    }

    suspend fun createLocalProfile(displayName:String,roleName:String):String = db.withTransaction {
        seedAuthorizationModelInTransaction()
        createUserWithRole(displayName,roleName,null,null)
    }

    suspend fun createManagedUser(displayName:String,roleName:String,scopeType:String?,scopeId:String?,actorId:String):String = db.withTransaction {
        require(hasPermission(actorId,"MANAGE_USERS")){"MANAGE_USERS_PERMISSION_REQUIRED"}
        require(roleName!="SYSTEM_OWNER"){"SYSTEM_OWNER_CREATION_PROTECTED"}
        if(roleName=="Administrator")require(isSystemOwner(actorId)){"SYSTEM_OWNER_REQUIRED"}
        val id=createUserWithRole(displayName,roleName,scopeType,scopeId)
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"USER_CREATED","User",id,null,"{\"role\":\"$roleName\"}",null,System.currentTimeMillis(),dao.getRoleNames(actorId).firstOrNull()))
        id
    }

    suspend fun setUserActive(targetUserId:String,active:Boolean,actorId:String,reason:String){
        require(hasPermission(actorId,"MANAGE_USERS")){"MANAGE_USERS_PERMISSION_REQUIRED"};require(reason.isNotBlank()){"REASON_REQUIRED"}
        require(!isSystemOwner(targetUserId)){"SYSTEM_OWNER_PROTECTED"}
        db.withTransaction{val user=dao.getUserById(targetUserId)?:error("USER_NOT_FOUND");val now=System.currentTimeMillis();dao.updateUser(user.copy(isActive=active,updatedAt=now,version=user.version+1));dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,if(active)"USER_ENABLED" else "USER_DISABLED","User",targetUserId,"{\"active\":${user.isActive}}","{\"active\":$active}",reason,now,dao.getRoleNames(actorId).firstOrNull()))}
    }

    suspend fun replaceRole(targetUserId:String,roleName:String,actorId:String,reason:String){
        require(hasPermission(actorId,"MANAGE_ROLES")){"MANAGE_ROLES_PERMISSION_REQUIRED"};require(reason.isNotBlank()){"REASON_REQUIRED"}
        require(!isSystemOwner(targetUserId)){"SYSTEM_OWNER_ROLE_PROTECTED"};require(roleName!="SYSTEM_OWNER"){"SYSTEM_OWNER_ROLE_PROTECTED"}
        db.withTransaction{val roleId=dao.getRoleIdByName(roleName)?:error("ROLE_NOT_FOUND");dao.clearUserRoles(targetUserId);dao.insertUserRole(UserRoleEntity(targetUserId,roleId));dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"ROLE_CHANGED","User",targetUserId,null,"{\"role\":\"$roleName\"}",reason,System.currentTimeMillis(),dao.getRoleNames(actorId).firstOrNull()))}
    }

    private suspend fun createUserWithRole(displayName:String,roleName:String,scopeType:String?,scopeId:String?):String{
        val now=System.currentTimeMillis();val userId=UUID.randomUUID().toString();val username="local-${userId.take(8)}"
        dao.insertUser(UserEntity(userId,username,displayName.trim().ifBlank{"Local User"},null,true,now,now,1))
        val roleId=dao.getRoleIdByName(roleName) ?: error("ROLE_NOT_FOUND")
        dao.insertUserRole(UserRoleEntity(userId,roleId))
        if(scopeType!=null)dao.upsertUserScope(UserScopeEntity(UUID.randomUUID().toString(),userId,scopeType.uppercase(),scopeId,true,now,userId))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),userId,"LOCAL_PROFILE_CREATED","User",userId,null,"{\"role\":\"$roleName\"}",null,now,roleName))
        return userId
    }

    suspend fun grantScope(userId:String,scopeType:String,scopeId:String?,actorId:String,reason:String){
        require(hasPermission(actorId,"MANAGE_USERS")){"MANAGE_USERS_PERMISSION_REQUIRED"}
        db.withTransaction{
        val actorRole=dao.getRoleNames(actorId).firstOrNull()
        dao.upsertUserScope(UserScopeEntity(UUID.randomUUID().toString(),userId,scopeType.uppercase(),scopeId,true,System.currentTimeMillis(),actorId))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"SCOPE_GRANTED","User",userId,null,"{\"scopeType\":\"${scopeType.uppercase()}\",\"scopeId\":${scopeId?.let{"\"$it\""}?:"null"}}",reason,System.currentTimeMillis(),actorRole))
        }
    }

    suspend fun grantTemporaryPermission(userId:String,permission:String,scopeType:String?,scopeId:String?,startsAt:Long?,expiresAt:Long?,actorId:String,reason:String):String{
        require(hasPermission(actorId,"MANAGE_PERMISSIONS")){"MANAGE_PERMISSIONS_PERMISSION_REQUIRED"}
        require(permission in PermissionCatalog.all){"UNKNOWN_PERMISSION"};require(reason.isNotBlank()){"REASON_REQUIRED"}
        if(expiresAt!=null)require(expiresAt>(startsAt?:System.currentTimeMillis())){"INVALID_EXPIRY"}
        return db.withTransaction{
        val id=UUID.randomUUID().toString();val now=System.currentTimeMillis();val actorRole=dao.getRoleNames(actorId).firstOrNull()
        dao.insertPermissionGrant(UserPermissionGrantEntity(id,userId,permission,scopeType?.uppercase(),scopeId,startsAt,expiresAt,actorId,now,reason.trim(),true,null))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"PERMISSION_GRANTED","UserPermissionGrant",id,null,"{\"userId\":\"$userId\",\"permission\":\"$permission\",\"expiresAt\":${expiresAt?:"null"}}",reason.trim(),now,actorRole))
        id
        }
    }

    suspend fun revokeGrant(grantId:String,actorId:String,reason:String){
        require(hasPermission(actorId,"MANAGE_PERMISSIONS")){"MANAGE_PERMISSIONS_PERMISSION_REQUIRED"};require(reason.isNotBlank()){"REASON_REQUIRED"}
        db.withTransaction{
        val grant=dao.getPermissionGrant(grantId)?:error("GRANT_NOT_FOUND");val now=System.currentTimeMillis();val actorRole=dao.getRoleNames(actorId).firstOrNull()
        dao.updatePermissionGrant(grant.copy(active=false,revokedAt=now))
        dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"PERMISSION_REVOKED","UserPermissionGrant",grant.id,"{\"active\":true}","{\"active\":false}",reason.trim(),now,actorRole))
        }
    }

    suspend fun expireGrants(){
        val now=System.currentTimeMillis()
        db.withTransaction{
            dao.getExpiredPermissionGrants(now).forEach{g->
                dao.updatePermissionGrant(g.copy(active=false,revokedAt=now))
                dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),null,"PERMISSION_EXPIRED","UserPermissionGrant",g.id,"{\"active\":true}","{\"active\":false}","Grant expired",now,"SYSTEM"))
            }
        }
    }

    suspend fun claimUpgradeSystemOwner(actorId:String){
        require(dao.isUserActive(actorId)){"USER_DISABLED"}
        require(dao.getSystemOwnerUser()==null){"SYSTEM_OWNER_ALREADY_EXISTS"}
        require(dao.getRoleNames(actorId).contains("Administrator")){"ADMINISTRATOR_REQUIRED"}
        db.withTransaction{
            val roleId=dao.getRoleIdByName("SYSTEM_OWNER")?:error("SYSTEM_OWNER_ROLE_NOT_SEEDED")
            dao.insertUserRole(UserRoleEntity(actorId,roleId))
            dao.insertAudit(AuditLogEntity(UUID.randomUUID().toString(),actorId,"SYSTEM_OWNER_CLAIMED","User",actorId,null,"{\"role\":\"SYSTEM_OWNER\"}","Confirmed upgrade ownership setup",System.currentTimeMillis(),"Administrator"))
        }
    }

    suspend fun seedAuthorizationModel()=db.withTransaction { seedAuthorizationModelInTransaction() }

    private suspend fun seedAuthorizationModelInTransaction(){
        val rolePermissions=mapOf(
            "Student" to setOf("SUBMIT_APPEAL"),
            "Representative" to setOf("VIEW_STUDENTS","EDIT_STUDENTS","START_LECTURE","END_LECTURE","EDIT_ATTENDANCE","APPROVE_ATTENDANCE","VIEW_REPORTS","SEND_REPORTS","APPROVE_REPORT","REVIEW_APPEALS","MANAGE_DEVICE_ENROLLMENT"),
            "Assistant Representative" to setOf("VIEW_STUDENTS","START_LECTURE","END_LECTURE","EDIT_ATTENDANCE","VIEW_REPORTS","REVIEW_APPEALS"),
            "Teacher" to setOf("VIEW_STUDENTS","APPROVE_ATTENDANCE","VIEW_REPORTS","SEND_REPORTS","APPROVE_REPORT","REVIEW_APPEALS"),
            "Academic Supervisor" to (PermissionCatalog.all - setOf("MANAGE_USERS","MANAGE_ROLES","MANAGE_PERMISSIONS","MANAGE_AI_PROVIDER","RESTORE_BACKUP")),
            "Administrator" to (PermissionCatalog.all - setOf("MANAGE_ROLES","MANAGE_PERMISSIONS")),
            "SYSTEM_OWNER" to PermissionCatalog.all,
        )
        val newPermissions=mutableSetOf<String>()
        PermissionCatalog.all.forEach { code -> if(dao.insertPermission(PermissionEntity("perm:$code",code,null))>0)newPermissions+=code }
        val newRoles=mutableSetOf<String>()
        rolePermissions.keys.forEach { role -> if(dao.insertRole(RoleEntity("role:${role.lowercase().replace(' ','_')}",role,null))>0)newRoles+=role }
        rolePermissions.forEach { (role,codes) ->
            val roleId=dao.getRoleIdByName(role) ?: return@forEach
            codes.filter{role in newRoles || it in newPermissions}.forEach inner@{ code ->
                val permissionId=dao.getPermissionIdByCode(code) ?: return@inner
                dao.insertRolePermission(RolePermissionEntity(roleId,permissionId))
            }
        }
    }
}
