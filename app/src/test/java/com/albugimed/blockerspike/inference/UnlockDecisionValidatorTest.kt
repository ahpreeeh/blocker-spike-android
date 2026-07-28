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

    @Test
    fun exactThirtyMinuteUpperBoundIsAccepted() {
        val result = validate(
            """{"decision":"allow","duration_minutes":30,"reason":"Besoin borne","confidence":"low"}"""
        )

        assertTrue(result.outputValid)
        assertEquals(1_800_000L, result.grantDurationMillis)
    }

    @Test
    fun stringDurationFailsClosed() {
        assertInvalidDeny(
            validate(
                """{"decision":"allow","duration_minutes":"2","reason":"Test","confidence":"high"}"""
            )
        )
    }

    @Test
    fun proseOrFreeTextFailsClosed() {
        assertInvalidDeny(
            validate(
                """Decision: {"decision":"deny","duration_minutes":0,"reason":"Non","confidence":"high"}"""
            )
        )
        assertInvalidDeny(validate("Autorise deux minutes"))
    }

    @Test
    fun emptyOrOversizedReasonFailsClosed() {
        assertInvalidDeny(
            validate(
                """{"decision":"deny","duration_minutes":0,"reason":" ","confidence":"high"}"""
            )
        )
        val oversized = "x".repeat(241)
        assertInvalidDeny(
            validate(
                """{"decision":"deny","duration_minutes":0,"reason":"$oversized","confidence":"high"}"""
            )
        )
    }

    @Test
    fun permissiveJsonExtensionsFailClosed() {
        val invalidOutputs = listOf(
            "{'decision':'deny','duration_minutes':0,'reason':'Non','confidence':'high'}",
            """{decision:"deny","duration_minutes":0,"reason":"Non","confidence":"high"}""",
            """{"decision":"deny","duration_minutes":0,"reason":"Non","confidence":"high",}""",
            """{"decision":"deny","decision":"allow","duration_minutes":0,"reason":"Non","confidence":"high"}""",
            """{"decision":"deny","duration_minutes":00,"reason":"Non","confidence":"high"}""",
        )

        invalidOutputs.forEach { assertInvalidDeny(validate(it)) }
    }

    @Test
    fun strictJsonAcceptsReorderedKeysAndEscapes() {
        val result = validate(
            """{"reason":"Besoin \"ponctuel\"","confidence":"low","duration_minutes":2,"decision":"allow"}"""
        )

        assertTrue(result.outputValid)
        assertEquals(UnlockDisposition.ALLOW, result.disposition)
        assertEquals("Besoin \"ponctuel\"", result.modelReason)
    }

    @Test
    fun unhealthyStorageFailsClosed() {
        val result = UnlockDecisionValidator.validate(
            rawOutput =
                """{"decision":"allow","duration_minutes":2,"reason":"Test","confidence":"high"}""",
            packageName = packageName,
            policy = blockedPolicy.copy(storageHealthy = false),
            nowMillis = 1_000L,
        )

        assertInvalidDeny(result)
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
