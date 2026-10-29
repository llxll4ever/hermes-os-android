package art.rodriguezrodriguez.hermesos

import art.rodriguezrodriguez.hermesos.core.model.BotLiveState
import art.rodriguezrodriguez.hermesos.core.model.BotProfileCard
import art.rodriguezrodriguez.hermesos.data.repository.BotWallMerge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotWallMergeTest {

    private fun wall(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `remote roster enriches local cards without dropping live state`() {
        val current = listOf(
            BotProfileCard(
                profile = "default",
                handle = "hermes",
                displayName = "Hermes",
                roleSummary = "local",
                liveState = BotLiveState.THINKING,
                currentActivity = "Pensando"
            )
        )
        val remote = listOf(
            BotProfileCard(
                profile = "default",
                handle = "hermes",
                displayName = "Hermes Principal",
                roleSummary = "remoto",
                activeModel = "gpt-5",
                activeProvider = "openai"
            )
        )

        val merged = BotWallMerge.merge(current, remote, null)

        assertEquals(1, merged.size)
        assertEquals("Hermes Principal", merged[0].displayName)
        assertEquals("remoto", merged[0].roleSummary)
        assertEquals("gpt-5", merged[0].activeModel)
        assertEquals("openai", merged[0].activeProvider)
        // El estado en vivo local no debe perderse al mergear el roster.
        assertEquals(BotLiveState.THINKING, merged[0].liveState)
        assertEquals("Pensando", merged[0].currentActivity)
    }

    @Test
    fun `wall telemetry overrides live state and activity`() {
        val merged = BotWallMerge.merge(
            current = emptyList(),
            remoteProfiles = emptyList(),
            wallBots = wall(
                """{"domo":{"state":"RUNNING_TOOL","activity":"Encendiendo luces","tool_name":"home_assistant"}}"""
            )
        )

        assertEquals(1, merged.size)
        val domo = merged.single()
        assertEquals("domo", domo.profile)
        assertEquals("domo", domo.handle)
        assertEquals(BotLiveState.RUNNING_TOOL, domo.liveState)
        assertEquals("Encendiendo luces", domo.currentActivity)
        assertEquals("home_assistant", domo.activeToolName)
    }

    @Test
    fun `default and orchestrator stay at the top`() {
        val current = listOf(
            BotProfileCard("redactor", "redactor", "Redactor", ""),
            BotProfileCard("orchestrator", "orchestrator", "Orchestrator", ""),
            BotProfileCard("default", "hermes", "Hermes", "")
        )

        val merged = BotWallMerge.merge(current, emptyList(), null)

        // El orden es estable: default y orchestrator siempre preceden al resto,
        // y entre ellos se respeta el orden previo.
        assertEquals(listOf("orchestrator", "default", "redactor"), merged.map { it.profile })
        assertTrue(merged.dropLast(1).none { it.profile == "redactor" })
    }

    @Test
    fun `unknown live state falls back to previous state`() {
        val current = listOf(
            BotProfileCard("programador", "programador", "Programador", "", liveState = BotLiveState.SPEAKING)
        )

        val merged = BotWallMerge.merge(
            current = current,
            remoteProfiles = emptyList(),
            wallBots = wall("""{"programador":{"state":"NOT_A_STATE","activity":"Compilando"}}""")
        )

        val bot = merged.single()
        assertEquals(BotLiveState.SPEAKING, bot.liveState)
        assertEquals("Compilando", bot.currentActivity)
        assertTrue(bot.activeToolName == null)
    }
}