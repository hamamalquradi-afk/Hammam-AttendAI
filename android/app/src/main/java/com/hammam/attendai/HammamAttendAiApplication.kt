package com.hammam.attendai

import android.app.Application
import com.hammam.attendai.sync.WorkOrchestrator
import com.hammam.attendai.backup.PendingRestoreApplier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class HammamAttendAiApplication:Application(){
    lateinit var container:AppContainer; private set
    private val startupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate(){
        super.onCreate();PendingRestoreApplier.applyIfPresent(this);container=AppContainer(this);WorkOrchestrator.ensure(this)
        startupScope.launch{
            container.authorization.seedAuthorizationModel()
            val initialized=container.preferences.firstRunComplete.first()
            val ownerExists=container.database.coreDao().getAnySystemOwnerUser()!=null
            container.preferences.setOwnerSetupRequired(initialized && !ownerExists)
        }
    }
}
