package com.albugimed.blockerspike.inference

import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.BlockPolicyRepository
import com.albugimed.blockerspike.policy.SystemTimeSource
import com.albugimed.blockerspike.policy.TimeSource
import kotlinx.coroutines.flow.first

enum class UnlockRequestStatus { ALLOWED, DENIED, ERROR }

data class UnlockRequestResult(
    val status: UnlockRequestStatus,
    val message: String,
    val durationMinutes: Int? = null,
    val loadDurationMillis: Long? = null,
    val generationDurationMillis: Long? = null,
    val peakPssKb: Int? = null,
)

class UnlockRequestCoordinator(
    private val repository: BlockPolicyRepository,
    private val inference: LocalInferenceGateway,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    suspend fun request(packageName: String, justification: String): UnlockRequestResult {
        val pkg = packageName.trim()
        if (justification.isBlank()) {
            return denied("Une justification est necessaire")
        }
        val policyBeforeInference = repository.policy.first()
        val nowBeforeInference = timeSource.nowMillis()
        if (!policyBeforeInference.shouldBlock(pkg, nowBeforeInference)) {
            return denied("Cette application n'est pas actuellement bloquee")
        }

        val prompt = UnlockPromptBuilder.build(
            packageName = pkg,
            justification = justification,
            policy = policyBeforeInference,
            nowMillis = nowBeforeInference,
        )
        val generation = inference.generate(prompt).getOrElse { error ->
            val safeError = error.message.orEmpty().take(300)
            InterceptionLog.add(
                InterceptionLog.TAG_INFERENCE,
                "Inference indisponible : ${error.javaClass.simpleName} $safeError",
                packageName = pkg,
            )
            return UnlockRequestResult(
                status = UnlockRequestStatus.ERROR,
                message = "Moteur local indisponible : demande refusee",
            )
        }

        InterceptionLog.add(
            InterceptionLog.TAG_INFERENCE,
            "Sortie brute (${generation.rawText.length} caracteres) : " +
                generation.rawText.take(MAX_LOGGED_OUTPUT_LENGTH),
            packageName = pkg,
        )

        // The policy can change while a multi-second generation is running.
        // Validate against a fresh snapshot immediately before any write.
        val currentPolicy = repository.policy.first()
        val validation = UnlockDecisionValidator.validate(
            rawOutput = generation.rawText,
            packageName = pkg,
            policy = currentPolicy,
            nowMillis = timeSource.nowMillis(),
        )
        InterceptionLog.add(
            InterceptionLog.TAG_INFERENCE,
            "Decision ${validation.disposition} : ${validation.validationReason}",
            packageName = pkg,
        )

        val metrics = UnlockRequestResult(
            status = UnlockRequestStatus.DENIED,
            message = validation.modelReason ?: validation.validationReason,
            durationMinutes = validation.durationMinutes,
            loadDurationMillis = generation.loadDurationMillis,
            generationDurationMillis = generation.generationDurationMillis,
            peakPssKb = generation.peakPssKb,
        )
        val durationMillis = validation.grantDurationMillis ?: return metrics
        if (!repository.grantTemporaryAllowance(pkg, durationMillis)) {
            return metrics.copy(
                status = UnlockRequestStatus.ERROR,
                message = "Decision valide mais ecriture de l'autorisation impossible",
            )
        }
        return metrics.copy(
            status = UnlockRequestStatus.ALLOWED,
            message = validation.modelReason ?: "Autorisation temporaire accordee",
        )
    }

    private fun denied(message: String): UnlockRequestResult {
        InterceptionLog.add(InterceptionLog.TAG_INFERENCE, message)
        return UnlockRequestResult(UnlockRequestStatus.DENIED, message)
    }

    private companion object {
        const val MAX_LOGGED_OUTPUT_LENGTH = 500
    }
}
