package com.albugimed.blockerspike.gate

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Confirme qu'une activité T2-B est réellement devenue visible. Sur Android,
 * startActivity() peut ne pas lever d'exception même si un lancement en
 * arrière-plan est bloqué ; le service doit donc attendre un accusé de réception.
 */
object GateLaunchTracker {
    private val shownByToken = ConcurrentHashMap<String, Boolean>()

    fun register(): String = UUID.randomUUID().toString().also { shownByToken[it] = false }

    fun markShown(token: String?) {
        if (token != null && shownByToken.containsKey(token)) shownByToken[token] = true
    }

    fun consumeWasShown(token: String): Boolean = shownByToken.remove(token) == true

    fun discard(token: String) {
        shownByToken.remove(token)
    }
}
