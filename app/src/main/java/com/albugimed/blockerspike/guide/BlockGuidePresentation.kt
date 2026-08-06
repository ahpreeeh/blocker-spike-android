package com.albugimed.blockerspike.guide

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val importDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

fun blockGuidePreview(body: String, maximumCharacters: Int = 700): String {
    require(maximumCharacters > 0)
    val visible = body.trimStart()
    if (visible.isEmpty()) return "(corps vide)"
    return if (visible.length <= maximumCharacters) visible
    else visible.take(maximumCharacters).trimEnd() + "…"
}

fun formatGuideImportDate(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(importDateFormat)

fun GuideValidationIssue.displayText(): String = buildString {
    if (line != null) append("Ligne $line — ")
    append(message)
}
