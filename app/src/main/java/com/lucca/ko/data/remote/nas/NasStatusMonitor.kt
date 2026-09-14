package com.lucca.ko.data.remote.nas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the brain is reachable, as far as we last knew. */
sealed interface NasStatus {
    /** Nothing has been checked yet — show nothing rather than a scary banner on cold start. */
    data object Unknown : NasStatus

    data object Checking : NasStatus

    data class Up(
        val version: String,
        val busy: Boolean,
        /** Models the server wants but does not have installed; worth surfacing once. */
        val missingModels: List<String>,
    ) : NasStatus

    data class Down(val message: String) : NasStatus

    val isUp: Boolean get() = this is Up
}

/**
 * One health check for the whole app, shared by every screen.
 *
 * Before this, each screen invented its own error string when a call failed, so "the NAS is off"
 * looked like five different problems. Now one banner says it once.
 *
 * Results are cached for [ttlMillis] so that opening four screens does not mean four requests,
 * and a check in flight is not duplicated.
 */
class NasStatusMonitor(
    private val client: NasClient,
    private val scope: CoroutineScope,
    private val ttlMillis: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<NasStatus>(NasStatus.Unknown)
    val status: StateFlow<NasStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var lastCheckedAt = 0L

    /** Checks if the cached answer has gone stale. Cheap to call from anywhere. */
    fun refreshIfStale() {
        if (now() - lastCheckedAt < ttlMillis && _status.value !is NasStatus.Unknown) return
        scope.launch { check() }
    }

    /** Forces a check regardless of the cache — what the Settings "Test connection" button does. */
    fun refreshNow() {
        scope.launch { check() }
    }

    /**
     * Called after a request fails, so the banner appears immediately rather than at the next
     * poll. A successful request does the reverse via [reportReachable].
     */
    fun reportUnreachable(message: String) {
        _status.value = NasStatus.Down(message)
        lastCheckedAt = now()
    }

    fun reportReachable() {
        if (_status.value is NasStatus.Down) refreshNow()
    }

    private suspend fun check() {
        mutex.withLock {
            _status.value = NasStatus.Checking
            val result = runCatching { client.health() }
            lastCheckedAt = now()
            _status.value = result.fold(
                onSuccess = { health ->
                    if (health.ollama.reachable) {
                        NasStatus.Up(
                            version = health.version,
                            busy = health.busy,
                            missingModels = health.ollama.missing,
                        )
                    } else {
                        // The brain answered but cannot reach Ollama — a different problem from
                        // the brain being off, and it needs a different fix.
                        NasStatus.Down("The brain is running but can't reach Ollama.")
                    }
                },
                onFailure = { NasStatus.Down(it.message ?: "Couldn't reach the brain.") },
            )
        }
    }
}
