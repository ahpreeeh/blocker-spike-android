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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Bound IPC service hosted in :inference, away from the Device Owner process. */
class InferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: LocalLlmEngine
    private lateinit var messenger: Messenger

    @Volatile
    private var activeRequestId: Long? = null
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = LiteRtLmEngine(applicationContext)
        messenger = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                when (message.what) {
                    InferenceProtocol.REQUEST_GENERATION -> {
                        handleGeneration(message)
                        true
                    }
                    InferenceProtocol.CANCEL_GENERATION -> {
                        val cancelledId = message.data
                            .getLong(InferenceProtocol.KEY_REQUEST_ID)
                        if (activeRequestId == cancelledId) {
                            engine.cancelActiveGeneration()
                            activeJob?.cancel()
                        }
                        true
                    }
                    else -> false
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

        // Messenger dispatches on the main looper: claim the slot before any
        // coroutine starts so CANCEL cannot target an overwritten request id.
        if (activeRequestId != null) {
            sendImmediateError(replyTo, requestId, "Une inference est deja en cours")
            return
        }
        activeRequestId = requestId

        val job = scope.launch(start = CoroutineStart.LAZY) {
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
                        putString(InferenceProtocol.KEY_BACKEND, result.backend)
                        putLong(InferenceProtocol.KEY_LOAD_MILLIS, result.loadDurationMillis)
                        putLong(
                            InferenceProtocol.KEY_GENERATION_MILLIS,
                            result.generationDurationMillis,
                        )
                        putLong(InferenceProtocol.KEY_PEAK_PSS_KB, result.peakPssKb)
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
        activeJob = job
        job.invokeOnCompletion {
            mainHandler.post {
                if (activeRequestId == requestId) {
                    activeRequestId = null
                    activeJob = null
                }
            }
        }
        job.start()
    }

    private fun sendImmediateError(replyTo: Messenger, requestId: Long, error: String) {
        val responseData = Bundle().apply {
            putLong(InferenceProtocol.KEY_REQUEST_ID, requestId)
            putString(InferenceProtocol.KEY_ERROR, error)
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
