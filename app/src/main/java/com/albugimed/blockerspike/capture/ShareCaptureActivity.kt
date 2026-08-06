package com.albugimed.blockerspike.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.albugimed.blockerspike.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Le point d'entrée du partage. **Aucun formulaire** (P4-06 §3) : on
 * enregistre et on rend la main.
 *
 * Une capture doit coûter un geste. Demander une catégorie, un chapitre ou
 * une échéance ici transformerait la capture en saisie, et le besoin
 * d'origine — « ne pas perdre l'information quand elle passe » — serait
 * perdu au premier moment où l'on est pressé.
 *
 * L'activité n'a pas de contenu et se ferme immédiatement. Le travail
 * d'écriture continue dans une portée applicative : fermer l'écran ne doit
 * pas annuler l'enregistrement.
 */
class ShareCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val capture = captureFromShare(
            text = intent?.getStringExtra(Intent.EXTRA_TEXT),
            subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            sourcePackage = referrer?.host,
            nowMillis = System.currentTimeMillis(),
        )

        if (capture == null) {
            Toast.makeText(this, "Rien à capturer dans ce partage.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val application = applicationContext
        scope.launch {
            val stored = Graph.captureOutbox.enqueue(capture)
            if (stored) {
                CaptureUploadWorker.schedule(application)
            }
            withContext(Dispatchers.Main) {
                // Le message dit ce qui s'est réellement passé. Un accusé de
                // réception affiché alors que l'écriture a échoué est le seul
                // scénario où l'on perd vraiment quelque chose.
                val message = if (stored) {
                    "Capturé. À trier dans l’atelier."
                } else {
                    "Capture NON enregistrée — réessaie."
                }
                Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private companion object {
        /**
         * Portée applicative, et non celle de l'activité : `finish()` est
         * appelé tout de suite, et une portée liée à l'écran annulerait
         * l'écriture avant qu'elle n'aboutisse.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
