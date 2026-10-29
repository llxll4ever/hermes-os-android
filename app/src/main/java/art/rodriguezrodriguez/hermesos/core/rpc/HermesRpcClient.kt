package art.rodriguezrodriguez.hermesos.core.rpc

import art.rodriguezrodriguez.hermesos.core.model.*
import art.rodriguezrodriguez.hermesos.core.network.CredentialGuards
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.network.bool
import art.rodriguezrodriguez.hermesos.core.network.str
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class GatewayEventFrame(
    val type: String,
    val sessionId: String?,
    val profile: String?,
    val roomId: String?,
    val memberId: String?,
    val payload: JsonObject
)

data class ServerRpcRequest(
    val id: String,
    val method: String,
    val params: JsonObject
)

/**
 * Multiplexed JSON-RPC 2.0 Client over `WS /api/ws` (`tui_gateway` host-scoped socket).
 *
 * - Mints a fresh single-use WS ticket (`POST /api/auth/ws-ticket`) on every connect/reconnect.
 * - Multiplexes concurrent RPC calls (`session.create`, `prompt.submit`, `groups.*`, `plugins.manage`, `reload.mcp`).
 * - Streams real-time bot & room events (`tool.start`, `tool.complete`, `message.delta`, `reasoning.delta`,
 *   `on_room_member_activity`) to drive the Live Bot Wall and Team War Room widgets.
 * - Captures server->client interactive requests (`srq-*` tool approvals) and sends responses back.
 */
