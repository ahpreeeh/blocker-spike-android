package com.albugimed.blockerspike.inference

/** Result returned by the native engine before any policy decision is applied. */
data class LocalGeneration(
    val rawText: String,
    val backend: String,
    val loadDurationMillis: Long,
    val generationDurationMillis: Long,
    /** Highest sampled proportional-set-size for the inference process. */
    val peakPssKb: Long,
)

enum class InferenceBackendPreference { AUTO, GPU, CPU }

interface LocalLlmEngine : AutoCloseable {
    suspend fun generate(
        modelPath: String,
        prompt: String,
        backendPreference: InferenceBackendPreference = InferenceBackendPreference.AUTO,
    ): Result<LocalGeneration>

    fun cancelActiveGeneration()
}
