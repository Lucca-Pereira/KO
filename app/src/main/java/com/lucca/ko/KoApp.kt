package com.lucca.ko

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KoApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Scope for work that lives as long as the process: the one-shot data repairs.
     *
     * Held as a property rather than created inline at the call site so it can be cancelled —
     * without that, a repair still running against the database outlives anything that tears the
     * database down.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            // One-shot data repairs, each guarded by its own flag; see data/repair/StartupRepairs.kt.
            container.startupRepairs.runAll()
            // First-run data: the bundled food table, and creatine + whey as presets.
            runCatching { container.foodSeedLoader.seedIfEmpty() }
            runCatching { container.supplementRepository.seedDefaultsIfEmpty() }
        }

        // ON_START fires once for the initial launch and again every time the app returns to the
        // foreground from the background — exactly "sync on foreground" from the process level,
        // rather than tied to any one screen's composition. SyncRepository itself no-ops quietly
        // when no NAS URL/token is set, so this is harmless before the user ever configures sync.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    applicationScope.launch { runCatching { container.syncRepository.sync() } }
                }
            },
        )
    }
}
