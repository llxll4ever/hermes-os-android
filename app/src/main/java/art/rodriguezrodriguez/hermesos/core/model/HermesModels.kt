package art.rodriguezrodriguez.hermesos.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── 1. Connection Ladder & Authentication ─────────────────────────────────────

@Serializable
enum class ConnectionKind {
    TAILSCALE,
    CLOUDFLARE,
    CUSTOM
}

@Serializable
data class ConnectionRung(
    val id: String,
    val label: String,
    val baseUrl: String,
    val kind: ConnectionKind,
    val enabled: Boolean = true
)

enum class LinkStatus {
    DISCONNECTED,
    PROBING,
    AUTH_REQUIRED,
    CONNECTED,
    DEGRADED
}

data class ConnectionSnapshot(
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val activeRung: ConnectionRung? = null,
    val rungs: List<ConnectionRung> = defaultConnectionRungs(),
    val serverVersion: String = "",
    val authRequired: Boolean = true,
    val authenticatedUser: String = "",
    val wsConnected: Boolean = false,
    val latencyMs: Long = 0L,
    val lastError: String? = null
)

fun defaultConnectionRungs(): List<ConnectionRung> = listOf(
    ConnectionRung(
        id = "tailscale-direct",
        label = "Tailscale Direct (lalo-vps)",
        baseUrl = "http://100.103.166.20:9119",
        kind = ConnectionKind.TAILSCALE,
        enabled = true
    ),
    ConnectionRung(
        id = "cloudflare-public",
        label = "Cloudflare Tunnel (hermes.rodriguezrodriguez.art)",
        baseUrl = "https://hermes.rodriguezrodriguez.art",
        kind = ConnectionKind.CLOUDFLARE,
        enabled = true
    )
)

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val version: String = "",
    val displayVersion: String = "",
    @SerialName("auth_required") val authRequired: Boolean = false
)

@Serializable
data class AuthProvidersResponse(
    val providers: List<AuthProviderInfo> = emptyList()
)

@Serializable
data class AuthProviderInfo(
    val name: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("supports_password") val supportsPassword: Boolean = false
)

@Serializable
data class PasswordLoginRequest(
    val provider: String = "basic",
    val username: String,
    val password: String,
    val next: String = ""
)

@Serializable
data class PasswordLoginResponse(
    val ok: Boolean = false,
    val next: String = ""
)

@Serializable
data class AuthMeResponse(
    @SerialName("user_id") val userId: String = "",
    val email: String = "",
    @SerialName("display_name") val displayName: String = "",
    val provider: String = ""
)

@Serializable
data class WsTicketResponse(
    val ticket: String = "",
    @SerialName("ttl_seconds") val ttlSeconds: Int = 30
)

// ── 2. Bot Profiles, Live Activity Wall & Tool Approvals ──────────────────────

@Serializable
enum class BotLiveState {
    IDLE,
    THINKING,
    RUNNING_TOOL,
    WAITING_APPROVAL,
    SPEAKING,
    ERROR
}

