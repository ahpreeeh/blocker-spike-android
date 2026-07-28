package com.albugimed.blockerspike.admin

import android.os.PersistableBundle
import com.albugimed.blockerspike.policy.PolicyState

object OwnershipTransferPayload {
    private const val FORMAT_VERSION = 1
    private const val KEY_FORMAT_VERSION = "format_version"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_ALLOWED_UNTIL = "allowed_until"
    private const val KEY_FAILSAFE_OVERRIDE = "failsafe_override"
    private const val KEY_VARIANT_B = "variant_b"

    fun encode(policy: PolicyState): PersistableBundle = PersistableBundle().apply {
        putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
        putStringArray(KEY_BLOCKED_PACKAGES, policy.blockedPackages.sorted().toTypedArray())
        putStringArray(
            KEY_ALLOWED_UNTIL,
            policy.allowedUntil
                .toSortedMap()
                .map { (packageName, until) -> "$packageName|$until" }
                .toTypedArray(),
        )
        putBoolean(KEY_FAILSAFE_OVERRIDE, policy.failsafeOverride)
        putBoolean(KEY_VARIANT_B, policy.variantB)
    }

    fun decode(bundle: PersistableBundle?): PolicyState? {
        if (bundle == null || bundle.getInt(KEY_FORMAT_VERSION) != FORMAT_VERSION) return null
        val blocked = bundle.getStringArray(KEY_BLOCKED_PACKAGES)
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?: return null
        val encodedAllowances = bundle.getStringArray(KEY_ALLOWED_UNTIL).orEmpty()
        val allowances = encodedAllowances.mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            parts[1].toLongOrNull()?.let { parts[0] to it }
        }.toMap()
        if (allowances.size != encodedAllowances.size) return null
        return PolicyState(
            blockedPackages = blocked,
            allowedUntil = allowances,
            failsafeOverride = bundle.getBoolean(KEY_FAILSAFE_OVERRIDE),
            variantB = bundle.getBoolean(KEY_VARIANT_B),
        )
    }
}
