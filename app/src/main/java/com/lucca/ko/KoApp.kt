package com.lucca.ko

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KoApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Scope for work that lives as long as the process: currently just the one-shot data repairs.
     *
     * Held as a property rather than created inline at the call site so it can be cancelled —
     * without that, a repair still running against the database outlives anything that tears the
     * database down, which is exactly what a Robolectric test does between cases.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // One-shot data repairs, each guarded by its own flag; see data/repair/StartupRepairs.kt.
        applicationScope.launch { container.startupRepairs.runAll() }
    }
}
