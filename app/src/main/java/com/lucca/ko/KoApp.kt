package com.lucca.ko

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // One-shot data repairs, each guarded by its own flag; see data/repair/StartupRepairs.kt.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.startupRepairs.runAll()
        }
    }
}
