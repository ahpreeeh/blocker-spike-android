package com.albugimed.blockerspike.inference

import com.albugimed.blockerspike.policy.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockDecisionValidatorTest {
    private val packageName = "com.example.social"
    private val blockedPolicy = PolicyState(blockedPackages = setOf(packageName))

    @Test
    fun validAllowProducesBoundedGrant() {
        val result = validate(
            """{"decision":"allow","duration_minutes":2,"reason":"Besoin ponctuel","confidence":"high"}"""
        )

        assertTrue(result.outputValid)
        assertEquals(UnlockDisposition.ALLOW, result.disposition)
        assertEquals(120_000L, result.grantDurationMillis)
    }

    @Test
    fun validDenyNeverProducesGrant() {
        val result = validate(
            """{"decision":"deny","duration_minutes":0,"reason":"Motif insuffisant","confidence":"medium"}"""
        )

        assertTrue(result.outputValid)
        assertEquals(UnlockDisposition.DENY, result.disposition)
        assertNull(result.grantDurationMillis)
    }

    @Test
    fun markdownAroundJsonFailsClosed() {
        val result = validate(
            """```json
                {"decision":"allow","duration_minutes":2,"reason":"Test","confidence":"high"}
                ```""".trimIndent()
        )

        assertInvalidDeny(result)
    }

    @Test
    fun missingOrExtraFieldsFailClosed() {
        assertInvalidDeny(
            validate("""{"decision":"deny","duration_minutes":0,"reason":"Non"}""")
        )
        assertInvalidDeny(
            validate(
                """{"decision":"deny","duration_minutes":0,"reason":"Non","confidence":"high","extra":true}"""
            )
        )
    }

    @Test
    fun invalidDurationsFailClosed() {
        listOf(-1, 0, 31).forEach { duration ->
            assertInvalidDeny(
                validate(
                    """{"decision":"allow","duration_minutes":$duration,"reason":"Test","confidence":"high"}"""
                )
            )
        }
        assertInvalidDeny(
            validate(
                """{"decision":"allow","duration_minutes":2.5,"reason":"Test","confidence":"high"}"""
            )
        )
        assertInvalidDeny(
            validate(
                """{"decision":"deny","duration_minutes":2,"reason":"Non","confidence":"high"}"""
            )
        )
    }

    @Test
    fun policyContextRemainsSovereign() {
        val allowJson =
            """{"decision":"allow","duration_minutes":2,"reason":"Test","confidence":"high"}"""

        assertInvalidDeny(
            UnlockDecisionValidator.validate(
                allowJson,
                packageName,
                PolicyState(blockedPackages = emptySet()),
                nowMillis = 1_000L,
            )
        )
        assertInvalidDeny(
            UnlockDecisionValidator.validate(
                allowJson,
                packageName,
                blockedPolicy.copy(failsafeOverride = true),
                nowMillis = 1_000L,
            )
        )
        assertInvalidDeny(
            UnlockDecisionValidator.validate(
                allowJson,
                packageName,
                blockedPolicy.copy(allowedUntil = mapOf(packageName to 2_000L)),
                nowMillis = 1_000L,
            )
        )
    }

    @Test
    fun unknownDecisionOrConfidenceFailsClosed() {
        assertInvalidDeny(
            validate(
                """{"decision":"maybe","duration_minutes":0,"reason":"Test","confidence":"high"}"""
            )
        )
        assertInvalidDeny(
            validate(
                """{"decision":"deny","duration_minutes":0,"reason":"Test","confidence":"certain"}"""
            )
        )
    }

    private fun validate(raw: String): DecisionValidationResult =
        UnlockDecisionValidator.validate(
            rawOutput = raw,
            packageName = packageName,
            policy = blockedPolicy,
            nowMillis = 1_000L,
        )

    private fun assertInvalidDeny(result: DecisionValidationResult) {
        assertFalse(result.outputValid)
        assertEquals(UnlockDisposition.DENY, result.disposition)
        assertNull(result.grantDurationMillis)
    }
}
