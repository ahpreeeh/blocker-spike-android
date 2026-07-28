package com.albugimed.blockerspike.inference

import com.albugimed.blockerspike.policy.PolicyState
import org.json.JSONObject
import org.json.JSONTokener

enum class UnlockDisposition { ALLOW, DENY }

data class DecisionValidationResult(
    val disposition: UnlockDisposition,
    val durationMinutes: Int? = null,
    val modelReason: String? = null,
    val confidence: String? = null,
    val outputValid: Boolean,
    val validationReason: String,
) {
    val grantDurationMillis: Long?
        get() = if (outputValid && disposition == UnlockDisposition.ALLOW) {
            durationMinutes?.times(60_000L)
        } else {
            null
        }
}

/**
 * Sovereign deterministic boundary between model text and Device Owner state.
 * Every malformed or contextually invalid answer becomes a denial.
 */
object UnlockDecisionValidator {
    private val requiredKeys = setOf(
        "decision",
        "duration_minutes",
        "reason",
        "confidence",
    )
    private val acceptedConfidence = setOf("high", "medium", "low")

    fun validate(
        rawOutput: String,
        packageName: String,
        policy: PolicyState,
        nowMillis: Long,
    ): DecisionValidationResult {
        if (!policy.storageHealthy) return invalid("Politique locale illisible")
        if (policy.failsafeOverride) return invalid("Override de panne actif")
        if (packageName !in policy.blockedPackages) {
            return invalid("Le package n'est pas dans la politique de blocage")
        }
        if (!policy.shouldBlock(packageName, nowMillis)) {
            return invalid("Une autorisation est deja active")
        }

        val text = rawOutput.trim()
        if (!text.startsWith('{') || !text.endsWith('}')) {
            return invalid("La sortie n'est pas un objet JSON pur")
        }
        val json = try {
            val tokener = JSONTokener(text)
            val parsed = tokener.nextValue()
            if (parsed !is JSONObject || tokener.nextClean() != 0.toChar()) {
                return invalid("La sortie contient du texte hors JSON")
            }
            parsed
        } catch (_: Exception) {
            return invalid("JSON invalide")
        }

        if (json.keySet() != requiredKeys) {
            return invalid("Champs manquants ou non autorises")
        }
        val decision = json.opt("decision") as? String
            ?: return invalid("decision doit etre une chaine")
        val reason = json.opt("reason") as? String
            ?: return invalid("reason doit etre une chaine")
        val confidence = json.opt("confidence") as? String
            ?: return invalid("confidence doit etre une chaine")
        val durationValue = json.opt("duration_minutes")
        if (durationValue !is Number ||
            !durationValue.toDouble().isFinite() ||
            durationValue.toDouble() != durationValue.toLong().toDouble() ||
            durationValue.toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        ) {
            return invalid("duration_minutes doit etre un entier")
        }
        val durationMinutes = durationValue.toInt()
        if (reason.isBlank() || reason.length > MAX_REASON_LENGTH) {
            return invalid("reason doit contenir 1 a $MAX_REASON_LENGTH caracteres")
        }
        if (confidence !in acceptedConfidence) {
            return invalid("confidence hors schema")
        }

        return when (decision) {
            "allow" -> {
                if (durationMinutes !in 1..MAX_DURATION_MINUTES) {
                    invalid("Duree allow hors bornes 1-$MAX_DURATION_MINUTES minutes")
                } else {
                    DecisionValidationResult(
                        disposition = UnlockDisposition.ALLOW,
                        durationMinutes = durationMinutes,
                        modelReason = reason,
                        confidence = confidence,
                        outputValid = true,
                        validationReason = "Autorisation validee par les regles deterministes",
                    )
                }
            }
            "deny" -> {
                if (durationMinutes != 0) {
                    invalid("Une decision deny doit avoir une duree nulle")
                } else {
                    DecisionValidationResult(
                        disposition = UnlockDisposition.DENY,
                        durationMinutes = 0,
                        modelReason = reason,
                        confidence = confidence,
                        outputValid = true,
                        validationReason = "Refus explicite du modele",
                    )
                }
            }
            else -> invalid("decision hors schema")
        }
    }

    fun invalid(reason: String): DecisionValidationResult = DecisionValidationResult(
        disposition = UnlockDisposition.DENY,
        outputValid = false,
        validationReason = reason,
    )

    private const val MAX_DURATION_MINUTES = 30
    private const val MAX_REASON_LENGTH = 240
}
