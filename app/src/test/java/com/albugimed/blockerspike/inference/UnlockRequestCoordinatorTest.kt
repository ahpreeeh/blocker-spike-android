package com.albugimed.blockerspike.inference

import com.albugimed.blockerspike.policy.PolicyState
import com.albugimed.blockerspike.policy.TimeSource
import com.albugimed.blockerspike.policy.UnlockPolicyGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockRequestCoordinatorTest {
    private val packageName = "com.example.social"
    private val allowJson =
        """{"decision":"allow","duration_minutes":2,"reason":"Besoin ponctuel","confidence":"high"}"""
    private val denyJson =
        """{"decision":"deny","duration_minutes":0,"reason":"Motif insuffisant","confidence":"high"}"""
    private val noOpLogger = UnlockEventLogger { _, _, _ -> }

    @Test
    fun engineFailureNeverWrites() = runTest {
        val store = FakePolicyStore(blockedState())
        val coordinator = coordinator(store) {
            Result.failure(IllegalStateException("moteur absent"))
        }

        val result = coordinator.request(packageName, "Besoin pour repondre a un message")

        assertEquals(UnlockRequestStatus.ERROR, result.status)
        assertEquals(0, store.writeAttempts)
    }

    @Test
    fun invalidOutputAndDenyNeverWrite() = runTest {
        val invalidStore = FakePolicyStore(blockedState())
        val invalid = coordinator(invalidStore) { generation("texte libre") }
            .request(packageName, "Justification")
        assertEquals(UnlockRequestStatus.DENIED, invalid.status)
        assertEquals(0, invalidStore.writeAttempts)

        val denyStore = FakePolicyStore(blockedState())
        val deny = coordinator(denyStore) { generation(denyJson) }
            .request(packageName, "Justification")
        assertEquals(UnlockRequestStatus.DENIED, deny.status)
        assertEquals(0, denyStore.writeAttempts)
    }

    @Test
    fun validAllowWritesExactlyOnce() = runTest {
        val store = FakePolicyStore(blockedState())
        val result = coordinator(store) { generation(allowJson) }
            .request(packageName, "Besoin pour repondre a un message")

        assertEquals(UnlockRequestStatus.ALLOWED, result.status)
        assertEquals(1, store.writeAttempts)
        assertEquals(120_000L, store.lastDurationMillis)
    }

    @Test
    fun policyChangedDuringGenerationNeverWrites() = runTest {
        val store = FakePolicyStore(blockedState())
        val result = coordinator(store) {
            store.state.value = store.state.value.copy(failsafeOverride = true)
            generation(allowJson)
        }.request(packageName, "Besoin pour repondre a un message")

        assertEquals(UnlockRequestStatus.DENIED, result.status)
        assertEquals(0, store.writeAttempts)
    }

    @Test
    fun atomicWriteRejectionBecomesError() = runTest {
        val store = FakePolicyStore(blockedState(), allowWrites = false)
        val result = coordinator(store) { generation(allowJson) }
            .request(packageName, "Besoin pour repondre a un message")

        assertEquals(UnlockRequestStatus.ERROR, result.status)
        assertEquals(1, store.writeAttempts)
    }

    @Test
    fun missingExactAlarmFailsBeforeInferenceAndNeverWrites() = runTest {
        val store = FakePolicyStore(blockedState())
        var inferenceCalls = 0
        val result = coordinator(
            store = store,
            exactExpiryAvailable = { false },
        ) {
            inferenceCalls++
            generation(allowJson)
        }.request(packageName, "Besoin ponctuel")

        assertEquals(UnlockRequestStatus.ERROR, result.status)
        assertEquals(0, inferenceCalls)
        assertEquals(0, store.writeAttempts)
    }

    @Test
    fun exactAlarmRevokedDuringGenerationNeverWrites() = runTest {
        val store = FakePolicyStore(blockedState())
        var exactExpiryAvailable = true
        val result = coordinator(
            store = store,
            exactExpiryAvailable = { exactExpiryAvailable },
        ) {
            exactExpiryAvailable = false
            generation(allowJson)
        }.request(packageName, "Besoin ponctuel")

        assertEquals(UnlockRequestStatus.ERROR, result.status)
        assertEquals(0, store.writeAttempts)
    }

    @Test
    fun failedAndroidUnsuspensionRollsBackGrant() = runTest {
        val store = FakePolicyStore(blockedState())
        val confirmations = mutableListOf<Boolean>()
        val result = coordinator(
            store = store,
            policyEnforcer = PolicyEnforcer { _, expectedSuspended ->
                confirmations += expectedSuspended
                expectedSuspended
            },
        ) { generation(allowJson) }
            .request(packageName, "Besoin ponctuel")

        assertEquals(UnlockRequestStatus.ERROR, result.status)
        assertEquals(listOf(false, true), confirmations)
        assertEquals(1, store.revokeAttempts)
        assertEquals(null, store.state.value.allowedUntil[packageName])
    }

    private fun coordinator(
        store: FakePolicyStore,
        exactExpiryAvailable: () -> Boolean = { true },
        policyEnforcer: PolicyEnforcer = PolicyEnforcer { _, _ -> true },
        reply: suspend (String) -> Result<InferenceResult>,
    ) = UnlockRequestCoordinator(
        repository = store,
        inference = object : LocalInferenceGateway {
            override suspend fun generate(prompt: String): Result<InferenceResult> = reply(prompt)
        },
        timeSource = TimeSource { 1_000L },
        eventLogger = noOpLogger,
        exactExpiryAvailable = exactExpiryAvailable,
        policyEnforcer = policyEnforcer,
    )

    private fun blockedState() = PolicyState(blockedPackages = setOf(packageName))

    private fun generation(raw: String) = Result.success(
        InferenceResult(
            rawText = raw,
            backend = "test",
            loadDurationMillis = 1L,
            generationDurationMillis = 2L,
            peakPssKb = 3L,
        )
    )

    private class FakePolicyStore(
        initial: PolicyState,
        private val allowWrites: Boolean = true,
    ) : UnlockPolicyGateway {
        val state = MutableStateFlow(initial)
        override val policy: Flow<PolicyState> = state
        var writeAttempts = 0
        var revokeAttempts = 0
        var lastDurationMillis: Long? = null

        override suspend fun grantTemporaryAllowanceIfStillBlocked(
            packageName: String,
            durationMillis: Long,
        ): Boolean {
            writeAttempts++
            lastDurationMillis = durationMillis
            if (!allowWrites || !state.value.shouldBlock(packageName, 1_000L)) return false
            state.value = state.value.copy(
                allowedUntil = state.value.allowedUntil + (packageName to 121_000L)
            )
            return true
        }

        override suspend fun revokeTemporaryAllowance(packageName: String): Boolean {
            revokeAttempts++
            state.value = state.value.copy(
                allowedUntil = state.value.allowedUntil - packageName,
            )
            return true
        }
    }
}
