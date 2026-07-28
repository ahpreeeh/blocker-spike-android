package com.albugimed.blockerspike.log

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Journal local minimal en mémoire (protocole §2). Suffisant pour le spike :
 * les entrées vivent tant que le processus vit, ce qui permet aussi de
 * constater les kills HyperOS (journal vide après coupure silencieuse).
 */
object InterceptionLog {

    data class Entry(
        val atMillis: Long,
        val atElapsedMillis: Long,
        val tag: String,
        val message: String,
        val packageName: String? = null,
        val latencyMillis: Long? = null,
        val homeActionSucceeded: Boolean? = null,
    )

    data class Metrics(
        val interceptionCount: Int,
        val successfulHomeActions: Int,
        val medianLatencyMillis: Long?,
    )

    const val TAG_SERVICE = "service"
    const val TAG_EVENT = "event"
    const val TAG_INTERCEPT = "intercept"
    const val TAG_GATE = "gate"
    const val TAG_POLICY = "policy"
    const val TAG_INFERENCE = "inference"
    const val TAG_ERROR = "error"

    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun add(
        tag: String,
        message: String,
        packageName: String? = null,
        latencyMillis: Long? = null,
        homeActionSucceeded: Boolean? = null,
    ) {
        _entries.value =
            (
                listOf(
                    Entry(
                        atMillis = System.currentTimeMillis(),
                        atElapsedMillis = SystemClock.elapsedRealtime(),
                        tag = tag,
                        message = message,
                        packageName = packageName,
                        latencyMillis = latencyMillis,
                        homeActionSucceeded = homeActionSucceeded,
                    )
                ) + _entries.value
            )
                .take(MAX_ENTRIES)
    }

    fun metrics(): Metrics {
        val interceptions = _entries.value.filter { it.tag == TAG_INTERCEPT && it.latencyMillis != null }
        val latencies = interceptions.mapNotNull { it.latencyMillis }.sorted()
        val median = when {
            latencies.isEmpty() -> null
            latencies.size % 2 == 1 -> latencies[latencies.size / 2]
            else -> {
                val upper = latencies.size / 2
                (latencies[upper - 1] + latencies[upper]) / 2
            }
        }
        return Metrics(
            interceptionCount = interceptions.size,
            successfulHomeActions = interceptions.count { it.homeActionSucceeded == true },
            medianLatencyMillis = median,
        )
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
