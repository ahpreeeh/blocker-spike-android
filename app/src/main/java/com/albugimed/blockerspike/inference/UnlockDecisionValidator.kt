package com.albugimed.blockerspike.inference

import com.albugimed.blockerspike.policy.PolicyState

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
            StrictFlatJsonParser.parse(text)
        } catch (_: Exception) {
            return invalid("JSON invalide")
        }

        if (json.keys != requiredKeys) {
            return invalid("Champs manquants ou non autorises")
        }
        val decision = (json["decision"] as? StrictFlatJsonParser.StringValue)?.value
            ?: return invalid("decision doit etre une chaine")
        val reason = (json["reason"] as? StrictFlatJsonParser.StringValue)?.value
            ?: return invalid("reason doit etre une chaine")
        val confidence = (json["confidence"] as? StrictFlatJsonParser.StringValue)?.value
            ?: return invalid("confidence doit etre une chaine")
        val durationToken =
            (json["duration_minutes"] as? StrictFlatJsonParser.NumberValue)?.token
                ?: return invalid("duration_minutes doit etre un entier")
        if (!STRICT_INTEGER.matches(durationToken)) {
            return invalid("duration_minutes doit etre un entier")
        }
        val durationMinutes = durationToken.toIntOrNull()
            ?: return invalid("duration_minutes doit etre un entier")
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
    private val STRICT_INTEGER = Regex("-?(0|[1-9][0-9]*)")
}
