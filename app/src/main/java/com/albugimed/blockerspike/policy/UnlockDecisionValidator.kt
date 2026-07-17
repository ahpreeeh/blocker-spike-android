package com.albugimed.blockerspike.policy

import org.json.JSONObject

/**
 * Moteur déterministe du parcours de déblocage (protocole T3 §4–§5).
 * L'IA ne produit qu'un texte ; seule cette validation peut conduire à une
 * écriture d'autorisation. Toute sortie hors schéma ou hors bornes => rejet,
 * donc retour à l'état bloqué (fail-closed).
 */
object UnlockDecisionValidator {

    const val MAX_DURATION_MINUTES = 30

    sealed interface Outcome {
        /** Sortie conforme, décision allow validée : durée à écrire par le moteur T2. */
        data class Granted(val durationMinutes: Int, val reason: String) : Outcome

        /** Sortie conforme, le modèle refuse : aucun état modifié. */
        data class Denied(val reason: String) : Outcome

        /** Sortie non conforme ou contexte interdit : refus par le validateur. */
        data class Rejected(val why: String) : Outcome
    }

    private val ALLOWED_KEYS = setOf("decision", "duration_minutes", "reason", "confidence")
    private val CONFIDENCES = setOf("high", "medium", "low")

    fun validate(rawOutput: String, packageName: String, policy: PolicyState): Outcome {
        if (!policy.storageHealthy) {
            return Outcome.Rejected("stockage illisible : aucune écriture d'autorisation")
        }
        if (policy.failsafeOverride) {
            return Outcome.Rejected("override de panne actif : rien à accorder")
        }
        if (packageName !in policy.blockedPackages) {
            return Outcome.Rejected("package non bloqué : $packageName")
        }

        val trimmed = rawOutput.trim()
        // JSON pur exigé : un code fence ou de la prose autour => refus (protocole §5).
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Outcome.Rejected("sortie non limitée à un objet JSON")
        }
        val json = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return Outcome.Rejected("JSON invalide")
        }

        val keys = json.keys().asSequence().toSet()
        val unknown = keys - ALLOWED_KEYS
        if (unknown.isNotEmpty()) {
            return Outcome.Rejected("clés hors schéma : $unknown")
        }

        val reason = json.optString("reason", "").trim()
        if (reason.isEmpty()) return Outcome.Rejected("champ reason manquant ou vide")

        val confidence = json.optString("confidence", "")
        if (confidence !in CONFIDENCES) {
            return Outcome.Rejected("confidence invalide : « $confidence »")
        }

        return when (json.optString("decision", "")) {
            "deny" -> Outcome.Denied(reason)
            "allow" -> {
                val duration = (json.opt("duration_minutes") as? Number)?.toInt()
                when {
                    duration == null -> Outcome.Rejected("duration_minutes absent ou non numérique")
                    duration < 1 || duration > MAX_DURATION_MINUTES ->
                        Outcome.Rejected("durée hors bornes : $duration")
                    else -> Outcome.Granted(duration, reason)
                }
            }
            else -> Outcome.Rejected("decision invalide")
        }
    }
}
