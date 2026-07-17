package com.albugimed.blockerspike.policy

import com.albugimed.blockerspike.policy.UnlockDecisionValidator.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockDecisionValidatorTest {

    private val pkg = "com.example.social"
    private val policy = PolicyState(blockedPackages = setOf(pkg))

    private fun validate(raw: String, p: PolicyState = policy) =
        UnlockDecisionValidator.validate(raw, pkg, p)

    // --- Sorties conformes ---

    @Test
    fun validAllowIsGranted() {
        val out = validate(
            """{"decision":"allow","duration_minutes":15,"reason":"pause légitime","confidence":"medium"}"""
        )
        assertEquals(Outcome.Granted(15, "pause légitime"), out)
    }

    @Test
    fun validDenyChangesNothing() {
        val out = validate(
            """{"decision":"deny","duration_minutes":0,"reason":"séance en cours","confidence":"high"}"""
        )
        assertEquals(Outcome.Denied("séance en cours"), out)
    }

    @Test
    fun allowAtExactUpperBoundIsGranted() {
        val out = validate(
            """{"decision":"allow","duration_minutes":30,"reason":"ok","confidence":"low"}"""
        )
        assertEquals(Outcome.Granted(30, "ok"), out)
    }

    // --- Bornes et types ---

    @Test
    fun durationAboveBoundIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":45,"reason":"longue pause","confidence":"high"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun durationZeroIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":0,"reason":"x","confidence":"high"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun missingDurationOnAllowIsRejected() {
        val out = validate("""{"decision":"allow","reason":"x","confidence":"high"}""")
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun stringDurationIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":"15","reason":"x","confidence":"high"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    // --- Forme de la sortie (cas piégés S4) ---

    @Test
    fun markdownFencedJsonIsRejected() {
        val out = validate(
            "```json\n{\"decision\":\"allow\",\"duration_minutes\":10,\"reason\":\"x\",\"confidence\":\"high\"}\n```"
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun proseAroundJsonIsRejected() {
        val out = validate(
            "Voici ma décision : {\"decision\":\"allow\",\"duration_minutes\":10,\"reason\":\"x\",\"confidence\":\"high\"} bonne journée"
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun freeTextIsRejected() {
        assertTrue(validate("Je pense qu'il faut autoriser 10 minutes.") is Outcome.Rejected)
    }

    @Test
    fun unknownKeyIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":10,"reason":"x","confidence":"high","note":"extra"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun invalidDecisionValueIsRejected() {
        val out = validate(
            """{"decision":"maybe","duration_minutes":10,"reason":"x","confidence":"high"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun invalidConfidenceIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":10,"reason":"x","confidence":"sure"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun emptyReasonIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":10,"reason":"  ","confidence":"high"}"""
        )
        assertTrue(out is Outcome.Rejected)
    }

    // --- Contexte de politique (le validateur reste souverain) ---

    @Test
    fun unblockedPackageIsRejectedEvenIfOutputValid() {
        val out = UnlockDecisionValidator.validate(
            """{"decision":"allow","duration_minutes":10,"reason":"x","confidence":"high"}""",
            "com.example.notblocked",
            policy,
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun activeFailsafeOverrideIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":10,"reason":"x","confidence":"high"}""",
            policy.copy(failsafeOverride = true),
        )
        assertTrue(out is Outcome.Rejected)
    }

    @Test
    fun unhealthyStorageIsRejected() {
        val out = validate(
            """{"decision":"allow","duration_minutes":10,"reason":"x","confidence":"high"}""",
            PolicyState(storageHealthy = false),
        )
        assertTrue(out is Outcome.Rejected)
    }
}
