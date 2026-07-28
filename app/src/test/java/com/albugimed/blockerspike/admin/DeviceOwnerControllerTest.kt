package com.albugimed.blockerspike.admin

import com.albugimed.blockerspike.policy.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOwnerControllerTest {
    private val target = "com.example.social"

    @Test
    fun blockedTargetIsSuspended() {
        val result = desiredSuspensions(
            previouslyManaged = emptySet(),
            currentTargets = setOf(target),
            policy = PolicyState(blockedPackages = setOf(target)),
            nowMillis = 1_000L,
        )

        assertEquals(mapOf(target to true), result)
    }

    @Test
    fun allowanceUnsuspendsUntilItsExactExpiry() {
        val policy = PolicyState(
            blockedPackages = setOf(target),
            allowedUntil = mapOf(target to 2_000L),
        )

        assertEquals(
            mapOf(target to false),
            desiredSuspensions(emptySet(), setOf(target), policy, nowMillis = 1_999L),
        )
        assertEquals(
            mapOf(target to true),
            desiredSuspensions(emptySet(), setOf(target), policy, nowMillis = 2_000L),
        )
    }

    @Test
    fun failsafeUnsuspendsEveryCurrentTarget() {
        val policy = PolicyState(
            blockedPackages = setOf(target),
            failsafeOverride = true,
        )

        assertEquals(
            mapOf(target to false),
            desiredSuspensions(emptySet(), setOf(target), policy, nowMillis = 1_000L),
        )
    }

    @Test
    fun removedPreviouslyManagedTargetIsUnsuspended() {
        val result = desiredSuspensions(
            previouslyManaged = setOf(target),
            currentTargets = emptySet(),
            policy = PolicyState(),
            nowMillis = 1_000L,
        )

        assertEquals(mapOf(target to false), result)
    }
}
