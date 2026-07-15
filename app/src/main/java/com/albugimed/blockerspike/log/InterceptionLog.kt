package com.albugimed.blockerspike.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Journal local minimal en mémoire (protocole §2). Suffisant pour le spike :
 * les entrées vivent tant que le processus vit, ce qui permet aussi de
 * constater les kills HyperOS (journal vide après coupure silencieuse).
 */
object InterceptionLog {

    data class Entry(val atMillis: Long, val tag: String, val message: String)

    const val TAG_SERVICE = "service"
    const val TAG_EVENT = "event"
    const val TAG_INTERCEPT = "intercept"

    private const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun add(tag: String, message: String) {
        _entries.value =
            (listOf(Entry(System.currentTimeMillis(), tag, message)) + _entries.value)
                .take(MAX_ENTRIES)
    }
}
