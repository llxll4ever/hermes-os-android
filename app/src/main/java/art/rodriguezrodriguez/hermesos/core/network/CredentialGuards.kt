package art.rodriguezrodriguez.hermesos.core.network

/**
 * Pure security & wire-protocol helpers matching hermes-agent backend contracts:
 * - Secret redaction guard (`hermes_cli/web_routers/_common.py::is_redacted_credential_preview`)
 * - WebSocket gateway ticket subprotocol (`hermes_cli/web_server_boundary.py`)
 * - Profile-scoped query parameter injection (`web/src/lib/api.ts`)
 */
object CredentialGuards {

    private val LEGACY_MASK_REGEX = Regex("^.{4}\\.\\.\\..{4}$")

    /**
     * Never allow a masked/redacted dashboard preview to be written back to `/api/env`
     * or plugin secret fields. Mirrors `is_redacted_credential_preview` in `_common.py`.
     */
    fun isRedactedPreview(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (v.startsWith("«redacted")) return true
        if (v == "***") return true
        return LEGACY_MASK_REGEX.matches(v)
    }

    /**
     * Filters out blank or redacted-preview entries before sending an `/api/env` PUT payload.
     */
    fun filterSafeEnvUpdates(candidates: Map<String, String>): Map<String, String> {
        return candidates.filter { (key, value) ->
            key.isNotBlank() && value.isNotBlank() && !isRedactedPreview(value)
        }
    }

    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun buildGatewayWebSocketUrl(baseUrl: String, path: String = "/api/ws"): String {
        val norm = normalizeBaseUrl(baseUrl)
        val wsBase = when {
            norm.startsWith("https://") -> "wss://" + norm.removePrefix("https://")
            norm.startsWith("http://") -> "ws://" + norm.removePrefix("http://")
            else -> "wss://$norm"
        }
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$wsBase$cleanPath"
    }

    /**
     * Subprotocol header values required by `_gateway_ws_ticket_from_subprotocol`
     * in `hermes_cli/web_server_boundary.py`.
     */
    fun buildTicketSubprotocols(ticket: String?): List<String> {
        val t = ticket?.trim().orEmpty()
        return if (t.isNotEmpty()) {
            listOf("hermes-gateway-v1", "hermes-gateway-ticket.$t")
        } else {
            listOf("hermes-gateway-v1")
        }
    }

    fun appendProfileParam(pathAndQuery: String, profile: String?): String {
        val p = profile?.trim().orEmpty()
        if (p.isEmpty() || pathAndQuery.contains("profile=")) return pathAndQuery
        val sep = if (pathAndQuery.contains("?")) "&" else "?"
        return "$pathAndQuery${sep}profile=$p"
    }
}
