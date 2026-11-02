package art.rodriguezrodriguez.hermesos.core.network

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Pure security & wire-protocol helpers matching hermes-agent backend contracts:
 * - Secret redaction guard (`hermes_cli/web_routers/_common.py::is_redacted_credential_preview`)
 * - WebSocket gateway ticket subprotocol & loopback query token (`hermes_cli/web_server_boundary.py`)
 * - RFC 3986 path/query encoding & profile-scoped query parameter injection (`web/src/lib/api.ts`)
 */
object CredentialGuards {

    private val LEGACY_MASK_REGEX = Regex("^.{4}\\.\\.\\..{4}$")
    private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

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

    /**
     * RFC 3986 percent-encoding for a single URL path segment or query value
     * (encodes spaces as `%20`, `/` as `%2F`, `+` as `%2B`, etc.).
     */
    fun encodePathSegment(raw: String): String {
        if (raw.isEmpty()) return ""
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size + 8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved = (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append(HEX_DIGITS[c ushr 4])
                sb.append(HEX_DIGITS[c and 0x0F])
            }
        }
        return sb.toString()
    }

    fun encodeQueryComponent(raw: String): String = encodePathSegment(raw)

    /**
     * Encodes each segment of a FastAPI `{name:path}` parameter while preserving `/` separators
     * (e.g. `category/my plugin` -> `category/my%20plugin`).
     */
    fun encodeMultiSegmentPath(raw: String): String {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        return trimmed.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodePathSegment(it) }
    }

    /**
     * Accepts either a raw loopback session token or a pasted dashboard launch URL
     * (`http://127.0.0.1:9119/?token=...`) and returns the clean session token.
     */
    fun extractTokenFromUrlOrRaw(input: String?): String? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val qIdx = trimmed.indexOf('?')
        val queryCandidate = when {
            qIdx >= 0 -> trimmed.substring(qIdx + 1).substringBefore('#')
            trimmed.startsWith("token=", ignoreCase = true) -> trimmed.substringBefore('#')
            else -> null
        }
        if (queryCandidate != null) {
            for (pair in queryCandidate.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq).equals("token", ignoreCase = true)) {
                    val rawVal = pair.substring(eq + 1)
                    val decoded = runCatching {
                        URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
                    }.getOrDefault(rawVal).trim()
                    if (decoded.isNotEmpty()) return decoded
                }
            }
        }
        return trimmed
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
     * Builds an authenticated WebSocket URL supporting:
     * - `WS /api/ws`: subprotocol ticket when `authRequired == true` (`includeTicketQuery = false`),
     *   or `?token=<sessionToken>` when `authRequired == false` (loopback mode, `_ws_auth_reason`).
     * - `WS /api/audio/{transcribe,speak}-stream`: `?ticket=<ticket>` when `authRequired == true` (`includeTicketQuery = true`),
     *   or `?token=<sessionToken>` when `authRequired == false`.
     */
    fun buildAuthenticatedWsUrl(
        baseUrl: String,
        path: String = "/api/ws",
        profile: String? = null,
        authRequired: Boolean = true,
        ticket: String? = null,
        includeTicketQuery: Boolean = false,
        sessionToken: String? = null
    ): String {
        var url = buildGatewayWebSocketUrl(baseUrl, path)
        url = appendProfileParam(url, profile)
        if (authRequired) {
            val cleanTicket = ticket?.trim().orEmpty()
            if (includeTicketQuery && cleanTicket.isNotEmpty()) {
                val sep = if (url.contains('?')) "&" else "?"
                url = "$url${sep}ticket=${encodeQueryComponent(cleanTicket)}"
            }
        } else {
            val cleanToken = extractTokenFromUrlOrRaw(sessionToken).orEmpty()
            if (cleanToken.isNotEmpty()) {
                val sep = if (url.contains('?')) "&" else "?"
                url = "$url${sep}token=${encodeQueryComponent(cleanToken)}"
            }
        }
        return url
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
        if (p.isEmpty() || Regex("(^|[?&])profile=").containsMatchIn(pathAndQuery)) return pathAndQuery
        val sep = if (pathAndQuery.contains("?")) "&" else "?"
        return "$pathAndQuery${sep}profile=${encodeQueryComponent(p)}"
    }
}
