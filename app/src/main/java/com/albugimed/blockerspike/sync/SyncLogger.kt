package com.albugimed.blockerspike.sync

import com.albugimed.blockerspike.log.InterceptionLog

/**
 * Frontière de journalisation.
 *
 * Injectée pour deux raisons. La première est technique : `InterceptionLog`
 * appelle `SystemClock`, indisponible sur la JVM, et les tests du moteur
 * d'envoi doivent tourner sans appareil.
 *
 * La seconde est plus importante : le journal de coercition contient des noms
 * de paquets bloqués. Rien du côté études ne doit en dépendre, sous peine de
 * les faire ressortir un jour dans un écran d'études. Ici on n'écrit que des
 * messages d'envoi.
 */
fun interface SyncLogger {
    fun add(tag: String, message: String)

    companion object {
        const val TAG_SYNC = "sync"
        const val TAG_ERROR = "error"
    }
}

val SystemSyncLogger = SyncLogger { tag, message -> InterceptionLog.add(tag, message) }
