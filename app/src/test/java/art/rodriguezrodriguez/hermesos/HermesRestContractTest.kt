package art.rodriguezrodriguez.hermesos

import art.rodriguezrodriguez.hermesos.core.model.ConnectionKind
import art.rodriguezrodriguez.hermesos.core.model.ConnectionRung
import art.rodriguezrodriguez.hermesos.core.model.LinkStatus
import art.rodriguezrodriguez.hermesos.core.model.McpServerEntry
import art.rodriguezrodriguez.hermesos.core.model.McpTransport
import art.rodriguezrodriguez.hermesos.core.network.CredentialGuards
import art.rodriguezrodriguez.hermesos.core.network.EncryptedCookieStore
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.network.bool
import art.rodriguezrodriguez.hermesos.core.network.str
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRestContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun newClient(server: MockWebServer): HermesRestClient {
        return HermesRestClient(EncryptedCookieStore(null)).apply {
            activeBaseUrl = server.url("/").toString().trimEnd('/')
        }
    }

    @Test
    fun `C1 saveRawConfigYaml sends yaml_text and profile`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"yaml":"model:\n  default: gpt-4.1\n","path":"/tmp/config.yaml"}"""))
        server.start()
        try {
            val client = newClient(server)
            val saved = client.saveRawConfigYaml("programador", "model:\n  default: gpt-4.1\n")
            assertTrue(saved)

            val putReq = server.takeRequest()
            assertEquals("PUT", putReq.method)
            assertEquals("/api/config/raw?profile=programador", putReq.path)
            val body = json.parseToJsonElement(putReq.body.readUtf8()) as JsonObject
            assertEquals("model:\n  default: gpt-4.1\n", body.str("yaml_text"))
            assertEquals("programador", body.str("profile"))
            assertNull(body["content"])

            val fetched = client.fetchRawConfigYaml("programador")
            assertEquals("model:\n  default: gpt-4.1\n", fetched)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `C2 and C3 fetchEnvVars parses metadata objects and updateEnvVars sends sequential single-key PUTs`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "GROQ_API_KEY": {
                    "is_set": true,
                    "redacted_value": "«redacted:gsk_...9xyz»",
                    "is_password": true,
                    "provider_label": "Groq Cloud"
                  },
                  "HERMES_LOG_LEVEL": {
                    "is_set": false,
                    "redacted_value": "",
                    "is_password": false,
                    "category": "Runtime"
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        try {
            val client = newClient(server)
            val vars = client.fetchEnvVars("default")
            assertEquals(2, vars.size)
            val groq = vars.first { it.key == "GROQ_API_KEY" }
            assertTrue(groq.isConfigured)
            assertTrue(groq.isSecret)
            assertEquals("«redacted:gsk_...9xyz»", groq.valuePreview)
            assertEquals("Groq Cloud", groq.providerGroup)

            val logVar = vars.first { it.key == "HERMES_LOG_LEVEL" }
            assertFalse(logVar.isConfigured)
            assertFalse(logVar.isSecret)
            assertEquals("Runtime", logVar.providerGroup)
            server.takeRequest() // consume GET

            val batch = client.updateEnvVarsDetailed(
                "programador",
                mapOf(
                    "GROQ_API_KEY" to "gsk_fresh_real_key_123456",
                    "OPENAI_API_KEY" to "«redacted:sk-1...2345»",
                    "CUSTOM_TOKEN" to "tok_fresh_789"
                )
            )
            assertTrue(batch.allSucceeded)
            assertEquals(listOf("GROQ_API_KEY", "CUSTOM_TOKEN"), batch.updatedKeys)
            assertEquals(2, server.requestCount - 1)

            val put1 = json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
            assertEquals("GROQ_API_KEY", put1.str("key"))
            assertEquals("gsk_fresh_real_key_123456", put1.str("value"))
            assertEquals("programador", put1.str("profile"))

            val put2 = json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
            assertEquals("CUSTOM_TOKEN", put2.str("key"))
            assertEquals("tok_fresh_789", put2.str("value"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `C4 and C5 fetchModelOptions uses canonical provider slug and model assignment preserves aggregator`() = runTest {
        val server = MockWebServer()
        // 1. /api/model/info for fetchModelOptions
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"model":"hermes-3-llama-3.1-70b","provider":"nous"}"""))
        // 2. /api/model/options with slug vs human name
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "providers": [
                    {
                      "slug": "nous",
                      "name": "Nous Portal",
                      "models": [{"id": "hermes-3-llama-3.1-70b", "label": "Hermes 3 70B"}]
                    },
                    {
                      "slug": "openrouter",
                      "name": "OpenRouter",
                      "models": ["anthropic/claude-sonnet-4"]
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        // 3. POST /api/model/set with explicit canonical provider
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        // 4. GET /api/model/info for blank-provider aggregator preservation on setProfileModel
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"model":"anthropic/claude-3.5-haiku","provider":"openrouter"}"""))
        // 5. PUT /api/profiles/domo/model
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        try {
            val client = newClient(server)
            val (current, options) = client.fetchModelOptions("default")
            assertEquals("hermes-3-llama-3.1-70b", current)
            assertEquals("nous", options.first { it.slug == "hermes-3-llama-3.1-70b" }.provider)
            assertEquals("openrouter", options.first { it.slug == "anthropic/claude-sonnet-4" }.provider)
            server.takeRequest()
            server.takeRequest()

            assertTrue(client.setGlobalModel("default", "hermes-3-llama-3.1-70b", "nous"))
            val setGlobalBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
            assertEquals("main", setGlobalBody.str("scope"))
            assertEquals("nous", setGlobalBody.str("provider"))
            assertEquals("hermes-3-llama-3.1-70b", setGlobalBody.str("model"))
            assertTrue(setGlobalBody.bool("confirm_expensive_model"))

            // Blank provider queries /api/model/info?profile=domo and preserves "openrouter" instead of "auto" or "anthropic"
            assertTrue(client.setProfileModel("domo", "anthropic/claude-sonnet-4", provider = null))
            val infoReq = server.takeRequest()
            assertEquals("/api/model/info?profile=domo", infoReq.path)
            val profilePutReq = server.takeRequest()
            assertEquals("PUT", profilePutReq.method)
            assertEquals("/api/profiles/domo/model", profilePutReq.path)
            val profileBody = json.parseToJsonElement(profilePutReq.body.readUtf8()) as JsonObject
            assertEquals("openrouter", profileBody.str("provider"))
            assertEquals("anthropic/claude-sonnet-4", profileBody.str("model"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `C6 custom endpoints parse preview and is_current, enforce model, and omit redacted api_key on update`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"endpoints":[{"id":"ep:omni/1","name":"OmniRoute","base_url":"http://127.0.0.1:20128/v1","model":"omni/auto","api_key_preview":"«redacted:sk-...1234»","is_current":true}]}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        try {
            val client = newClient(server)
            val list = client.fetchCustomEndpoints("default")
            assertEquals(1, list.size)
            assertEquals("«redacted:sk-...1234»", list[0].apiKeyPreview)
            assertTrue(list[0].active)
            server.takeRequest()

            // Blank defaultModel is rejected locally without network roundtrip
            assertFalse(client.addCustomEndpoint("default", "Bad", "http://127.0.0.1:11434/v1", "", "   "))

            assertTrue(
                client.updateCustomEndpoint(
                    profile = "default",
                    endpointId = "ep:omni/1",
                    name = "OmniRoute",
                    baseUrl = "http://127.0.0.1:20128/v1",
                    defaultModel = "omni/pro",
                    apiKey = "«redacted:sk-...1234»"
                )
            )
            val putReq = server.takeRequest()
            assertEquals("PUT", putReq.method)
            assertEquals("/api/providers/custom-endpoints/ep%3Aomni%2F1?profile=default", putReq.path)
            val putBody = json.parseToJsonElement(putReq.body.readUtf8()) as JsonObject
            assertEquals("omni/pro", putBody.str("model"))
            assertNull(putBody["api_key"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `C7 and C8 MCP catalog reads entries and auth_type, and MCP 409 overwrite guards plugin-owned servers`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "entries": [
                    {
                      "id": "notion",
                      "label": "Notion Official",
                      "description": "Notion workspace",
                      "transport": "http",
                      "auth_type": "oauth",
                      "installed": false,
                      "needs_install": true,
                      "required_env": [{"env": "NOTION_TOKEN", "label": "Token", "required": true, "is_set": false}]
                    }
                  ],
                  "diagnostics": []
                }
                """.trimIndent()
            )
        )
        // Non-plugin 409 -> DELETE -> POST succeeds
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Server 'ctx7' already exists"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        // Plugin-owned 409 -> must NOT delete
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Server 'plug:mcp' is provided by plugin"}"""))
        server.start()
        try {
            val client = newClient(server)
            val cat = client.fetchMcpCatalog("default")
            assertEquals(1, cat.size)
            assertEquals("Notion Official", cat[0].title)
            assertTrue(cat[0].requiresOauth)
            assertTrue(cat[0].needsInstall)
            assertEquals("NOTION_TOKEN", cat[0].requiredEnv.single().env)
            server.takeRequest()

            val entry = McpServerEntry(
                name = "ctx7",
                transport = McpTransport.HTTP,
                commandOrUrl = "https://mcp.context7.com/mcp"
            )
            assertTrue(client.addOrUpdateMcpServer("programador", entry))
            val post1 = server.takeRequest()
            val post1Body = json.parseToJsonElement(post1.body.readUtf8()) as JsonObject
            assertEquals("programador", post1Body.str("profile"))
            val delReq = server.takeRequest()
            assertEquals("DELETE", delReq.method)
            assertEquals("/api/mcp/servers/ctx7?profile=programador", delReq.path)
            val post2 = server.takeRequest()
            assertEquals("POST", post2.method)

            val pluginEntry = McpServerEntry("plug:mcp", McpTransport.STDIO, "npx")
            assertFalse(client.addOrUpdateMcpServer("programador", pluginEntry))
            val pluginPost = server.takeRequest()
            assertEquals("POST", pluginPost.method)
            assertEquals(0, server.requestCount - 5) // zero DELETE sent for plugin-owned MCP!
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `C9 plugin catalog prioritizes hub runtime_status and decodes JsonNull and JsonObject settings`() = runTest {
        val server = MockWebServer()
        // Unscoped catalog says disabled, profile-scoped hub says enabled
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[{"name":"hermes-mobile-wall","description":"Wall","runtime_status":"disabled","installed":true}]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "plugins": [
                    {
                      "name": "mobile-wall-dir",
                      "key": "hermes-mobile-wall",
                      "catalog_name": "hermes-mobile-wall",
                      "runtime_status": "enabled",
                      "can_remove": true,
                      "can_update_git": true,
                      "settings_schema": [
                        {"key": "optional_note", "type": "string", "label": "Note", "required": false, "value": null, "default": null},
                        {"key": "headers", "type": "json", "label": "Headers", "required": false, "value": {"X-Test": "1"}, "default": ["a"]}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val client = newClient(server)
            val items = client.fetchPluginStoreCatalog("domo")
            val wall = items.single()
            assertTrue(wall.enabled)
            assertEquals("mobile-wall-dir", wall.installedName)
            assertEquals("hermes-mobile-wall", wall.pluginKey)
            assertEquals("", wall.settingsSchema[0].currentValue)
            assertEquals("", wall.settingsSchema[0].defaultValue)
            assertEquals("""{"X-Test":"1"}""", wall.settingsSchema[1].currentValue)
            assertEquals("""["a"]""", wall.settingsSchema[1].defaultValue)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `C11 C12 C13 diagnostics probe, loopback 401 detection, and RFC 3986 helpers`() = runTest {
        assertEquals("tok_abc_123", CredentialGuards.extractTokenFromUrlOrRaw("http://127.0.0.1:9119/?token=tok_abc_123&next=%2F"))
        assertEquals("raw_tok_999", CredentialGuards.extractTokenFromUrlOrRaw("  raw_tok_999 "))
        assertEquals("cat/my%20plugin", CredentialGuards.encodeMultiSegmentPath("cat/my plugin"))
        assertEquals(
            "ws://127.0.0.1:9119/api/ws?token=sec%20tok",
            CredentialGuards.buildAuthenticatedWsUrl(
                baseUrl = "http://127.0.0.1:9119",
                path = "/api/ws",
                authRequired = false,
                sessionToken = "http://127.0.0.1:9119/?token=sec%20tok"
            )
        )

        val server = MockWebServer()
        // Loopback health (auth_required = false) followed by 401 on /api/profiles
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"version":"0.22.5","auth_required":false}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid session token"}"""))
        // probeEndpoint on POST /api/auth/ws-ticket with authRequired=true
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"t_1"}"""))
        // probeEndpoint on /api/plugins/hermes-mobile-wall/wall returning 404
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        server.start()
        try {
            val client = newClient(server)
            client.loopbackSessionToken = "http://127.0.0.1:9119/?token=loop_tok_77"
            val snap = client.resolveConnectionLadder(
                listOf(ConnectionRung("loop", "Loopback", server.url("/").toString(), ConnectionKind.CUSTOM))
            )
            assertEquals(LinkStatus.AUTH_REQUIRED, snap.status)
            assertFalse(snap.authRequired)
            server.takeRequest() // health
            val profProbe = server.takeRequest()
            assertEquals("loop_tok_77", profProbe.getHeader("X-Hermes-Session-Token"))

            val skipped = client.probeEndpoint("POST", "/api/auth/ws-ticket", authRequired = false)
            assertEquals(0, skipped.code)
            assertNotNull(skipped.note)

            val wsTicketProbe = client.probeEndpoint("POST", "/api/auth/ws-ticket", authRequired = true)
            assertEquals(200, wsTicketProbe.code)
            val ticketReq = server.takeRequest()
            assertEquals("POST", ticketReq.method)
            assertEquals("{}", ticketReq.body.readUtf8())
            assertEquals("loop_tok_77", ticketReq.getHeader("X-Hermes-Session-Token"))

            val wallProbe = client.probeEndpoint("GET", "/api/plugins/hermes-mobile-wall/wall", authRequired = true)
            assertEquals(404, wallProbe.code)
            assertNotNull(wallProbe.note)
        } finally {
            server.shutdown()
        }
    }
}
