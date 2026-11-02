package art.rodriguezrodriguez.hermesos

import art.rodriguezrodriguez.hermesos.core.model.PluginCatalogItemUi
import art.rodriguezrodriguez.hermesos.core.model.PluginSettingFieldUi
import art.rodriguezrodriguez.hermesos.core.model.PluginUpdateOutcome
import art.rodriguezrodriguez.hermesos.core.network.EncryptedCookieStore
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.plugins.PluginSettingsCodec
import art.rodriguezrodriguez.hermesos.core.plugins.PluginSettingsValidationException
import art.rodriguezrodriguez.hermesos.core.rpc.ManagedPluginRpcInfo
import art.rodriguezrodriguez.hermesos.data.repository.HermesOsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsAndConsentTest {

    private val sampleSchema = listOf(
        PluginSettingFieldUi(
            key = "host",
            type = "string",
            label = "Host",
            required = true,
            currentValue = "127.0.0.1"
        ),
        PluginSettingFieldUi(
            key = "port",
            type = "number",
            label = "Port",
            required = true,
            currentValue = "8123"
        ),
        PluginSettingFieldUi(
            key = "threshold",
            type = "number",
            label = "Threshold",
            required = false,
            defaultValue = "0.75"
        ),
        PluginSettingFieldUi(
            key = "auto_sync",
            type = "boolean",
            label = "Auto Sync",
            required = false,
            currentValue = "true"
        ),
        PluginSettingFieldUi(
            key = "mode",
            type = "enum",
            label = "Mode",
            required = true,
            choices = listOf("fast", "balanced", "strict"),
            currentValue = "balanced"
        ),
        PluginSettingFieldUi(
            key = "extra_headers",
            type = "json",
            label = "Extra Headers",
            required = false,
            currentValue = """{"X-Client":"hermes"}"""
        ),
        PluginSettingFieldUi(
            key = "api_token",
            type = "secret",
            label = "API Token",
            required = true,
            envKey = "HA_LONG_LIVED_TOKEN",
            hasSecretValue = true
        )
    )

    @Test
    fun `PluginSettingsCodec serializes strict Python types and isolates secrets`() {
        val drafts = PluginSettingsCodec.initialDrafts(sampleSchema).toMutableMap()
        assertEquals("", drafts["api_token"]) // never pre-populate secret drafts
        drafts["port"] = "9119"
        drafts["threshold"] = "0.85"
        drafts["auto_sync"] = "false"
        drafts["mode"] = "strict"
        drafts["extra_headers"] = """["alpha","beta"]"""
        drafts["api_token"] = "eyJ_real_new_ha_token"

        val plan = PluginSettingsCodec.buildPayloads(sampleSchema, drafts).getOrThrow()
        assertEquals("127.0.0.1", (plan.configValues["host"] as JsonPrimitive).content)
        assertEquals(9119L, (plan.configValues["port"] as JsonPrimitive).long)
        assertEquals(0.85, (plan.configValues["threshold"] as JsonPrimitive).double, 0.0001)
        assertFalse((plan.configValues["auto_sync"] as JsonPrimitive).boolean)
        assertEquals("strict", (plan.configValues["mode"] as JsonPrimitive).content)
        assertTrue(plan.configValues["extra_headers"] is JsonArray)
        // Secret MUST NOT appear in configValues (`plugins_settings.py` forbids secrets)
        assertFalse("api_token" in plan.configValues)
        assertEquals(mapOf("HA_LONG_LIVED_TOKEN" to "eyJ_real_new_ha_token"), plan.secretEnvUpdates)
    }

    @Test
    fun `PluginSettingsCodec blocks redacted secret previews and validates bad numbers enums and json`() {
        val redactedDrafts = PluginSettingsCodec.initialDrafts(sampleSchema).toMutableMap()
        redactedDrafts["api_token"] = "«redacted:eyJ_...9999»"
        val plan = PluginSettingsCodec.buildPayloads(sampleSchema, redactedDrafts).getOrThrow()
        assertTrue(plan.secretEnvUpdates.isEmpty())

        val badNumDrafts = PluginSettingsCodec.initialDrafts(sampleSchema).toMutableMap().apply {
            this["port"] = "not-a-number"
        }
        val numErr = PluginSettingsCodec.buildPayloads(sampleSchema, badNumDrafts).exceptionOrNull()
        assertEquals("port", (numErr as? PluginSettingsValidationException)?.fieldKey)

        val badEnumDrafts = PluginSettingsCodec.initialDrafts(sampleSchema).toMutableMap().apply {
            this["mode"] = "invalid_choice"
        }
        val enumErr = PluginSettingsCodec.buildPayloads(sampleSchema, badEnumDrafts).exceptionOrNull()
        assertEquals("mode", (enumErr as? PluginSettingsValidationException)?.fieldKey)

        // JSON primitive (e.g. "123" or "\"str\"") must be rejected because Python requires dict or list
        val badJsonDrafts = PluginSettingsCodec.initialDrafts(sampleSchema).toMutableMap().apply {
            this["extra_headers"] = "\"just_a_string\""
        }
        val jsonErr = PluginSettingsCodec.buildPayloads(sampleSchema, badJsonDrafts).exceptionOrNull()
        assertEquals("extra_headers", (jsonErr as? PluginSettingsValidationException)?.fieldKey)

        // Missing required secret when hasSecretValue == false must fail
        val unsetRequiredSecretSchema = listOf(
            PluginSettingFieldUi(
                key = "api_token",
                type = "secret",
                label = "API Token",
                required = true,
                envKey = "NEW_PLUGIN_KEY",
                hasSecretValue = false
            )
        )
        val secretErr = PluginSettingsCodec.buildPayloads(unsetRequiredSecretSchema, mapOf("api_token" to "")).exceptionOrNull()
        assertEquals("api_token", (secretErr as? PluginSettingsValidationException)?.fieldKey)
    }

    @Test
    fun `updatePlugin maps consent_required, caution scan_blocked, dangerous scan_blocked, and applied`() = runTest {
        val server = MockWebServer()
        // 1. consent_required at HTTP 200
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":false,"name":"hermes-mobile-wall","sha":"abcdef123456","consent_required":true,"delta_lines":["+ mcp_server: wall-bridge","+ env: WALL_TOKEN"]}"""
            )
        )
        // 2. scan_blocked with caution verdict at HTTP 200 -> canForce == true
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":false,"name":"hermes-mobile-wall","scan_blocked":true,"scan_verdict":"caution","scan_findings":[{"severity":"medium","category":"subprocess","file":"plugin.py","line":42,"description":"Calls subprocess.run"}]}"""
            )
        )
        // 3. scan_blocked with dangerous verdict -> canForce == false
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":false,"name":"evil-plugin","scan_blocked":true,"scan_verdict":"dangerous","scan_findings":[{"severity":"critical","category":"exfil","file":"init.py","line":9,"description":"Exfiltrates ~/.hermes/.env"}]}"""
            )
        )
        // 4. Applied
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"name":"hermes-mobile-wall","sha":"9876543210fedcba","unchanged":false,"restart_required":true}"""
            )
        )
        server.start()
        try {
            val client = HermesRestClient(EncryptedCookieStore(null)).apply {
                activeBaseUrl = server.url("/").toString().trimEnd('/')
            }

            val consent = client.updatePlugin("default", "hermes-mobile-wall")
            assertTrue(consent is PluginUpdateOutcome.ConsentRequired)
            consent as PluginUpdateOutcome.ConsentRequired
            assertEquals(2, consent.deltaLines.size)

            val caution = client.updatePlugin("default", "hermes-mobile-wall", acceptCapabilities = true)
            assertTrue(caution is PluginUpdateOutcome.ScanBlocked)
            caution as PluginUpdateOutcome.ScanBlocked
            assertTrue(caution.canForce)
            assertEquals(42, caution.findings.single().line)

            val dangerous = client.updatePlugin("default", "evil/plugin", acceptCapabilities = true, force = true)
            assertTrue(dangerous is PluginUpdateOutcome.ScanBlocked)
            dangerous as PluginUpdateOutcome.ScanBlocked
            assertFalse(dangerous.canForce)
            // Verify {name:path} encoding preserved `/`
            server.takeRequest()
            server.takeRequest()
            val thirdReq = server.takeRequest()
            assertEquals("/api/dashboard/agent-plugins/evil/plugin/update?profile=default", thirdReq.path)

            val applied = client.updatePlugin("default", "hermes-mobile-wall", acceptCapabilities = true, force = true)
            assertTrue(applied is PluginUpdateOutcome.Applied)
            applied as PluginUpdateOutcome.Applied
            assertTrue(applied.restartRequired)
            assertEquals("9876543210fedcba", applied.sha)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mergePluginCatalogWithRpc enriches catalog items with profile-scoped key, schema, and bundled guard`() {
        val restItems = listOf(
            PluginCatalogItemUi(
                name = "meshy-3d",
                description = "Meshy 3D generator",
                installed = true,
                enabled = false,
                catalogName = "meshy-3d"
            )
        )
        val rpcItems = listOf(
            ManagedPluginRpcInfo(
                name = "meshy-dir",
                key = "image_gen/meshy",
                catalogName = "meshy-3d",
                description = "Meshy 3D generator",
                source = "https://github.com/example/meshy",
                status = "enabled",
                installedSha = "1234567890abcdef",
                catalogTier = "verified",
                updateAvailable = true,
                canRemove = true,
                canUpdate = true,
                settingsSchema = listOf(
                    PluginSettingFieldUi(key = "quality", type = "enum", label = "Quality", choices = listOf("draft", "high"))
                )
            ),
            ManagedPluginRpcInfo(
                name = "memory-builtin",
                key = "memory/builtin",
                catalogName = "",
                description = "Bundled memory plugin",
                source = "bundled",
                status = "enabled",
                installedSha = "",
                catalogTier = "official",
                updateAvailable = false,
                canRemove = false,
                canUpdate = false,
                settingsSchema = emptyList()
            )
        )

        val merged = HermesOsRepository.mergePluginCatalogWithRpc(restItems, rpcItems)
        assertEquals(2, merged.size)

        val meshy = merged.first { it.catalogName == "meshy-3d" }
        assertTrue(meshy.enabled)
        assertEquals("meshy-dir", meshy.installedName)
        assertEquals("image_gen/meshy", meshy.pluginKey)
        assertEquals(1, meshy.settingsSchema.size)
        assertTrue(meshy.canRemove)

        val bundled = merged.first { it.pluginKey == "memory/builtin" }
        assertFalse(bundled.canRemove)
        assertFalse(bundled.canUpdate)
    }
}
