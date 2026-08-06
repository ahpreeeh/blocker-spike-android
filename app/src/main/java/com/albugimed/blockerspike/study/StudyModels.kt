package com.albugimed.blockerspike.study

import com.albugimed.blockerspike.sync.ActivityUnit
import com.albugimed.blockerspike.sync.AcademicNodeKind
import com.albugimed.blockerspike.sync.AcademicNodeRef
import com.albugimed.blockerspike.sync.ActivityKind
import com.albugimed.blockerspike.sync.DeadEvent
import com.albugimed.blockerspike.sync.Difficulty
import com.albugimed.blockerspike.sync.QueueItem
import com.albugimed.blockerspike.sync.StudyEvent
import com.albugimed.blockerspike.sync.StudyEventType
import com.albugimed.blockerspike.sync.formatOccurredAt
import java.time.OffsetDateTime

/** Projection d'écran des états fournis par les magasins du lot sync. */
data class StudyQueueState(
    val cachedAtMillis: Long? = null,
    /** L'ordre reçu est conservé tel quel : le premier élément reste le premier. */
    val items: List<QueueItem> = emptyList(),
    val skippedQueueItems: Int = 0,
    /** Référentiel descendant, dans l'ordre reçu. */
    val nodes: List<AcademicNodeRef> = emptyList(),
    val skippedQueueNodes: Int = 0,
    val pendingCount: Int = 0,
    val rejectedEvents: List<DeadEvent> = emptyList(),
    val unreadableCount: Int = 0,
    val outboxStorageHealthy: Boolean = true,
)

enum class WorkUnitType {
    PAGES,
    CHAPTER,
    ANNALE,
    CARDS,
    FREE,
}

/** Données de formulaire avant génération de l'identifiant par la file locale. */
data class ActivityDeclaration(
    val nodeId: String,
    val occurredAt: OffsetDateTime,
    val durationMinutes: Int,
    val unit: ActivityUnit,
    val difficulty: Difficulty,
    val note: String?,
    val stepId: String? = null,
    val resourceId: String? = null,
    /** Seulement hors file ; avec une étape, le référentiel serveur fait foi. */
    val activityKind: ActivityKind? = null,
)

data class DeclareFormState(
    val durationMinutes: String = "",
    val unitType: WorkUnitType = WorkUnitType.PAGES,
    val pagesFrom: String = "",
    val pagesTo: String = "",
    val annaleLabel: String = "",
    val cardsCount: String = "",
    val freeLabel: String = "",
    val difficulty: Difficulty? = null,
    val note: String = "",
)

sealed interface DeclarationBuildResult {
    data class Valid(val declaration: ActivityDeclaration) : DeclarationBuildResult

    data class Invalid(val errors: List<String>) : DeclarationBuildResult
}

fun buildActivityDeclaration(
    item: QueueItem,
    form: DeclareFormState,
    occurredAt: OffsetDateTime,
): DeclarationBuildResult = buildActivityDeclarationForTarget(
    target = DeclarationTarget(
        nodeId = item.chapter?.nodeId ?: item.subject.nodeId,
        stepId = item.stepId,
        resourceId = item.resource?.resourceId,
        activityKind = null,
    ),
    targetErrors = emptyList(),
    form = form,
    occurredAt = occurredAt,
)

/** Déclaration hors file : un vrai chapitre et un type sont obligatoires. */
fun buildFreeActivityDeclaration(
    chapter: AcademicNodeRef?,
    activityKind: ActivityKind?,
    form: DeclareFormState,
    occurredAt: OffsetDateTime,
): DeclarationBuildResult {
    val errors = mutableListOf<String>()
    if (chapter == null || chapter.kind != AcademicNodeKind.CHAPTER) {
        errors += "Choisis un chapitre."
    }
    if (activityKind == null) {
        errors += "Choisis un type de travail."
    }
    val target = if (
        chapter != null &&
        chapter.kind == AcademicNodeKind.CHAPTER &&
        activityKind != null
    ) {
        DeclarationTarget(
            nodeId = chapter.nodeId,
            stepId = null,
            resourceId = null,
            activityKind = activityKind,
        )
    } else {
        null
    }
    return buildActivityDeclarationForTarget(
        target = target,
        targetErrors = errors,
        form = form,
        occurredAt = occurredAt,
    )
}

