package com.albugimed.blockerspike.inference

import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.SystemTimeSource
import com.albugimed.blockerspike.policy.TimeSource
import com.albugimed.blockerspike.policy.UnlockPolicyGateway
import kotlinx.coroutines.flow.first

enum class UnlockRequestStatus { ALLOWED, DENIED, ERROR }

data class UnlockRequestResult(
    val status: UnlockRequestStatus,
    val message: String,
    val durationMinutes: Int? = null,
    val backend: String? = null,
    val loadDurationMillis: Long? = null,
    val generationDurationMillis: Long? = null,
    val peakPssKb: Long? = null,
)

fun interface UnlockEventLogger {
    fun add(tag: String, message: String, packageName: String?)
}

fun interface PolicyEnforcer {
    suspend fun reconcileAndConfirm(packageName: String, expectedSuspended: Boolean): Boolean
}

private val SystemUnlockEventLogger = UnlockEventLogger { tag, message, packageName ->
    InterceptionLog.add(tag, message, packageName)
}

class UnlockRequestCoordinator(
    private val repository: UnlockPolicyGateway,
    private val inference: LocalInferenceGateway,
    private val timeSource: TimeSource = SystemTimeSource,
    private val eventLogger: UnlockEventLogger = SystemUnlockEventLogger,
    private val exactExpiryAvailable: () -> Boolean = { true },
    private val policyEnforcer: PolicyEnforcer = PolicyEnforcer { _, _ -> true },
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
        if (!exactExpiryAvailable()) {
            return expiryUnavailable(pkg)
        }

        val prompt = UnlockPromptBuilder.build(
            packageName = pkg,
            justification = justification,
            policy = policyBeforeInference,
            nowMillis = nowBeforeInference,
        )
        val generation = inference.generate(prompt).getOrElse { error ->
            val safeError = error.message.orEmpty().take(300)
            eventLogger.add(
                InterceptionLog.TAG_INFERENCE,
                "Inference indisponible : ${error.javaClass.simpleName} $safeError",
                pkg,
            )
            return UnlockRequestResult(
                status = UnlockRequestStatus.ERROR,
                message = "Moteur local indisponible : demande refusee",
            )
        }

        eventLogger.add(
            InterceptionLog.TAG_INFERENCE,
            "Sortie brute (${generation.rawText.length} caracteres) : " +
                generation.rawText.take(MAX_LOGGED_OUTPUT_LENGTH),
            pkg,
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
        eventLogger.add(
            InterceptionLog.TAG_INFERENCE,
            "Decision ${validation.disposition} : ${validation.validationReason}",
            pkg,
        )

        val metrics = UnlockRequestResult(
            status = UnlockRequestStatus.DENIED,
            message = validation.modelReason ?: validation.validationReason,
            durationMinutes = validation.durationMinutes,
            backend = generation.backend,
            loadDurationMillis = generation.loadDurationMillis,
            generationDurationMillis = generation.generationDurationMillis,
            peakPssKb = generation.peakPssKb,
        )
        val durationMillis = validation.grantDurationMillis ?: return metrics
        // The permission can be revoked while the native model is generating.
        // Never write an allowance that Android cannot expire on time.
        if (!exactExpiryAvailable()) {
            return expiryUnavailable(pkg).copy(
                backend = generation.backend,
                loadDurationMillis = generation.loadDurationMillis,
                generationDurationMillis = generation.generationDurationMillis,
                peakPssKb = generation.peakPssKb,
            )
        }
        if (!repository.grantTemporaryAllowanceIfStillBlocked(pkg, durationMillis)) {
            return metrics.copy(
                status = UnlockRequestStatus.ERROR,
                message = "Decision valide mais ecriture de l'autorisation impossible",
            )
        }
        if (!policyEnforcer.reconcileAndConfirm(pkg, expectedSuspended = false)) {
            val rolledBack = repository.revokeTemporaryAllowance(pkg)
            if (rolledBack) {
                policyEnforcer.reconcileAndConfirm(pkg, expectedSuspended = true)
            }
            eventLogger.add(
                InterceptionLog.TAG_INFERENCE,
                "Autorisation annulee : desuspension Android non confirmee",
                pkg,
            )
            return metrics.copy(
                status = UnlockRequestStatus.ERROR,
                message = "Android n'a pas confirme l'autorisation : demande annulee",
            )
        }
        return metrics.copy(
            status = UnlockRequestStatus.ALLOWED,
            message = validation.modelReason ?: "Autorisation temporaire accordee",
        )
    }

    private fun denied(message: String): UnlockRequestResult {
        eventLogger.add(InterceptionLog.TAG_INFERENCE, message, null)
        return UnlockRequestResult(UnlockRequestStatus.DENIED, message)
    }

    private fun expiryUnavailable(packageName: String): UnlockRequestResult {
        val message =
            "Alarme exacte inactive : aucune exception ne peut expirer de facon fiable"
        eventLogger.add(InterceptionLog.TAG_INFERENCE, message, packageName)
        return UnlockRequestResult(UnlockRequestStatus.ERROR, message)
    }

    private companion object {
        const val MAX_LOGGED_OUTPUT_LENGTH = 500
    }
}
