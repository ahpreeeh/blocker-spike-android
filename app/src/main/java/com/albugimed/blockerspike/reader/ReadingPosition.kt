package com.albugimed.blockerspike.reader

import org.json.JSONObject

/**
 * Où l'on en est dans un document, **sur cet appareil et nulle part ailleurs**.
 *
 * C'est l'invariant de l'amendement V2.2, et il tient en une phrase :
 *
 * > Le lecteur connaît la page ; le serveur ne l'apprend qu'au moment où tu
 * > la déclares.
 *
 * Un lecteur interne connaît la page en permanence. En faire un flux continu
 * vers le serveur transformerait `activity_events` en journal de
 * surveillance, ce que le §8 du contrat exclut — *« aucune statistique
 * d'usage »*. Cette classe ne traverse donc jamais le réseau : elle n'a ni
 * encodeur de contrat, ni place dans `SyncTransport`.
 */
data class ReadingPosition(
    /** La ressource du référentiel à laquelle le document est rattaché. */
    val resourceId: String,
    /**
     * L'URI du document local, obtenue par le sélecteur système avec une
     * permission persistante. Elle peut devenir invalide — fichier supprimé,
     * carte retirée — et l'écran doit alors le dire, pas planter.
     */
    val documentUri: String,
    /** Nom lisible au moment du rattachement, pour l'afficher sans rouvrir. */
    val documentLabel: String,
    /** Page **affichée**, à partir de 1. `PdfRenderer` compte à partir de 0. */
    val page: Int,
    /**
     * Page à laquelle la dernière déclaration s'est arrêtée, ou `null` si
     * rien n'a encore été déclaré pour ce document. Sert à pré-remplir le
     * « de » d'une déclaration : on reprend là où le compte s'était arrêté.
     */
    val declaredThrough: Int? = null,
    val pageCount: Int = 0,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(page >= 1) { "la page affichée commence à 1" }
    }
}

/**
 * Ce que la déclaration propose après une séance de lecture.
 *
 * `from` reprend juste après la dernière page déjà déclarée : déclarer
 * « 47 → 62 » puis lire jusqu'à 67 doit proposer « 63 → 67 », et non
 * « 47 → 67 » qui recompterait quinze pages.
 *
 * Renvoie `null` quand il n'y a rien d'honnête à proposer — un lecteur
 * ouvert sans avoir avancé, par exemple. Un formulaire pré-rempli avec une
 * plage vide vaut moins qu'un formulaire vide : il donne l'impression d'un
 * fait établi.
 */
fun suggestedPageRange(position: ReadingPosition): IntRange? {
    val start = position.declaredThrough?.plus(1) ?: 1
    if (start > position.page) return null
    return start..position.page
}

/**
 * Sérialisation locale. Volontairement séparée de `StudyEventJson` et de
 * `CaptureJson` : ces deux-là décrivent un contrat réseau, celle-ci décrit
 * un fichier privé. Les mélanger rendrait facile d'envoyer par erreur ce qui
 * ne doit pas partir.
 */
internal object ReadingPositionJson {
    fun encode(position: ReadingPosition): String = JSONObject()
        .put("resource_id", position.resourceId)
        .put("document_uri", position.documentUri)
        .put("document_label", position.documentLabel)
        .put("page", position.page)
        .put("page_count", position.pageCount)
        .put("updated_at", position.updatedAtMillis)
        .apply { position.declaredThrough?.let { put("declared_through", it) } }
        .toString()

    fun decode(serialized: String): ReadingPosition? {
        val json = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
        val resourceId = json.optString("resource_id").takeIf { it.isNotBlank() } ?: return null
        val documentUri = json.optString("document_uri").takeIf { it.isNotBlank() } ?: return null
        // Une entrée abîmée est ignorée, jamais réparée au jugé : une page
        // inventée renverrait au mauvais endroit sans le dire.
        val page = json.optInt("page", 0).takeIf { it >= 1 } ?: return null

        return ReadingPosition(
            resourceId = resourceId,
            documentUri = documentUri,
            documentLabel = json.optString("document_label"),
            page = page,
            declaredThrough = json.optInt("declared_through", 0).takeIf { it >= 1 },
            pageCount = json.optInt("page_count", 0).coerceAtLeast(0),
            updatedAtMillis = json.optLong("updated_at", 0L),
        )
    }
}
