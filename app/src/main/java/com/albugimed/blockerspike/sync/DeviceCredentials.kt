package com.albugimed.blockerspike.sync

import java.net.URI

/**
 * De quoi parler au serveur — contrat §2.
 *
 * `deviceId` n'est pas saisi : il est **appris** à la première réponse du
 * serveur, qui sait à quel appareil le jeton présenté appartient. C'est
 * aussi ce qui rend l'enrôlement honnête — tant qu'aucune requête n'a
 * abouti, rien n'est enregistré, et un jeton mal recopié se voit tout de
 * suite, pendant qu'il est encore affiché à l'écran.
 */
data class DeviceCredentials(
    val baseUrl: String,
    val token: String,
    val deviceId: String? = null,
)

/**
 * Ramène une saisie à une base d'URL utilisable, ou `null`.
 *
 * **HTTPS uniquement** : le trafic en clair est refusé par Android 16 et
 * n'est pas contourné (contrat §2). Le chemin, la requête et le fragment
 * sont écartés — le jeton part dans un en-tête, jamais dans une URL, et une
 * base porteuse d'une chaîne de requête est le premier pas vers l'inverse.
 */
fun normalizeBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null

    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "https://$host$port"
}

/** Le jeton tel qu'il est présenté au serveur, débarrassé des aides à la saisie. */
fun normalizeDeviceToken(raw: String): String? {
    val compact = raw.trim().replace(Regex("[\\s\\-.]+"), "")
    return compact.takeIf { it.length in 8..512 }
}
