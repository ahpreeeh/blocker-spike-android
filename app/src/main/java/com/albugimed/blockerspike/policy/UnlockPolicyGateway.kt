package com.albugimed.blockerspike.policy

import kotlinx.coroutines.flow.Flow

/** Narrow policy boundary used by the T3 coordinator and its JVM tests. */
interface UnlockPolicyGateway {
    val policy: Flow<PolicyState>

    /** Atomically rechecks the policy and writes only if the package is still blocked. */
    suspend fun grantTemporaryAllowanceIfStillBlocked(
        packageName: String,
        durationMillis: Long,
    ): Boolean

    /** Removes a grant if system enforcement cannot apply it safely. */
    suspend fun revokeTemporaryAllowance(packageName: String): Boolean
}
