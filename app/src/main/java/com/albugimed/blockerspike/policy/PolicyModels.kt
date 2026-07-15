package com.albugimed.blockerspike.policy

/** Horloge injectée pour rendre l'expiration testable (cf. protocole §3). */
fun interface TimeSource {
    fun nowMillis(): Long
}

val SystemTimeSource: TimeSource = TimeSource { System.currentTimeMillis() }

/**
 * État déterministe du bloqueur. Aucune logique IA ici : l'IA future passera
 * par le moteur déterministe pour écrire une autorisation structurée.
 */
data class PolicyState(
    val blockedPackages: Set<String> = emptySet(),
    /** package -> timestamp epoch millis d'expiration de l'autorisation temporaire. */
    val allowedUntil: Map<String, Long> = emptyMap(),
    val failsafeOverride: Boolean = false,
    /** false = T2-A (notification), true = T2-B (écran de blocage immédiat). */
    val variantB: Boolean = false,
    /** false si le stockage est illisible : diagnostic affiché, override toujours possible. */
    val storageHealthy: Boolean = true,
) {
    /**
     * Modèle d'état du protocole §5 :
     * blocked -> allowed_until(timestamp) -> expiration -> blocked,
     * l'override de panne court-circuitant tout blocage.
     */
    fun shouldBlock(packageName: String, nowMillis: Long): Boolean {
        if (failsafeOverride) return false
        if (packageName !in blockedPackages) return false
        val until = allowedUntil[packageName] ?: return true
        return nowMillis >= until
    }
}
