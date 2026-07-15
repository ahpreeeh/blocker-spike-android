package com.albugimed.blockerspike.policy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
            PolicyState(
                blockedPackages = prefs[Keys.BLOCKED] ?: emptySet(),
                allowedUntil = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                    .mapNotNull { entry ->
                        val parts = entry.split('|', limit = 2)
                        if (parts.size != 2) return@mapNotNull null
                        parts[1].toLongOrNull()?.let { parts[0] to it }
                    }
                    .toMap(),
                failsafeOverride = prefs[Keys.FAILSAFE_OVERRIDE] ?: false,
                variantB = prefs[Keys.VARIANT_B] ?: false,
            )
        }
        .catch { emit(PolicyState(storageHealthy = false)) }

    suspend fun addBlockedPackage(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        context.blockPolicyStore.edit { prefs ->
            prefs[Keys.BLOCKED] = (prefs[Keys.BLOCKED] ?: emptySet()) + pkg
        }
    }

    suspend fun removeBlockedPackage(packageName: String) {
        context.blockPolicyStore.edit { prefs ->
            prefs[Keys.BLOCKED] = (prefs[Keys.BLOCKED] ?: emptySet()) - packageName
            prefs[Keys.ALLOWED_UNTIL] = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$packageName|") }
                .toSet()
        }
    }

    /** Une autorisation possède toujours une expiration (protocole §5). */
    suspend fun grantTemporaryAllowance(packageName: String, durationMillis: Long) {
        val until = timeSource.nowMillis() + durationMillis
        context.blockPolicyStore.edit { prefs ->
            val others = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$packageName|") }
            prefs[Keys.ALLOWED_UNTIL] = (others + "$packageName|$until").toSet()
        }
    }

    suspend fun revokeAllowance(packageName: String) {
        context.blockPolicyStore.edit { prefs ->
            prefs[Keys.ALLOWED_UNTIL] = (prefs[Keys.ALLOWED_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$packageName|") }
                .toSet()
        }
    }

    /** Ne dépend ni du modèle ni du réseau (protocole §5). */
    suspend fun setFailsafeOverride(enabled: Boolean) {
        context.blockPolicyStore.edit { prefs -> prefs[Keys.FAILSAFE_OVERRIDE] = enabled }
    }

    suspend fun setVariantB(enabled: Boolean) {
        context.blockPolicyStore.edit { prefs -> prefs[Keys.VARIANT_B] = enabled }
    }
}