private data class DeclarationTarget(
    val nodeId: String,
    val stepId: String?,
    val resourceId: String?,
    val activityKind: ActivityKind?,
)

private fun buildActivityDeclarationForTarget(
    target: DeclarationTarget?,
    targetErrors: List<String>,
    form: DeclareFormState,
    occurredAt: OffsetDateTime,
): DeclarationBuildResult {
    val errors = targetErrors.toMutableList()
    val duration = form.durationMinutes.trim().toIntOrNull()
    if (duration == null || duration <= 0) {
        errors += "Indique une durée positive en minutes."
    }

    val unit = when (form.unitType) {
        WorkUnitType.PAGES -> {
            val from = form.pagesFrom.trim().toIntOrNull()
            val to = form.pagesTo.trim().toIntOrNull()
            when {
                from == null || to == null || from <= 0 || to <= 0 -> {
                    errors += "Indique les pages de début et de fin."
                    null
                }

                to < from -> {
                    errors += "La page de fin doit suivre la page de début."
                    null
                }

                else -> ActivityUnit.Pages(from = from, to = to)
            }
        }

        WorkUnitType.CHAPTER -> ActivityUnit.Chapter
        WorkUnitType.ANNALE -> form.annaleLabel.trim().takeIf(String::isNotEmpty)
            ?.let { ActivityUnit.Annale(it) }
            ?: run {
                errors += "Indique l'annale travaillée."
                null
            }

        WorkUnitType.CARDS -> form.cardsCount.trim().toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { ActivityUnit.Cards(it) }
            ?: run {
                errors += "Indique un nombre de cartes positif."
                null
            }

        WorkUnitType.FREE -> form.freeLabel.trim().takeIf(String::isNotEmpty)
            ?.let { ActivityUnit.Free(it) }
            ?: run {
                errors += "Décris l'unité travaillée."
                null
            }
    }

    val difficulty = form.difficulty
    if (difficulty == null) {
        errors += "Choisis une difficulté."
    }
    if (form.note.length > MAX_NOTE_LENGTH) {
        errors += "La note ne peut pas dépasser $MAX_NOTE_LENGTH caractères."
    }

    if (
        errors.isNotEmpty() ||
        target == null ||
        duration == null ||
        unit == null ||
        difficulty == null
    ) {
        return DeclarationBuildResult.Invalid(errors)
    }

    return DeclarationBuildResult.Valid(
        ActivityDeclaration(
            nodeId = target.nodeId,
            occurredAt = occurredAt,
            durationMinutes = duration,
            unit = unit,
            difficulty = difficulty,
            note = form.note.trim().ifEmpty { null },
            stepId = target.stepId,
            resourceId = target.resourceId,
            activityKind = target.activityKind,
        ),
    )
}

/** Conversion pure vers le contrat réel de D1, testée sans Android. */
fun ActivityDeclaration.toStudyEvent(eventId: String): StudyEvent = StudyEvent(
    eventId = eventId,
    type = StudyEventType.ACTIVITY_RECORDED,
    occurredAt = formatOccurredAt(
        epochMillis = occurredAt.toInstant().toEpochMilli(),
        zone = occurredAt.offset,
    ),
    nodeId = nodeId,
    stepId = stepId,
    resourceId = resourceId,
    activityKind = activityKind,
    durationMinutes = durationMinutes,
    unit = unit,
    difficulty = difficulty,
    note = note,
)

const val MAX_NOTE_LENGTH = 500
const val NOTE_COUNTER_THRESHOLD = 400
