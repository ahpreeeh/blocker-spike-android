package com.albugimed.blockerspike.inference

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
    private var loadedPreference: InferenceBackendPreference? = null
    private var backendLabel: String = "none"

    @Volatile
    private var activeConversation: Conversation? = null

    override suspend fun generate(
        modelPath: String,
        prompt: String,
        backendPreference: InferenceBackendPreference,
    ): Result<LocalGeneration> =
        runCatching {
            engineMutex.withLock {
                var loadDurationMillis = 0L
                val (generation, peakPssKb) = measurePeakPss {
                    var activeEngine = if (
                        engine == null ||
                        loadedModelPath != modelPath ||
                        loadedPreference != backendPreference
                    ) {
                        clearEngineState()
                        val startedAt = SystemClock.elapsedRealtime()
                        val initialized = initializeEngine(modelPath, backendPreference)
                        loadDurationMillis = SystemClock.elapsedRealtime() - startedAt
                        engine = initialized.engine
                        loadedModelPath = modelPath
                        loadedPreference = backendPreference
                        backendLabel = initialized.backendLabel
                        initialized.engine
                    } else {
                        checkNotNull(engine)
                    }

                    try {
                        generateOnce(activeEngine, prompt)
                    } catch (gpuGenerationError: Throwable) {
                        val canRetryOnCpu =
                            backendPreference == InferenceBackendPreference.AUTO &&
                                backendLabel == "GPU" &&
                                currentCoroutineContext().isActive
                        if (!canRetryOnCpu) throw gpuGenerationError

                        clearEngineState()
                        val fallbackStartedAt = SystemClock.elapsedRealtime()
                        val fallback = initializeWithBackend(
                            modelPath = modelPath,
                            backend = Backend.CPU(),
                            label = "CPU (erreur generation GPU: " +
                                "${gpuGenerationError.javaClass.simpleName})",
                        )
                        loadDurationMillis +=
                            SystemClock.elapsedRealtime() - fallbackStartedAt
                        engine = fallback.engine
                        loadedModelPath = modelPath
                        loadedPreference = backendPreference
                        backendLabel = fallback.backendLabel
                        activeEngine = fallback.engine
                        generateOnce(activeEngine, prompt)
                    }
                }
                LocalGeneration(
                    rawText = generation.first,
                    backend = backendLabel,
                    loadDurationMillis = loadDurationMillis,
                    generationDurationMillis = generation.second,
                    peakPssKb = peakPssKb,
                )
            }
        }

    private suspend fun generateOnce(activeEngine: Engine, prompt: String): Pair<String, Long> {
        val generationStartedAt = SystemClock.elapsedRealtime()
        val response = withContext(Dispatchers.IO) {
            val conversation = activeEngine.createConversation(DETERMINISTIC_CONVERSATION)
            activeConversation = conversation
            try {
                conversation.sendMessage(prompt).toString()
            } finally {
                activeConversation = null
                conversation.close()
            }
        }
        return response to (SystemClock.elapsedRealtime() - generationStartedAt)
    }

    override fun cancelActiveGeneration() {
        runCatching { activeConversation?.cancelProcess() }
    }

    override fun close() {
        cancelActiveGeneration()
        clearEngineState()
    }

    private suspend fun initializeEngine(
        modelPath: String,
        preference: InferenceBackendPreference,
    ): InitializedEngine = when (preference) {
        InferenceBackendPreference.GPU -> initializeWithBackend(modelPath, Backend.GPU(), "GPU")
        InferenceBackendPreference.CPU -> initializeWithBackend(modelPath, Backend.CPU(), "CPU")
        InferenceBackendPreference.AUTO -> {
            val gpuAttempt = runCatching {
                initializeWithBackend(modelPath, Backend.GPU(), "GPU")
            }
            gpuAttempt.getOrElse { gpuError ->
                try {
                    initializeWithBackend(
                        modelPath,
                        Backend.CPU(),
                        "CPU (GPU indisponible: ${gpuError.javaClass.simpleName})",
                    )
                } catch (cpuError: Throwable) {
                    cpuError.addSuppressed(gpuError)
                    throw cpuError
                }
            }
        }
    }

    private suspend fun initializeWithBackend(
        modelPath: String,
        backend: Backend,
        label: String,
    ): InitializedEngine {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = cacheDirectory,
            )
        )
        try {
            withContext(Dispatchers.IO) { candidate.initialize() }
            return InitializedEngine(candidate, label)
        } catch (error: Throwable) {
            // close() can itself fail if native initialization did not reach a
            // valid handle; never mask the original load failure.
            runCatching { candidate.close() }
            throw error
        }
    }

    private fun clearEngineState() {
        val previous = engine
        engine = null
        loadedModelPath = null
        loadedPreference = null
        backendLabel = "none"
        previous?.let { runCatching { it.close() } }
    }

    private suspend fun <T> measurePeakPss(block: suspend () -> T): Pair<T, Long> = coroutineScope {
        val peakPssKb = AtomicLong(Debug.getPss())
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
        const val MAX_NUM_TOKENS = 4_096
        val DETERMINISTIC_CONVERSATION = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 1,
                topP = 1.0,
                temperature = 0.0,
                seed = 42,
            )
        )
    }

    private data class InitializedEngine(val engine: Engine, val backendLabel: String)
}
