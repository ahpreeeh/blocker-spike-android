package com.albugimed.blockerspike.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class InferenceResult(
    val rawText: String,
    val backend: String,
    val loadDurationMillis: Long,
    val generationDurationMillis: Long,
    val peakPssKb: Long,
)

interface LocalInferenceGateway {
    suspend fun generate(prompt: String): Result<InferenceResult>
}

class InferenceClient(context: Context) : LocalInferenceGateway {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun generate(prompt: String): Result<InferenceResult> {
        val model = ModelLocator.findModel(appContext).getOrElse { return Result.failure(it) }
        return withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            bindAndGenerate(model.absolutePath, prompt)
        } ?: Result.failure(IllegalStateException("Inference interrompue apres 120 secondes"))
    }

    private suspend fun bindAndGenerate(
        modelPath: String,
        prompt: String,
    ): Result<InferenceResult> = suspendCancellableCoroutine { continuation ->
        val requestId = nextRequestId.incrementAndGet()
        val cleanedUp = AtomicBoolean(false)
        var bound = false
        var remoteMessenger: Messenger? = null
        lateinit var connection: ServiceConnection

        fun cleanup() {
            if (cleanedUp.compareAndSet(false, true) && bound) {
                runCatching { appContext.unbindService(connection) }
            }
        }

        fun scheduleCleanup(delayMillis: Long) {
            mainHandler.postDelayed({ cleanup() }, delayMillis)
        }

        fun finish(result: Result<InferenceResult>, keepWarm: Boolean = false) {
            if (keepWarm) scheduleCleanup(WARM_BINDING_MILLIS) else cleanup()
            if (continuation.isActive) continuation.resume(result)
        }

        fun cancelRemoteGeneration() {
            val cancel = Message.obtain(null, InferenceProtocol.CANCEL_GENERATION).apply {
                data = android.os.Bundle().apply {
                    putLong(InferenceProtocol.KEY_REQUEST_ID, requestId)
                }
            }
            runCatching { remoteMessenger?.send(cancel) }
        }

        val replyMessenger = Messenger(
            Handler(Looper.getMainLooper()) { response ->
                if (response.what != InferenceProtocol.GENERATION_RESULT ||
                    response.data.getLong(InferenceProtocol.KEY_REQUEST_ID) != requestId
                ) {
                    false
                } else {
                    val error = response.data.getString(InferenceProtocol.KEY_ERROR)
                    if (error != null) {
                        finish(Result.failure(IllegalStateException(error)))
                    } else {
                        finish(
                            Result.success(
                                InferenceResult(
                                    rawText = response.data
                                        .getString(InferenceProtocol.KEY_RAW_TEXT)
                                        .orEmpty(),
                                    backend = response.data
                                        .getString(InferenceProtocol.KEY_BACKEND)
                                        .orEmpty(),
                                    loadDurationMillis = response.data
                                        .getLong(InferenceProtocol.KEY_LOAD_MILLIS),
                                    generationDurationMillis = response.data
                                        .getLong(InferenceProtocol.KEY_GENERATION_MILLIS),
                                    peakPssKb = response.data
                                        .getLong(InferenceProtocol.KEY_PEAK_PSS_KB),
                                )
                            ),
                            keepWarm = true,
                        )
                    }
                    true
                }
            }
        )

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    finish(Result.failure(IllegalStateException("Service d'inference sans binder")))
                    return
                }
                remoteMessenger = Messenger(binder)
                val request = Message.obtain(null, InferenceProtocol.REQUEST_GENERATION).apply {
                    replyTo = replyMessenger
                    data = android.os.Bundle().apply {
                        putLong(InferenceProtocol.KEY_REQUEST_ID, requestId)
                        putString(InferenceProtocol.KEY_MODEL_PATH, modelPath)
                        putString(InferenceProtocol.KEY_PROMPT, prompt)
                    }
                }
                runCatching { remoteMessenger?.send(request) }
                    .onFailure { finish(Result.failure(it)) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(Result.failure(IllegalStateException("Processus d'inference arrete")))
            }

            override fun onNullBinding(name: ComponentName?) {
                finish(Result.failure(IllegalStateException("Service d'inference indisponible")))
            }
        }

        continuation.invokeOnCancellation {
            cancelRemoteGeneration()
            // Keep the binding briefly so the remote native call can observe
            // cancellation before Android destroys the service process.
            scheduleCleanup(CANCELLATION_GRACE_MILLIS)
        }
        val intent = Intent(appContext, InferenceService::class.java)
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            finish(Result.failure(it))
            false
        }
        if (!bound) {
            finish(Result.failure(IllegalStateException("Connexion au moteur local impossible")))
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 120_000L
        const val WARM_BINDING_MILLIS = 120_000L
        const val CANCELLATION_GRACE_MILLIS = 5_000L
        val nextRequestId = AtomicLong(0L)
    }
}
