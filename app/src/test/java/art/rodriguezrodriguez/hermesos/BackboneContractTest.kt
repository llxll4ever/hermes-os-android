package art.rodriguezrodriguez.hermesos

import art.rodriguezrodriguez.hermesos.core.model.ConnectionKind
import art.rodriguezrodriguez.hermesos.core.model.ConnectionRung
import art.rodriguezrodriguez.hermesos.core.model.LinkStatus
import art.rodriguezrodriguez.hermesos.core.model.WorkspacePreset
import art.rodriguezrodriguez.hermesos.core.model.defaultWidgetsForPreset
import art.rodriguezrodriguez.hermesos.core.network.CredentialGuards
import art.rodriguezrodriguez.hermesos.core.network.EncryptedCookieStore
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.rpc.HermesRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackboneContractTest {

    @Test
    fun `credential redaction guard blocks masked dashboard previews`() {
        assertTrue(CredentialGuards.isRedactedPreview("«redacted:sk-1...9abc»"))
        assertTrue(CredentialGuards.isRedactedPreview("«redacted-vault-secret»"))
        assertTrue(CredentialGuards.isRedactedPreview("sk-1...9abc"))
        assertTrue(CredentialGuards.isRedactedPreview("***"))
        assertFalse(CredentialGuards.isRedactedPreview("sk-live-real-new-secret-value-12345"))

        val filtered = CredentialGuards.filterSafeEnvUpdates(
            mapOf(
                "OPENAI_API_KEY" to "«redacted:sk-1...9abc»",
                "GROQ_API_KEY" to "gsk_real_key_value_999",
                "EMPTY_KEY" to "   "
            )
        )
        assertEquals(mapOf("GROQ_API_KEY" to "gsk_real_key_value_999"), filtered)
    }

    @Test
    fun `ws ticket subprotocols and url builders match hermes web_server_boundary`() {
        assertEquals(
            "wss://hermes.rodriguezrodriguez.art/api/ws",
            CredentialGuards.buildGatewayWebSocketUrl("https://hermes.rodriguezrodriguez.art/")
        )
        assertEquals(
            "ws://100.103.166.20:9119/api/ws",
            CredentialGuards.buildGatewayWebSocketUrl("http://100.103.166.20:9119")
        )
        assertEquals(
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.tkt_123"),
            CredentialGuards.buildTicketSubprotocols("tkt_123")
        )
        assertEquals(
            "/api/mcp/servers?profile=programador",
            CredentialGuards.appendProfileParam("/api/mcp/servers", "programador")
        )
    }

    @Test
    fun `connection ladder falls back from unreachable tailscale rung to healthy cloudflare rung`() = runTest {
        val healthyServer = MockWebServer()
        healthyServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"version":"0.22.5","displayVersion":"0.22.5","auth_required":true}""")
        )
        healthyServer.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""")
        )
        healthyServer.start()

        try {
            val client = HermesRestClient(EncryptedCookieStore(null))
            val rungs = listOf(
                ConnectionRung("dead-ts", "Unreachable Rung", "http://127.0.0.1:1", ConnectionKind.TAILSCALE),
                ConnectionRung("live-cf", "Cloudflare Mock", healthyServer.url("/").toString(), ConnectionKind.CLOUDFLARE)
            )

            val snap = client.resolveConnectionLadder(rungs)
            assertEquals(LinkStatus.AUTH_REQUIRED, snap.status)
            assertEquals("live-cf", snap.activeRung?.id)
            assertEquals("0.22.5", snap.serverVersion)
        } finally {
            healthyServer.shutdown()
        }
    }

    @Test
    fun `rpc client dispatches event frames and server approval requests`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = HermesRestClient(EncryptedCookieStore(null))
        val rpc = HermesRpcClient(client, scope)

        val eventDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { rpc.events.first() }
        rpc.handleIncomingFrame(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"s1","profile":"domo","payload":{"name":"hass_turn_on"}}}"""
        )
        val ev = eventDeferred.await()
        assertEquals("tool.start", ev.type)
        assertEquals("domo", ev.profile)

        val srqDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { rpc.serverRequests.first() }
        rpc.handleIncomingFrame(
            """{"jsonrpc":"2.0","id":"srq-42","method":"approval","params":{"profile":"programador","tool_name":"bash","command":"git push"}}"""
        )
        val srq = srqDeferred.await()
        assertEquals("srq-42", srq.id)
        assertEquals("approval", srq.method)
    }

    @Test
    fun `desktop workspace presets include wall, mic, and chat widgets`() {
        val widgets = defaultWidgetsForPreset(WorkspacePreset.COMMAND_CENTER)
        assertTrue(widgets.size >= 4)
    }
}