@Serializable
data class BotProfileCard(
    val profile: String,
    val handle: String,
    val displayName: String,
    val roleSummary: String,
    val activeModel: String = "",
    val activeProvider: String = "",
    val liveState: BotLiveState = BotLiveState.IDLE,
    val currentActivity: String = "En espera de órdenes",
    val activeToolName: String? = null,
    val activeSessionId: String? = null,
    val activeRoomId: String? = null,
    val soulMarkdown: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class PendingToolApproval(
    val requestId: String,
    val botProfile: String,
    val sessionId: String? = null,
    val roomId: String? = null,
    val memberId: String? = null,
    val taskId: String? = null,
    val executionGeneration: Int = 0,
    val toolName: String,
    val commandPreview: String,
    val riskLabel: String = "standard",
    val createdAtMs: Long = System.currentTimeMillis()
)

// ── 3. Bot Teams (Hosted Rooms + 1:1 Rich Chat) ───────────────────────────────

@Serializable
data class TeamMember(
    @SerialName("member_id") val memberId: String,
    val profile: String,
    val handle: String,
    @SerialName("display_name") val displayName: String = "",
    val liveStatus: String = "idle"
)

@Serializable
data class BotTeamRoom(
    @SerialName("room_id") val roomId: String,
    val name: String,
    val members: List<TeamMember> = emptyList(),
    val working: Boolean = false,
    val blocked: Boolean = false,
    val latestSeq: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
enum class AuthorKind {
    USER,
    BOT,
    SYSTEM
}

@Serializable
data class ToolCallChip(
    val name: String,
    val status: String,
    val detail: String = ""
)

@Serializable
data class ChatTurnMessage(
    val id: String,
    val scopeId: String, // roomId or "bot:<profile>"
    val authorHandle: String,
    val authorKind: AuthorKind,
    val modelBadge: String = "",
    val content: String,
    val reasoning: String = "",
    val toolCalls: List<ToolCallChip> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

// ── 4. Configs, Providers & Manual Model Registry ─────────────────────────────

@Serializable
data class EnvVarEntry(
    val key: String,
    val valuePreview: String = "",
    val isConfigured: Boolean = false,
    val isSecret: Boolean = true,
    val providerGroup: String = "General"
)

@Serializable
data class CustomEndpointEntry(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKeyPreview: String = "",
    val defaultModel: String = "",
    val active: Boolean = false
)

@Serializable
data class ManualModelEntry(
    val slug: String,
    val provider: String,
    val label: String = slug,
    val supportsTools: Boolean = true,
    val pinned: Boolean = true
)

@Serializable
data class ModelOptionEntry(
    val slug: String,
    val provider: String,
    val label: String = slug,
    val isManual: Boolean = false,
    val isActiveGlobal: Boolean = false
)

// ── 5. MCP Servers & Plugin Store ─────────────────────────────────────────────

@Serializable
enum class McpTransport {
    STDIO,
    SSE,
    HTTP
}

@Serializable
data class McpServerEntry(
    val name: String,
    val transport: McpTransport = McpTransport.STDIO,
    val commandOrUrl: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val connected: Boolean = false,
    val tools: List<String> = emptyList(),
    val statusDetail: String = ""
)

@Serializable
data class McpCatalogEntry(
    val name: String,
    val title: String,
    val description: String,
    val transport: McpTransport = McpTransport.HTTP,
    val requiresOauth: Boolean = false,
    val installed: Boolean = false
)

@Serializable
data class PluginSettingFieldUi(
    val key: String,
    val type: String, // string | number | boolean | enum | secret | json
    val label: String,
    val description: String = "",
    val required: Boolean = false,
    val currentValue: String = "",
    val choices: List<String> = emptyList(),
    val envKey: String? = null,
    val hasSecretValue: Boolean = false
)

@Serializable
data class PluginScanIssueUi(
    val severity: String,
    val category: String,
    val file: String,
    val line: Int,
    val description: String
)

@Serializable
data class PluginCatalogItemUi(
    val name: String,
    val description: String,
    val repo: String = "",
    val shaShort: String = "",
    val tier: String = "community", // official | verified | community
    val maintainer: String = "",
    val capabilitySummary: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val updateAvailable: Boolean = false,
    val settingsSchema: List<PluginSettingFieldUi> = emptyList(),
    val scanFindings: List<PluginScanIssueUi> = emptyList(),
    val pendingConsentDelta: List<String> = emptyList()
)

// ── 6. Desktop Mode Workspace Canvas & Widgets ────────────────────────────────

@Serializable
enum class DesktopWidgetType(val defaultTitle: String) {
    BOT_WALL("Muro de Bots en Vivo"),
    TEAM_CHAT("Ventana Chat & Equipos"),
    VOICE_MIC("Micrófono Hermes Live"),
    QUICK_MODEL_MCP("Control Rápido Modelos & MCP"),
    APPROVALS_INBOX("Bandeja de Aprobaciones"),
    VPS_TELEMETRY("Estado VPS & Consola PTY")
}

@Serializable
enum class WidgetSpan {
    SMALL,      // 1 column compact
    STANDARD,   // 1 column standard height
    WIDE,       // 2 columns wide
    MAXIMIZED   // Full desktop canvas focus
}

@Serializable
data class DesktopWidgetPlacement(
    val id: String,
    val type: DesktopWidgetType,
    val customTitle: String = type.defaultTitle,
    val span: WidgetSpan = WidgetSpan.STANDARD,
    val targetScope: String = "team:main", // e.g., "bot:domo" or "room:<id>"
    val order: Int = 0
)

@Serializable
enum class WorkspacePreset(val displayName: String) {
    COMMAND_CENTER("Command Center"),
    TEAM_WAR_ROOM("Team War Room"),
    VOICE_DOMOTICS("Voz & Domótica"),
    DEX_STUDIO("DeX Multi-Window")
}

fun defaultWidgetsForPreset(preset: WorkspacePreset): List<DesktopWidgetPlacement> = when (preset) {
    WorkspacePreset.COMMAND_CENTER -> listOf(
        DesktopWidgetPlacement("w-wall", DesktopWidgetType.BOT_WALL, span = WidgetSpan.WIDE, order = 0),
        DesktopWidgetPlacement("w-mic", DesktopWidgetType.VOICE_MIC, span = WidgetSpan.SMALL, targetScope = "bot:orchestrator", order = 1),
        DesktopWidgetPlacement("w-quick", DesktopWidgetType.QUICK_MODEL_MCP, span = WidgetSpan.SMALL, order = 2),
        DesktopWidgetPlacement("w-chat", DesktopWidgetType.TEAM_CHAT, span = WidgetSpan.WIDE, targetScope = "bot:hermes", order = 3)
    )
    WorkspacePreset.TEAM_WAR_ROOM -> listOf(
        DesktopWidgetPlacement("w-chat-team", DesktopWidgetType.TEAM_CHAT, customTitle = "War Room de Equipo", span = WidgetSpan.WIDE, order = 0),
        DesktopWidgetPlacement("w-wall", DesktopWidgetType.BOT_WALL, span = WidgetSpan.STANDARD, order = 1),
        DesktopWidgetPlacement("w-approvals", DesktopWidgetType.APPROVALS_INBOX, span = WidgetSpan.STANDARD, order = 2),
        DesktopWidgetPlacement("w-mic", DesktopWidgetType.VOICE_MIC, span = WidgetSpan.SMALL, order = 3)
    )
    WorkspacePreset.VOICE_DOMOTICS -> listOf(
        DesktopWidgetPlacement("w-mic-domo", DesktopWidgetType.VOICE_MIC, customTitle = "Micrófono Directo @domo", span = WidgetSpan.WIDE, targetScope = "bot:domo", order = 0),
        DesktopWidgetPlacement("w-wall", DesktopWidgetType.BOT_WALL, span = WidgetSpan.STANDARD, order = 1),
        DesktopWidgetPlacement("w-chat-domo", DesktopWidgetType.TEAM_CHAT, customTitle = "Bitácora @domo", span = WidgetSpan.STANDARD, targetScope = "bot:domo", order = 2)
    )
    WorkspacePreset.DEX_STUDIO -> listOf(
        DesktopWidgetPlacement("w-wall", DesktopWidgetType.BOT_WALL, span = WidgetSpan.STANDARD, order = 0),
        DesktopWidgetPlacement("w-chat-1", DesktopWidgetType.TEAM_CHAT, customTitle = "Sala Principal", span = WidgetSpan.WIDE, order = 1),
        DesktopWidgetPlacement("w-mic", DesktopWidgetType.VOICE_MIC, span = WidgetSpan.SMALL, order = 2),
        DesktopWidgetPlacement("w-quick", DesktopWidgetType.QUICK_MODEL_MCP, span = WidgetSpan.SMALL, order = 3),
        DesktopWidgetPlacement("w-approvals", DesktopWidgetType.APPROVALS_INBOX, span = WidgetSpan.SMALL, order = 4),
        DesktopWidgetPlacement("w-vps", DesktopWidgetType.VPS_TELEMETRY, span = WidgetSpan.STANDARD, order = 5)
    )
}

// ── 7. JSON-RPC 2.0 Wire Envelope ─────────────────────────────────────────────

@Serializable
data class RpcEnvelope(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: RpcErrorPayload? = null
)

@Serializable
data class RpcErrorPayload(
    val code: Int = -1,
    val message: String = "",
    val data: JsonElement? = null
)
