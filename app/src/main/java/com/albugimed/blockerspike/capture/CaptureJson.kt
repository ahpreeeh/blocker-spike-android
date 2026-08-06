package com.albugimed.blockerspike.capture

import org.json.JSONArray
import org.json.JSONObject

/**
 * L'encodage du §16 du contrat, et rien d'autre.
 *
 * Ce fichier est séparé de `StudyEventJson` volontairement : les deux
 * formats se ressemblent aujourd'hui, et c'est exactement pourquoi ils
 * doivent pouvoir diverger sans qu'un changement de l'un abîme l'autre.
 */
object CaptureJson {

    /** Contrat §16 : au plus 50 captures par requête. */
    const val MAX_PER_BATCH = 50

    fun encode(capture: Capture): JSONObject {
        val raw = JSONObject().put("text", capture.text)
        capture.subject?.let { raw.put("subject", it) }

        // `capture.sourcePackage` n'est PAS encodé, et ce n'est pas un oubli.
        // Le §8 range les noms de paquets parmi ce qui ne traverse jamais ce
        // contrat. Savoir d'où vient un partage serait confortable dans la
        // corbeille ; ça ne vaut pas d'entamer l'invariant qui justifie la
        // permission `INTERNET`. Le champ reste sur l'appareil.

        return JSONObject()
            .put("capture_id", capture.captureId)
            .put("kind", capture.kind)
            .put("captured_at", formatCapturedAt(capture.capturedAtMillis))
            .put("raw", raw)
    }

    fun encodeBatch(deviceId: String, captures: List<Capture>): String {
        val array = JSONArray()
        captures.forEach { array.put(encode(it)) }
        return JSONObject()
            .put("device_id", deviceId)
            .put("captures", array)
            .toString()
    }

    /**
     * Les verdicts, **un par capture**. Une réponse illisible ne vaut pas un
     * refus : l'appelant garde tout et réessaiera. Le rejeu porte le même
     * `capture_id`, il ne peut rien dupliquer.
     */
    fun decodeResults(body: String): List<CaptureResult>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val array = root.optJSONArray("results") ?: return null

        val results = ArrayList<CaptureResult>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val captureId = item.optString("capture_id").takeIf { it.isNotBlank() } ?: continue
            results.add(
                CaptureResult(
                    captureId = captureId,
                    status = item.optString("status"),
                    reason = item.optString("reason").takeIf { it.isNotBlank() },
                )
            )
        }
        return results
    }
}
