package com.albugimed.blockerspike.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PathCommandJsonTest {

    @Test
    fun `new subject command has no activity kind`() {
        val command = PathCommand.PourSubject("cmd_1", "nod_cardio")

        val encoded = PathCommandJson.encode(command)

        assertFalse(encoded.has("kind"))
        assertEquals(command, PathCommandJson.decode(encoded.toString()))
    }

    @Test
    fun `legacy subject command still decodes and drops its old kind`() {
        val decoded = PathCommandJson.decode(
            """{"command_id":"cmd_1","type":"pour_subject","node_id":"nod_cardio","kind":"revision"}""",
        )

        assertEquals(PathCommand.PourSubject("cmd_1", "nod_cardio"), decoded)
        assertFalse(JSONObject(PathCommandJson.encodeToString(decoded!!)).has("kind"))
    }

    @Test
    fun `complete command requires a real boolean`() {
        assertNull(
            PathCommandJson.decode(
                """{"command_id":"cmd_1","type":"complete_step","step_id":"stp_1","completed_at":"2026-08-13T09:00:00+02:00"}""",
            ),
        )
        assertNull(
            PathCommandJson.decode(
                """{"command_id":"cmd_1","type":"complete_step","step_id":"stp_1","completed_at":"2026-08-13T09:00:00+02:00","completed":"false"}""",
            ),
        )
        assertEquals(
            false,
            (
                PathCommandJson.decode(
                    """{"command_id":"cmd_1","type":"complete_step","step_id":"stp_1","completed_at":"2026-08-13T09:00:00+02:00","completed":false}""",
                ) as PathCommand.CompleteStep
                ).completed,
        )
    }
}
