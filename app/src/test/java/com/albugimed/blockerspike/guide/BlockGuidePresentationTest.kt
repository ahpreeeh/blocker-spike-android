package com.albugimed.blockerspike.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BlockGuidePresentationTest {
    @Test
    fun emptyAndLongBodiesHaveUnambiguousPreviews() {
        assertEquals("(corps vide)", blockGuidePreview("\n  "))

        val preview = blockGuidePreview("\n" + "x".repeat(20), maximumCharacters = 8)
        assertEquals("xxxxxxxx…", preview)
    }

    @Test
    fun reportLineIsShownWhenKnown() {
        val issue = GuideValidationIssue(
            GuideIssueCode.DUPLICATE_KEY,
            "Cle dupliquee.",
            line = 7,
        )

        assertEquals("Ligne 7 — Cle dupliquee.", issue.displayText())
    }

    @Test
    fun importDateUsesExplicitPresentationZone() {
        val rendered = formatGuideImportDate(0L, ZoneId.of("UTC"))

        assertTrue(rendered.startsWith("01/01/1970"))
    }
}
