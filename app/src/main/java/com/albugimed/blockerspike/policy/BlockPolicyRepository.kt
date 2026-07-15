package com.albugimed.blockerspike.policy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.albugimed.blockerspike.log.InterceptionLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.blockPolicyStore by preferencesDataStore(name = "block_policy")

class BlockPolicyRepository(
    private val context: Context,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    private object Keys {
        val BLOCKED = stringSetPreferencesKey("blocked_packages")

        /** Entrées encodées "package|epochMillis". */
        val ALLOWED_UNTIL = stringSetPreferencesKey("allowed_until")
        val FAILSAFE_OVERRIDE = booleanPreferencesKey("failsafe_override")
        val VARIANT_B = booleanPreferencesKey("variant_b")
    }

    /**
     * Stockage illisible => fail-open (aucun blocage) + storageHealthy=false,
     * pour que le diagnostic l'affiche et que l'utilisateur ne soit jamais
     * enfermé dehors sans recours (protocole §5).
     */
    val policy: Flow<PolicyState> = context.blockPolicyStore.data
        .map { prefs ->
            val allowedEntries = prefs[Keys.ALLOWED_UNTIL] ?: emptySet()
            val allowedUntil = allowedEntries.mapNotNull { entry ->
                val parts = entry.split('|', limit = 2)
                if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
                parts[1].toLongOrNull()?.let { parts[0] to it }
            }.toMap()

            // Une entrée partiellement corrompue rend la politique ambiguë :
            // fail-open explicite plutôt que blocage avec un état incomplet.
            if (allowedUntil.size != allowedEntries.size) {
                return@map PolicyState(storageHealthy = false)
            }

            PolicyState(
                blockedPackages = prefs[Keys.BLOCKED] ?: emptySet(),
                allowedUntil = allowedUntil,
                failsafeOverride = prefs[Keys.FAILSAFE_OVERRIDE] ?: false,
                variantB = prefs[Keys.VARIANT_B] ?: false,
            )
        }
        .catch { error ->
            InterceptionLog.add(
                InterceptionLog.TAG_ERROR,
                "Lecture de la politique impossible : ${error.javaClass.simpleName}",
            )
            emit(PolicyState(storageHealthy = false))
        }

    suspend fun addBlockedPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        return mutate("Package bloqué ajouté : $pkg") { prefs ->
            prefs[Keys.BLOCKED] = (prefs[Keys.BLOCKED] ?: emptySet()) + pkg
        }
    }

    suspend fun removeBlockedPackage(packageName: String): Boolean =
        mutate("Package retiré : $packageName") { prefs ->
            prefs[Keys.BLOCKED] = (prefs[Keys.BLOCKED] ?: emptySet()) - packageName
            prefs[Keys.ALLOWED_UNTIL] = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$packageName|") }
                .toSet()
        }

    /** Une autorisation possède toujours une expiration (protocole §5). */
    suspend fun grantTemporaryAllowance(packageName: String, durationMillis: Long): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || durationMillis <= 0L) {
            InterceptionLog.add(
                InterceptionLog.TAG_ERROR,
                "Autorisation temporaire refusée : paramètres invalides",
                packageName = pkg.takeIf { it.isNotEmpty() },
            )
            return false
        }
        val now = timeSource.nowMillis()
        val until = if (Long.MAX_VALUE - now < durationMillis) Long.MAX_VALUE
        else now + durationMillis
        return mutate("Autorisation temporaire accordée jusqu'à $until", pkg) { prefs ->
            val others = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$pkg|") }
            prefs[Keys.ALLOWED_UNTIL] = (others + "$pkg|$until").toSet()
        }
    }

    suspend fun revokeAllowance(packageName: String): Boolean =
        mutate("Autorisation temporaire révoquée", packageName) { prefs ->
            prefs[Keys.ALLOWED_UNTIL] = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$packageName|") }
                .toSet()
        }

    /** Ne dépend ni du modèle ni du réseau (protocole §5). */
    suspend fun setFailsafeOverride(enabled: Boolean): Boolean =
        mutate("Override de panne ${if (enabled) "activé" else "désactivé"}") { prefs ->
            prefs[Keys.FAILSAFE_OVERRIDE] = enabled
        }

    suspend fun setVariantB(enabled: Boolean): Boolean =
        mutate("Variante ${if (enabled) "T2-B" else "T2-A"} sélectionnée") { prefs ->
            prefs[Keys.VARIANT_B] = enabled
        }

    private suspend fun mutate(
        successMessage: String,
        packageName: String? = null,
        transform: (MutablePreferences) -> Unit,
    ): Boolean {
        return try {
            context.blockPolicyStore.edit { prefs -> transform(prefs) }
            InterceptionLog.add(
                InterceptionLog.TAG_POLICY,
                successMessage,
                packageName = packageName,
            )
            true
        } catch (error: Exception) {
            InterceptionLog.add(
                InterceptionLog.TAG_ERROR,
                "Écriture de la politique impossible : ${error.javaClass.simpleName}",
                packageName = packageName,
            )
            false
        }
    }
}
