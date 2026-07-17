package com.albugimed.blockerspike.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Client du processus d'inférence : lie le service, envoie un prompt,
 * attend la réponse puis se détache. Une requête à la fois par client.
 */
class InferenceClient(context: Context) {

    private val appContext = context.applicationContext

    data class Reply(
        val text: String,
        val backend: String,
        val loadMs: Long,
        val genMs: Long,
    )

    suspend fun generate(prompt: String): Result<Reply> =
        suspendCancellableCoroutine { cont ->
            lateinit var connection: ServiceConnection

            fun finish(result: Result<Reply>) {
                runCatching { appContext.unbindService(connection) }
                if (cont.isActive) cont.resume(result)
            }

            val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != InferenceService.MSG_RESULT) return
                    val data = msg.data
                    if (data.getBoolean(InferenceService.KEY_OK)) {
                        finish(
                            Result.success(
                                Reply(
                                    text = data.getString(InferenceService.KEY_TEXT).orEmpty(),
                                    backend = data.getString(InferenceService.KEY_BACKEND).orEmpty(),
                                    loadMs = data.getLong(InferenceService.KEY_LOAD_MS, -1),
                                    genMs = data.getLong(InferenceService.KEY_GEN_MS, -1),
                                )
                            )
                        )
                    } else {
                        finish(
                            Result.failure(
                                IllegalStateException(
                                    data.getString(InferenceService.KEY_ERROR) ?: "erreur inconnue"
                                )
                            )
                        )
                    }
                }
            })

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    runCatching {
                        val msg = Message.obtain(null, InferenceService.MSG_GENERATE)
                        msg.data = Bundle().apply {
                            putString(InferenceService.KEY_PROMPT, prompt)
                        }
                        msg.replyTo = replyMessenger
                        Messenger(binder).send(msg)
                    }.onFailure { finish(Result.failure(it)) }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(IllegalStateException("processus d'inférence déconnecté").let { Result.failure(it) })
                }
            }

            val bound = appContext.bindService(
                Intent(appContext, InferenceService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                finish(Result.failure(IllegalStateException("bindService a échoué")))
            }

            cont.invokeOnCancellation { runCatching { appContext.unbindService(connection) } }
        }
}
