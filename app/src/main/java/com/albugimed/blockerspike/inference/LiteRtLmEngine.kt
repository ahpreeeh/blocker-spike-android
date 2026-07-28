package com.albugimed.blockerspike.inference

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** LiteRT-LM adapter. It is instantiated only inside the :inference process. */
class LiteRtLmEngine(context: Context) : LocalLlmEngine {
    private val cacheDirectory = context.cacheDir.absolutePath
    private val engineMutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null

    override suspend fun generate(modelPath: String, prompt: String): Result<LocalGeneration> =
        runCatching {
            engineMutex.withLock {
                var loadDurationMillis = 0L
                val (rawText, peakPssKb) = measurePeakPss {
                    val activeEngine = if (engine == null || loadedModelPath != modelPath) {
                        engine?.close()
                        val startedAt = SystemClock.elapsedRealtime()
                        val initialized = Engine(
                            EngineConfig(
                                modelPath = modelPath,
                                backend = Backend.CPU(),
                                cacheDir = cacheDirectory,
                            )
                        )
                        try {
                            withContext(Dispatchers.IO) { initialized.initialize() }
                        } catch (error: Throwable) {
                            initialized.close()
                            throw error
                        }
                        loadDurationMillis = SystemClock.elapsedRealtime() - startedAt
                        engine = initialized
                        loadedModelPath = modelPath
                        initialized
                    } else {
                        checkNotNull(engine)
                    }

                    val generationStartedAt = SystemClock.elapsedRealtime()
                    val response = withContext(Dispatchers.IO) {
                        activeEngine.createConversation().use { conversation ->
                            conversation.sendMessage(prompt).text
                        }
                    }
                    response to (SystemClock.elapsedRealtime() - generationStartedAt)
                }
                LocalGeneration(
                    rawText = rawText.first,
                    loadDurationMillis = loadDurationMillis,
                    generationDurationMillis = rawText.second,
                    peakPssKb = peakPssKb,
                )
            }
        }

    override fun close() {
        engine?.close()
        engine = null
        loadedModelPath = null
    }

    private suspend fun <T> measurePeakPss(block: suspend () -> T): Pair<T, Int> = coroutineScope {
        val peakPssKb = AtomicInteger(Debug.getPss())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakPssKb.accumulateAndGet(Debug.getPss(), ::maxOf)
                delay(MEMORY_SAMPLE_INTERVAL_MILLIS)
            }
        }
        try {
            val result = block()
            peakPssKb.accumulateAndGet(Debug.getPss(), ::maxOf)
            result to peakPssKb.get()
        } finally {
            sampler.cancelAndJoin()
        }
    }

    private companion object {
        const val MEMORY_SAMPLE_INTERVAL_MILLIS = 100L
    }
}