class HermesRpcClient(
    private val restClient: HermesRestClient,
    private val scope: CoroutineScope
) {
    private val json = restClient.json
    private val rpcCounter = AtomicLong(1L)
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    private var webSocket: WebSocket? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEventFrame>(extraBufferCapacity = 256)
    val events: SharedFlow<GatewayEventFrame> = _events.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<ServerRpcRequest>(extraBufferCapacity = 64)
    val serverRequests: SharedFlow<ServerRpcRequest> = _serverRequests.asSharedFlow()

    suspend fun connect(authRequired: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        val ticket = if (authRequired) restClient.mintWsTicket() else null
        val wsUrl = CredentialGuards.buildGatewayWebSocketUrl(restClient.activeBaseUrl, "/api/ws")
        val subprotocols = CredentialGuards.buildTicketSubprotocols(ticket)

        val reqBuilder = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", subprotocols.joinToString(", "))
            .header("Origin", CredentialGuards.normalizeBaseUrl(restClient.activeBaseUrl))

        restClient.loopbackSessionToken?.takeIf { it.isNotBlank() }?.let {
            reqBuilder.header("X-Hermes-Session-Token", it)
        }

        val readySignal = CompletableDeferred<Boolean>()

        webSocket = restClient.httpClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                if (!readySignal.isCompleted) readySignal.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                failAllPending("WebSocket closed ($code: $reason)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                if (!readySignal.isCompleted) readySignal.complete(false)
                failAllPending(t.message ?: "WebSocket transport failure")
            }
        })

        withTimeoutOrNull(8_000L) { readySignal.await() } ?: false
    }

    fun disconnect() {
        runCatching { webSocket?.close(1000, "Client re-home") }
        webSocket = null
        _connected.value = false
        failAllPending("Disconnected")
    }

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 25_000L): JsonElement {
        val ws = webSocket ?: error("Gateway WebSocket no conectado")
        val callId = "rpc-${rpcCounter.getAndIncrement()}"
        val deferred = CompletableDeferred<JsonElement>()
        pendingCalls[callId] = deferred

        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", callId)
            put("method", method)
            put("params", params)
        }

        val sent = ws.send(frame.toString())
        if (!sent) {
            pendingCalls.remove(callId)
            error("No se pudo enviar frame JSON-RPC ($method)")
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingCalls.remove(callId)
        }
    }

    fun respondToServerRequest(requestId: String, resultPayload: JsonObject) {
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("result", resultPayload)
        }
        webSocket?.send(frame.toString())
    }

    // ── High-Level Typed Gateway Operations ───────────────────────────────────

    suspend fun createOrResumeSession(profile: String, model: String? = null): String {
        val params = buildJsonObject {
            put("profile", profile)
            put("source", "hermes-os-android")
            put("idempotency_key", "android-$profile-${UUID.randomUUID()}")
            if (!model.isNullOrBlank()) put("model", model)
        }
        val res = call("session.create", params) as? JsonObject
        return res?.str("session_id").orEmpty()
    }

    suspend fun submitPrompt(
        sessionId: String,
        profile: String,
        text: String,
        voiceTurn: Boolean = false
    ): JsonObject? {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("profile", profile)
            put("text", text)
            if (voiceTurn) {
                put("voice_turn", true)
                put("surface", "voice-live")
            }
        }
        return call("prompt.submit", params) as? JsonObject
    }

    // ── Bot Teams (`groups.*` Hosted Rooms) ───────────────────────────────────

    suspend fun listTeamRooms(): List<BotTeamRoom> {
        val res = call("groups.list", buildJsonObject { put("limit", 50) }) as? JsonObject ?: return emptyList()
        val roomsArr = res["rooms"] as? JsonArray ?: return emptyList()
        return roomsArr.mapNotNull { el -> parseRoomObject(el as? JsonObject) }
    }

    suspend fun createTeamRoom(name: String, memberProfiles: List<String>): BotTeamRoom? {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "team" }
        val roomId = "$slug-${UUID.randomUUID().toString().take(6)}"
        val params = buildJsonObject {
            put("room_id", roomId)
            put("name", name.trim())
            putJsonArray("members") {
                memberProfiles.distinct().forEach { prof ->
                    addJsonObject {
                        put("member_id", prof)
                        put("profile", prof)
                        put("handle", prof)
                        put("display_name", prof.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
        val res = call("groups.create", params) as? JsonObject
        return parseRoomObject(res?.get("room") as? JsonObject)
    }

    suspend fun sendTeamRoomMessage(roomId: String, text: String): Boolean {
        val params = buildJsonObject {
            put("room_id", roomId)
            put("event_id", "evt-${UUID.randomUUID()}")
            putJsonObject("payload") {
                put("text", text)
                put("content", text)
            }
        }
        val res = call("groups.send", params) as? JsonObject
        return res?.bool("accepted") ?: (res != null)
    }

    suspend fun fetchTeamRoomState(roomId: String): Pair<BotTeamRoom?, List<Pair<String, String>>> {
        val res = call("groups.state", buildJsonObject { put("room_id", roomId) }) as? JsonObject
            ?: return null to emptyList()
        val room = parseRoomObject(res["room"] as? JsonObject)
        val driver = res["driver_status"] as? JsonObject
        val memberActivities = (driver?.get("member_activity") as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            o.str("member_id") to o.str("status")
        }.orEmpty()
        return room to memberActivities
    }

    suspend fun fetchTeamRoomLog(roomId: String, sinceSeq: Int = 0): List<ChatTurnMessage> {
        val params = buildJsonObject {
            put("room_id", roomId)
            put("since_seq", sinceSeq)
            put("limit", 100)
        }
        val res = call("groups.log", params) as? JsonObject ?: return emptyList()
        val events = res["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val kind = o.str("kind")
            val actor = o["actor"] as? JsonObject
            val actorKindStr = actor?.str("kind").orEmpty()
            val actorId = actor?.str("id").orEmpty().ifBlank { "system" }
            val payload = o["payload"] as? JsonObject
            val text = payload?.str("text")?.ifBlank { payload.str("content") }.orEmpty()
            if (text.isBlank() && !kind.contains("turn")) return@mapNotNull null
            ChatTurnMessage(
                id = o.str("event_id").ifBlank { "seq-${o.str("seq")}" },
                scopeId = roomId,
                authorHandle = actorId,
                authorKind = when {
                    actorKindStr.contains("user", true) || kind == "message.user" -> AuthorKind.USER
                    actorKindStr.contains("member", true) || actorKindStr.contains("bot", true) || actorKindStr.contains("agent", true) -> AuthorKind.BOT
                    else -> AuthorKind.SYSTEM
                },
                content = text.ifBlank { "[$kind]" }
            )
        }
    }

    suspend fun approveRoomAction(approval: PendingToolApproval, allow: Boolean): Boolean {
        val choice = if (allow) "allow_once" else "deny"
        return if (!approval.roomId.isNullOrBlank() && !approval.memberId.isNullOrBlank() && !approval.taskId.isNullOrBlank()) {
            val params = buildJsonObject {
                put("room_id", approval.roomId)
                put("member_id", approval.memberId)
                put("task_id", approval.taskId)
                put("execution_generation", approval.executionGeneration)
                put("choice", choice)
                put("request_id", approval.requestId)
            }
            runCatching { call("groups.approve", params); true }.getOrDefault(false)
        } else {
            respondToServerRequest(
                approval.requestId,
                buildJsonObject { put("choice", choice) }
            )
            true
        }
    }

    suspend fun reloadMcpServers(confirm: Boolean = true): Boolean {
        val params = buildJsonObject { put("confirm", confirm) }
        return runCatching { call("reload.mcp", params); true }.getOrDefault(false)
    }

    suspend fun savePluginSettings(profile: String, pluginKey: String, values: Map<String, String>): Boolean {
        val params = buildJsonObject {
            put("profile", profile)
            put("action", "settings")
            put("key", pluginKey)
            putJsonObject("values") {
                values.forEach { (k, v) -> put(k, v) }
            }
        }
        val res = runCatching { call("plugins.manage", params) as? JsonObject }.getOrNull()
        return res?.bool("ok") ?: false
    }

    // ── Internal Frame Dispatcher ─────────────────────────────────────────────

    internal fun handleIncomingFrame(rawText: String) {
        val obj = runCatching { json.parseToJsonElement(rawText) as? JsonObject }.getOrNull() ?: return
        val id = (obj["id"] as? JsonPrimitive)?.content
        val method = (obj["method"] as? JsonPrimitive)?.content

        // 1. Response to an outgoing client RPC call
        if (id != null && method == null) {
            val pending = pendingCalls.remove(id) ?: return
            val errObj = obj["error"] as? JsonObject
            if (errObj != null) {
                pending.completeExceptionally(
                    IllegalStateException("RPC ${errObj.str("code")}: ${errObj.str("message")}")
                )
            } else {
                pending.complete(obj["result"] ?: JsonNull)
            }
            return
        }

        // 2. Server -> Client Request (e.g. `srq-...` tool execution approval)
        if (id != null && method != null) {
            val paramsObj = (obj["params"] as? JsonObject) ?: JsonObject(emptyMap())
            scope.launch {
                _serverRequests.emit(ServerRpcRequest(id = id, method = method, params = paramsObj))
            }
            return
        }

        // 3. Server Event Notification (`method == "event"`)
        if (method == "event") {
            val paramsObj = (obj["params"] as? JsonObject) ?: return
            val eventType = paramsObj.str("type")
            val payloadObj = (paramsObj["payload"] as? JsonObject) ?: paramsObj
            val frame = GatewayEventFrame(
                type = eventType,
                sessionId = paramsObj.str("session_id").takeIf { it.isNotBlank() },
                profile = paramsObj.str("profile").ifBlank { payloadObj.str("profile") }.takeIf { it.isNotBlank() },
                roomId = payloadObj.str("room_id").takeIf { it.isNotBlank() },
                memberId = payloadObj.str("member_id").takeIf { it.isNotBlank() },
                payload = payloadObj
            )
            scope.launch { _events.emit(frame) }
        }
    }

    private fun parseRoomObject(o: JsonObject?): BotTeamRoom? {
        if (o == null) return null
        val roomId = o.str("room_id").ifBlank { return null }
        val members = (o["members"] as? JsonArray)?.mapNotNull { mEl ->
            val m = mEl as? JsonObject ?: return@mapNotNull null
            val prof = m.str("profile").ifBlank { m.str("member_id") }
            val handle = m.str("handle").ifBlank { prof }
            TeamMember(
                memberId = m.str("member_id").ifBlank { prof },
                profile = prof,
                handle = handle,
                displayName = m.str("display_name").ifBlank { handle.replaceFirstChar { it.uppercase() } }
            )
        }.orEmpty()
        return BotTeamRoom(
            roomId = roomId,
            name = o.str("name").ifBlank { roomId },
            members = members,
            latestSeq = (o["latest_seq"] as? JsonPrimitive)?.intOrNull ?: 0
        )
    }

    private fun failAllPending(reason: String) {
        val ex = CancellationException(reason)
        pendingCalls.values.forEach { it.completeExceptionally(ex) }
        pendingCalls.clear()
    }
}
