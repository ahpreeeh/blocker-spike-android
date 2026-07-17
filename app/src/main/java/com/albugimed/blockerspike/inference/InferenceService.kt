package com.albugimed.blockerspike.inference

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * Exécute Gemma 3n E4B via LiteRT-LM dans un processus dédié (":inference").
 * Si le système tue ce processus sous pression mémoire, le service
 * d'accessibilité et l'état de blocage survivent — exigence T3 §4.
 *
 * Le modèle (~4 Go) n'est pas embarqué dans l'APK : il est copié dans le
 * stockage externe de l'app (voir [modelFile]), par adb ou manuellement.
 */
class InferenceService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var messenger: Messenger

    private var engine: Engine? = null
    private var backendUsed: String = "aucun"
    private var lastLoadMs: Long = -1

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("inference-worker").also { it.start() }
        messenger = Messenger(object : Handler(workerThread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_GENERATE) return
                val replyTo = msg.replyTo ?: return
                val prompt = msg.data.getString(KEY_PROMPT).orEmpty()
                val reply = Message.obtain(null, MSG_RESULT)
                reply.data = generateBlocking(prompt)
                runCatching { replyTo.send(reply) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { engine?.close() }
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun generateBlocking(prompt: String): Bundle = try {
        val e = ensureEngine()
        val t0 = SystemClock.elapsedRealtime()
        val conversation = e.createConversation()
        val text = try {
            conversation.sendMessage(prompt).toString()
        } finally {
            runCatching { conversation.close() }
        }
        val genMs = SystemClock.elapsedRealtime() - t0
        Bundle().apply {
            putBoolean(KEY_OK, true)
            putString(KEY_TEXT, text)
            putString(KEY_BACKEND, backendUsed)
            putLong(KEY_LOAD_MS, lastLoadMs)
            putLong(KEY_GEN_MS, genMs)
        }
    } catch (t: Throwable) {
        Bundle().apply {
            putBoolean(KEY_OK, false)
            putString(KEY_ERROR, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun ensureEngine(): Engine {
        engine?.let { return it }
        val file = modelFile(this)
        check(file.exists()) { "Modèle absent : ${file.absolutePath}" }
        val t0 = SystemClock.elapsedRealtime()
        val created = try {
            Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU()))
                .also { it.initialize() }
                .also { backendUsed = "GPU" }
        } catch (gpuFailure: Throwable) {
            Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU()))
                .also { it.initialize() }
                .also { backendUsed = "CPU (repli : ${gpuFailure.javaClass.simpleName})" }
        }
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        engine = created
        return created
    }

    companion object {
        const val MSG_GENERATE = 1
        const val MSG_RESULT = 2
        const val KEY_PROMPT = "prompt"
        const val KEY_OK = "ok"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"
        const val KEY_BACKEND = "backend"
        const val KEY_LOAD_MS = "load_ms"
        const val KEY_GEN_MS = "gen_ms"

        const val MODEL_FILE_NAME = "gemma-3n-e4b.litertlm"

        /** Ex. /storage/emulated/0/Android/data/com.albugimed.blockerspike/files/models/gemma-3n-e4b.litertlm */
        fun modelFile(context: Context): File =
            File(context.getExternalFilesDir(null), "models/$MODEL_FILE_NAME")
    }
}
