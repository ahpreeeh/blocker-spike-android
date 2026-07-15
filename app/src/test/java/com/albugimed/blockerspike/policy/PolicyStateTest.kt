package com.albugimed.blockerspike.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyStateTest {
    private val pkg = "com.example.social"

    @Test
    fun selectedPackageIsBlockedByDefault() {
        val state = PolicyState(blockedPackages = setOf(pkg))

        assertTrue(state.shouldBlock(pkg, nowMillis = 1_000L))
    }

    @Test
    fun temporaryAllowanceStopsBlockingUntilExactExpiration() {
        val state = PolicyState(
            blockedPackages = setOf(pkg),
            allowedUntil = mapOf(pkg to 2_000L),
        )

        assertFalse(state.shouldBlock(pkg, nowMillis = 1_999L))
        assertTrue(state.shouldBlock(pkg, nowMillis = 2_000L))
    }

    @Test
    fun failsafeAlwaysDisablesBlocking() {
        val state = PolicyState(
            blockedPackages = setOf(pkg),
            failsafeOverride = true,
        )

        assertFalse(state.shouldBlock(pkg, nowMillis = Long.MAX_VALUE))
    }

    @Test
    fun unselectedPackageIsNeverBlocked() {
        val state = PolicyState(blockedPackages = setOf(pkg))

        assertFalse(state.shouldBlock("com.example.allowed", nowMillis = 1_000L))
    }
}
