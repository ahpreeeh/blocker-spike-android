package com.albugimed.blockerspike.inference

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Bound IPC service hosted in :inference, away from the Device Owner process. */
class InferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: LocalLlmEngine
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        engine = LiteRtLmEngine(applicationContext)
        messenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                if (message.what != InferenceProtocol.REQUEST_GENERATION) {
                    false
                } else {
                    handleGeneration(message)
                    true
                }
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        engine.close()
        super.onDestroy()
    }

    private fun handleGeneration(request: Message) {
        val replyTo = request.replyTo ?: return
        val requestId = request.data.getLong(InferenceProtocol.KEY_REQUEST_ID)
        val modelPath = request.data.getString(InferenceProtocol.KEY_MODEL_PATH).orEmpty()
        val prompt = request.data.getString(InferenceProtocol.KEY_PROMPT).orEmpty()

        scope.launch {
            val generation = if (modelPath.isBlank() || prompt.isBlank()) {
                Result.failure(IllegalArgumentException("Requete d'inference incomplete"))
            } else {
                engine.generate(modelPath, prompt)
            }
            val responseData = Bundle().apply {
                putLong(InferenceProtocol.KEY_REQUEST_ID, requestId)
                generation.fold(
                    onSuccess = { result ->
                        putString(InferenceProtocol.KEY_RAW_TEXT, result.rawText)
                        putLong(InferenceProtocol.KEY_LOAD_MILLIS, result.loadDurationMillis)
                        putLong(
                            InferenceProtocol.KEY_GENERATION_MILLIS,
                            result.generationDurationMillis,
                        )
                        putInt(InferenceProtocol.KEY_PEAK_PSS_KB, result.peakPssKb)
                    },
                    onFailure = { error ->
                        putString(
                            InferenceProtocol.KEY_ERROR,
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(500),
                        )
                    },
                )
            }
            runCatching {
                replyTo.send(
                    Message.obtain(null, InferenceProtocol.GENERATION_RESULT).apply {
                        data = responseData
                    }
                )
            }
        }
    }
}
