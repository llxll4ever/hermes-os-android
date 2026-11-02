package art.rodriguezrodriguez.hermesos.core.network

import art.rodriguezrodriguez.hermesos.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class EndpointProbeResult(
    val path: String,
    val method: String,
    val code: Int,
    val latencyMs: Long,
    val error: String? = null,
    val note: String? = null
)

data class EnvBatchUpdateResult(
    val updatedKeys: List<String>,
    val failedKeys: List<String>
) {
    val allSucceeded: Boolean
        get() = updatedKeys.isNotEmpty() && failedKeys.isEmpty()
}

/**
 * Observable Connection Ladder + Typed REST Client for `hermes dashboard` (port 9119).
 *
 * Implements the precedence rules from `apps/desktop/ENGINEERING.md`:
 * 1. Ordered candidate rungs (Tailscale Direct -> Cloudflare Tunnel -> Custom URL).
 * 2. Fast probe on `/api/health`, followed by session verification on `/api/auth/me`
 *    (or `/api/profiles` token verification when `auth_required == false` in loopback mode).
 * 3. Every profile-scoped endpoint carries RFC 3986 percent-encoded `?profile=<name>` and body
 *    `profile` fields matching `hermes_cli/web_models.py`.
 */
class HermesRestClient(
    val cookieStore: EncryptedCookieStore,
    baseClient: OkHttpClient? = null
) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val httpClient: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .cookieJar(cookieStore)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val probeClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(2200, TimeUnit.MILLISECONDS)
        .readTimeout(2800, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    var activeBaseUrl: String = "https://hermes.rodriguezrodriguez.art"

    @Volatile
    var loopbackSessionToken: String? = cookieStore.readBearerOrSessionToken("loopback_session_token")

    @Volatile
    var bearerAccessToken: String? = cookieStore.readBearerOrSessionToken("native_bearer_token")

    // ── Connection Ladder Resolution ──────────────────────────────────────────

    suspend fun resolveConnectionLadder(rungs: List<ConnectionRung>): ConnectionSnapshot = withContext(Dispatchers.IO) {
        val enabled = rungs.filter { it.enabled && it.baseUrl.isNotBlank() }
        var lastErr: String? = null

        for (rung in enabled) {
            val base = CredentialGuards.normalizeBaseUrl(rung.baseUrl)
            val t0 = System.currentTimeMillis()
            val healthResult = runCatching { fetchHealth(base, useFastTimeout = true) }
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)

            if (healthResult.isSuccess) {
                val health = healthResult.getOrThrow()
                if (health.ok) {
                    activeBaseUrl = base
                    var loopbackErr: String? = null
                    val me: AuthMeResponse?
                    val status: LinkStatus
                    if (health.authRequired) {
                        me = runCatching { getAuthMe(base) }.getOrNull()
                        status = if (me != null && me.userId.isNotBlank()) {
                            LinkStatus.CONNECTED
                        } else {
                            LinkStatus.AUTH_REQUIRED
                        }
                    } else {
                        me = null
                        val probeCode = probeProtectedRouteCode("/api/profiles", base)
                        status = when {
                            probeCode in 200..299 -> LinkStatus.CONNECTED
                            probeCode == 401 || probeCode == 403 -> {
                                loopbackErr = "Nodo loopback requiere X-Hermes-Session-Token o URL ?token=..."
                                LinkStatus.AUTH_REQUIRED
                            }
                            probeCode > 0 -> {
                                loopbackErr = "Respuesta inesperada HTTP $probeCode en verificación loopback"
                                LinkStatus.DEGRADED
                            }
                            else -> LinkStatus.CONNECTED
                        }
                    }
                    val resolvedUser = when {
                        me != null -> me.displayName.ifBlank { me.userId }
                        !health.authRequired && status == LinkStatus.CONNECTED -> "loopback"
                        else -> ""
                    }
                    return@withContext ConnectionSnapshot(
                        status = status,
                        activeRung = rung.copy(baseUrl = base),
                        rungs = rungs,
                        serverVersion = health.displayVersion.ifBlank { health.version },
                        authRequired = health.authRequired,
                        authenticatedUser = resolvedUser,
                        latencyMs = elapsed,
                        lastError = loopbackErr
                    )
                }
            } else {
                lastErr = "${rung.label}: ${healthResult.exceptionOrNull()?.message ?: "Sin respuesta"}"
            }
        }

        ConnectionSnapshot(
            status = LinkStatus.DISCONNECTED,
            activeRung = null,
            rungs = rungs,
            lastError = lastErr ?: "Ningún nodo Hermes respondió en la escalera de conexión."
        )
    }

    private fun probeProtectedRouteCode(path: String, baseUrl: String): Int = runCatching {
        val req = buildRequest(path, baseUrl).get().build()
        probeClient.newCall(req).execute().use { it.code }
    }.getOrDefault(-1)

    // ── Auth, WS Ticket & Diagnostics Probe Endpoints ─────────────────────────

    suspend fun fetchHealth(baseUrl: String = activeBaseUrl, useFastTimeout: Boolean = false): HealthResponse =
        getTyped("/api/health", baseUrl = baseUrl, clientOverride = if (useFastTimeout) probeClient else null)

    suspend fun getAuthProviders(baseUrl: String = activeBaseUrl): List<AuthProviderInfo> =
        runCatching { getTyped<AuthProvidersResponse>("/api/auth/providers", baseUrl = baseUrl).providers }
            .getOrDefault(listOf(AuthProviderInfo("basic", "Username & Password", true)))

    suspend fun loginWithPassword(
        username: String,
        password: String,
        provider: String = "basic",
        baseUrl: String = activeBaseUrl
    ): PasswordLoginResponse {
        val body = json.encodeToString(
            PasswordLoginRequest.serializer(),
            PasswordLoginRequest(provider = provider, username = username, password = password)
        )
        return postTyped("/auth/password-login", body, baseUrl = baseUrl)
    }

    suspend fun getAuthMe(baseUrl: String = activeBaseUrl): AuthMeResponse =
        getTyped("/api/auth/me", baseUrl = baseUrl)

    /**
     * Mints a fresh single-use 30-second ticket for `WS /api/ws` or `WS /api/audio/{transcribe,speak}-stream`.
     * Never cached or reused across reconnects.
     */
    suspend fun mintWsTicket(baseUrl: String = activeBaseUrl): String? = withContext(Dispatchers.IO) {
        runCatching {
            postTyped<WsTicketResponse>("/api/auth/ws-ticket", "{}", baseUrl = baseUrl).ticket.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Authenticated route probe for `ContractDiagnosticsScreen`:
     * - Attaches session cookies, `X-Hermes-Session-Token`, and `Authorization: Bearer`.
     * - Provides a valid empty JSON body (`"{}"`) on `POST` / `PUT` / `PATCH` so OkHttp never throws.
     * - Marks `POST /api/auth/ws-ticket` as `SKIPPED` (`code = 0`) when `!authRequired` (loopback mode).
     */
    suspend fun probeEndpoint(
        method: String,
        path: String,
        authRequired: Boolean = true
    ): EndpointProbeResult = withContext(Dispatchers.IO) {
        val upperMethod = method.trim().uppercase()
        if (!authRequired && path.startsWith("/api/auth/ws-ticket")) {
            return@withContext EndpointProbeResult(
                path = path,
                method = upperMethod,
                code = 0,
                latencyMs = 0L,
                error = null,
                note = "SKIPPED — modo loopback (usa ?token= en WS)"
            )
        }

        val t0 = System.currentTimeMillis()
        try {
            val builder = buildRequest(path)
            when (upperMethod) {
                "POST", "PUT", "PATCH" -> builder.method(upperMethod, "{}".toRequestBody(jsonMedia))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                val note = when {
                    path.startsWith("/api/plugins/hermes-mobile-wall/wall") && resp.code == 404 ->
                        "Pendiente reinicio de hermes dashboard (:9119)"
                    else -> null
                }
                EndpointProbeResult(
                    path = path,
                    method = upperMethod,
                    code = resp.code,
                    latencyMs = latency,
                    error = null,
                    note = note
                )
            }
        } catch (e: Exception) {
            EndpointProbeResult(
                path = path,
                method = upperMethod,
                code = -1,
                latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L),
                error = e.message ?: "Error de transporte"
            )
        }
    }

    // ── Bot Profiles & Roster (`/api/profiles`) ───────────────────────────────

    suspend fun fetchProfiles(): List<BotProfileCard> = withContext(Dispatchers.IO) {
        val root = getJson("/api/profiles")
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["profiles"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name").ifBlank { obj.str("profile") }.ifBlank { return@mapNotNull null }
            val handle = obj.str("handle").ifBlank { name }
            val desc = obj.str("description").ifBlank { defaultRoleForHandle(handle) }
            BotProfileCard(
                profile = name,
                handle = handle,
                displayName = obj.str("display_name").ifBlank { handle.replaceFirstChar { it.uppercase() } },
                roleSummary = desc,
                activeModel = obj.str("model"),
                activeProvider = obj.str("provider"),
                liveState = if (obj.bool("active") || obj.bool("running")) BotLiveState.THINKING else BotLiveState.IDLE
            )
        }
    }

    suspend fun createBotProfile(name: String, description: String, model: String?): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("name", name.trim())
            put("description", description.trim())
            if (!model.isNullOrBlank()) put("model", model.trim())
        }
        runCatching { postJson("/api/profiles", payload.toString()); true }.getOrDefault(false)
    }

    suspend fun fetchProfileSoul(profile: String): String = withContext(Dispatchers.IO) {
        val enc = CredentialGuards.encodePathSegment(profile.trim())
        val obj = getJson("/api/profiles/$enc/soul") as? JsonObject
        obj?.str("content")?.ifBlank { obj.str("soul") }.orEmpty()
    }

    suspend fun updateProfileSoul(profile: String, soulMarkdown: String): Boolean = withContext(Dispatchers.IO) {
        val enc = CredentialGuards.encodePathSegment(profile.trim())
        val payload = buildJsonObject { put("content", soulMarkdown) }
        runCatching { putJson("/api/profiles/$enc/soul", payload.toString()); true }.getOrDefault(false)
    }

    suspend fun setProfileModel(profile: String, modelSlug: String, provider: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val cleanModel = modelSlug.trim()
            val cleanProfile = profile.trim()
            if (cleanModel.isEmpty() || cleanProfile.isEmpty()) return@withContext false
            val resolvedProvider = resolveProviderForAssignment(cleanProfile, cleanModel, provider)
            val payload = buildJsonObject {
                put("provider", resolvedProvider)
                put("model", cleanModel)
            }
            val enc = CredentialGuards.encodePathSegment(cleanProfile)
            runCatching {
                val res = putJson("/api/profiles/$enc/model", payload.toString()) as? JsonObject
                res?.boolOrDefault("ok", true) ?: true
            }.getOrDefault(false)
        }

    // ── Configs, Providers & Models (`/api/config`, `/api/env`, `/api/model`) ─

    suspend fun fetchRawConfigYaml(profile: String): String = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/config/raw", profile))
        when (el) {
            is JsonObject -> el.str("yaml").ifBlank { el.str("content") }.ifBlank { el.str("raw") }
            is JsonPrimitive -> el.content
            else -> ""
        }
    }

    suspend fun saveRawConfigYaml(profile: String, yamlText: String): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("yaml_text", yamlText)
            if (profile.isNotBlank()) put("profile", profile.trim())
        }
        val path = CredentialGuards.appendProfileParam("/api/config/raw", profile)
        runCatching {
            val res = putJson(path, payload.toString()) as? JsonObject
            res?.boolOrDefault("ok", true) ?: true
        }.getOrDefault(false)
    }

    suspend fun fetchEnvVars(profile: String): List<EnvVarEntry> = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/env", profile))
        val result = mutableListOf<EnvVarEntry>()
        when (el) {
            is JsonObject -> {
                val varsNode = el["vars"] ?: el["env"] ?: el
                if (varsNode is JsonArray) {
                    for (item in varsNode) {
                        val o = item as? JsonObject ?: continue
                        val key = o.str("key").ifBlank { o.str("name") }
                        if (key.isBlank()) continue
                        val preview = o.str("redacted_value").ifBlank { o.str("value") }.ifBlank { o.str("preview") }
                        result += EnvVarEntry(
                            key = key,
                            valuePreview = preview,
                            isConfigured = o.bool("is_set") || o.bool("configured") || preview.isNotBlank(),
                            isSecret = o.boolOrDefault("is_password", o.boolOrDefault("secret", true)),
                            providerGroup = o.str("provider_label")
                                .ifBlank { o.str("category") }
                                .ifBlank { o.str("provider") }
                                .ifBlank { inferProviderGroup(key) }
                        )
                    }
                } else if (varsNode is JsonObject) {
                    for ((k, v) in varsNode) {
                        when (v) {
                            is JsonObject -> {
                                val preview = v.str("redacted_value").ifBlank { v.str("value") }.ifBlank { v.str("preview") }
                                val isSet = v.bool("is_set") || v.bool("configured") || preview.isNotBlank()
                                val isSecret = v.boolOrDefault("is_password", v.boolOrDefault("secret", true))
                                val group = v.str("provider_label")
                                    .ifBlank { v.str("category") }
                                    .ifBlank { v.str("provider") }
                                    .ifBlank { inferProviderGroup(k) }
                                result += EnvVarEntry(
                                    key = k,
                                    valuePreview = preview,
                                    isConfigured = isSet,
                                    isSecret = isSecret,
                                    providerGroup = group
                                )
                            }
                            is JsonPrimitive -> {
                                val preview = if (v is JsonNull) "" else v.content
                                result += EnvVarEntry(
                                    key = k,
                                    valuePreview = preview,
                                    isConfigured = preview.isNotBlank(),
                                    isSecret = true,
                                    providerGroup = inferProviderGroup(k)
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }
            else -> Unit
        }
        result.sortedBy { it.key }
    }

    suspend fun updateEnvVarsDetailed(profile: String, updates: Map<String, String>): EnvBatchUpdateResult =
        withContext(Dispatchers.IO) {
            val safeUpdates = CredentialGuards.filterSafeEnvUpdates(updates)
            if (safeUpdates.isEmpty()) {
                return@withContext EnvBatchUpdateResult(emptyList(), emptyList())
            }
            val path = CredentialGuards.appendProfileParam("/api/env", profile)
            val updated = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val cleanProfile = profile.trim()

            for ((k, v) in safeUpdates) {
                val payload = buildJsonObject {
                    put("key", k.trim())
                    put("value", v)
                    if (cleanProfile.isNotEmpty()) put("profile", cleanProfile)
                }
                val ok = runCatching {
                    val res = putJson(path, payload.toString()) as? JsonObject
                    res?.boolOrDefault("ok", true) ?: true
                }.getOrDefault(false)
                if (ok) updated += k else failed += k
            }
            EnvBatchUpdateResult(updatedKeys = updated, failedKeys = failed)
        }

    suspend fun updateEnvVars(profile: String, updates: Map<String, String>): Boolean =
        updateEnvVarsDetailed(profile, updates).allSucceeded

    suspend fun fetchCustomEndpoints(profile: String): List<CustomEndpointEntry> = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/providers/custom-endpoints", profile))
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["endpoints"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id").ifBlank { o.str("name") }.ifBlank { return@mapNotNull null }
            CustomEndpointEntry(
                id = id,
                name = o.str("name").ifBlank { id },
                baseUrl = o.str("base_url").ifBlank { o.str("url") },
                apiKeyPreview = o.str("api_key_preview").ifBlank { o.str("api_key") },
                defaultModel = o.str("model").ifBlank { o.str("default_model") },
                active = o.bool("is_current") || o.bool("active")
            )
        }
    }

    suspend fun addCustomEndpoint(
        profile: String,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val cleanUrl = baseUrl.trim()
        val cleanModel = defaultModel.trim()
        if (cleanName.isEmpty() || cleanUrl.isEmpty() || cleanModel.isEmpty()) return@withContext false

        val payload = buildJsonObject {
            put("name", cleanName)
            put("base_url", cleanUrl)
            put("model", cleanModel)
            if (apiKey.isNotBlank() && !CredentialGuards.isRedactedPreview(apiKey)) {
                put("api_key", apiKey.trim())
            }
            if (profile.isNotBlank()) put("profile", profile.trim())
        }
        val path = CredentialGuards.appendProfileParam("/api/providers/custom-endpoints", profile)
        runCatching {
            val res = postJson(path, payload.toString()) as? JsonObject
            res?.boolOrDefault("ok", true) ?: true
        }.getOrDefault(false)
    }

    suspend fun updateCustomEndpoint(
        profile: String,
        endpointId: String,
        name: String,
        baseUrl: String,
        defaultModel: String,
        apiKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanId = endpointId.trim()
        val cleanName = name.trim()
        val cleanUrl = baseUrl.trim()
        val cleanModel = defaultModel.trim()
        if (cleanId.isEmpty() || cleanName.isEmpty() || cleanUrl.isEmpty() || cleanModel.isEmpty()) {
            return@withContext false
        }
        val payload = buildJsonObject {
            put("name", cleanName)
            put("base_url", cleanUrl)
            put("model", cleanModel)
            if (!apiKey.isNullOrBlank() && !CredentialGuards.isRedactedPreview(apiKey)) {
                put("api_key", apiKey.trim())
            }
            if (profile.isNotBlank()) put("profile", profile.trim())
        }
        val encId = CredentialGuards.encodePathSegment(cleanId)
        val path = CredentialGuards.appendProfileParam("/api/providers/custom-endpoints/$encId", profile)
        runCatching {
            val res = putJson(path, payload.toString()) as? JsonObject
            res?.boolOrDefault("ok", true) ?: true
        }.getOrDefault(false)
    }

    suspend fun deleteCustomEndpoint(profile: String, endpointId: String): Boolean = withContext(Dispatchers.IO) {
        val encId = CredentialGuards.encodePathSegment(endpointId.trim())
        val path = CredentialGuards.appendProfileParam("/api/providers/custom-endpoints/$encId", profile)
        runCatching { deleteJson(path); true }.getOrDefault(false)
    }

    suspend fun fetchModelOptions(profile: String): Pair<String, List<ModelOptionEntry>> = withContext(Dispatchers.IO) {
        val infoObj = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/model/info", profile)) as? JsonObject
        }.getOrNull()
        val currentModel = infoObj?.str("model").orEmpty()
        val currentProvider = infoObj?.str("provider").orEmpty()

        val optsEl = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/model/options", profile))
        }.getOrNull()

        val parsed = mutableListOf<ModelOptionEntry>()
        if (optsEl is JsonObject) {
            val providersArr = optsEl["providers"] as? JsonArray
            if (providersArr != null) {
                for (pEl in providersArr) {
                    val pObj = pEl as? JsonObject ?: continue
                    // `inventory.py:685` emits canonical provider ID in `slug` and human label in `name`.
                    val pSlug = pObj.str("slug")
                        .ifBlank { pObj.str("provider") }
                        .ifBlank { pObj.str("id") }
                        .ifBlank { pObj.str("name") }
                        .ifBlank { "custom" }
                    val modelsArr = pObj["models"] as? JsonArray ?: continue
                    for (mEl in modelsArr) {
                        when (mEl) {
                            is JsonPrimitive -> if (mEl !is JsonNull && mEl.content.isNotBlank()) {
                                parsed += ModelOptionEntry(
                                    slug = mEl.content,
                                    provider = pSlug,
                                    label = mEl.content,
                                    isActiveGlobal = mEl.content == currentModel
                                )
                            }
                            is JsonObject -> {
                                val slug = mEl.str("id").ifBlank { mEl.str("model").ifBlank { mEl.str("slug") } }
                                if (slug.isNotBlank()) {
                                    parsed += ModelOptionEntry(
                                        slug = slug,
                                        provider = pSlug,
                                        label = mEl.str("label").ifBlank { slug },
                                        isActiveGlobal = slug == currentModel
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
            val flatModels = optsEl["models"] as? JsonArray
            val fallbackProvider = currentProvider.ifBlank { "openrouter" }
            flatModels?.forEach { mEl ->
                if (mEl is JsonPrimitive && mEl !is JsonNull && mEl.content.isNotBlank()) {
                    parsed += ModelOptionEntry(
                        slug = mEl.content,
                        provider = fallbackProvider,
                        isActiveGlobal = mEl.content == currentModel
                    )
                } else if (mEl is JsonObject) {
                    val slug = mEl.str("model").ifBlank { mEl.str("id") }
                    if (slug.isNotBlank()) {
                        parsed += ModelOptionEntry(
                            slug = slug,
                            provider = mEl.str("slug").ifBlank { mEl.str("provider") }.ifBlank { fallbackProvider },
                            label = mEl.str("label").ifBlank { slug },
                            isActiveGlobal = slug == currentModel
                        )
                    }
                }
            }
        }
        currentModel to parsed.distinctBy { "${it.provider}:${it.slug}" }
    }

    suspend fun setGlobalModel(profile: String, modelSlug: String, provider: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val cleanModel = modelSlug.trim()
            if (cleanModel.isEmpty()) return@withContext false
            val resolvedProvider = resolveProviderForAssignment(profile, cleanModel, provider)
            val payload = buildJsonObject {
                put("scope", "main")
                put("provider", resolvedProvider)
                put("model", cleanModel)
                put("confirm_expensive_model", true)
                if (profile.isNotBlank()) put("profile", profile.trim())
            }
            val path = CredentialGuards.appendProfileParam("/api/model/set", profile)
            runCatching {
                val res = postJson(path, payload.toString()) as? JsonObject
                res?.boolOrDefault("ok", true) ?: true
            }.getOrDefault(false)
        }

    private suspend fun resolveProviderForAssignment(
        profile: String,
        modelSlug: String,
        explicitProvider: String?
    ): String {
        val cleanExplicit = explicitProvider?.trim().orEmpty()
        if (cleanExplicit.isNotEmpty() &&
            !cleanExplicit.equals("auto", ignoreCase = true) &&
            !cleanExplicit.equals("hermes", ignoreCase = true)
        ) {
            return cleanExplicit
        }
        val activeProfileProvider = runCatching {
            val info = getJson(CredentialGuards.appendProfileParam("/api/model/info", profile)) as? JsonObject
            info?.str("provider").orEmpty().trim()
        }.getOrDefault("")
        if (activeProfileProvider.isNotEmpty() && !activeProfileProvider.equals("auto", ignoreCase = true)) {
            return activeProfileProvider
        }
        return if (modelSlug.contains('/')) "openrouter" else "custom"
    }

    // ── MCP Servers & Curated Catalog (`/api/mcp/*`) ──────────────────────────

    suspend fun fetchMcpServers(profile: String): List<McpServerEntry> = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/mcp/servers", profile))
        val result = mutableListOf<McpServerEntry>()
        val serversNode = (el as? JsonObject)?.get("servers") ?: el
        when (serversNode) {
            is JsonArray -> serversNode.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                result += parseMcpServerObject(o.str("name"), o)
            }
            is JsonObject -> serversNode.forEach { (name, item) ->
                val o = item as? JsonObject ?: return@forEach
                result += parseMcpServerObject(name, o)
            }
            else -> Unit
        }
        result
    }

    suspend fun addOrUpdateMcpServer(profile: String, entry: McpServerEntry): Boolean = withContext(Dispatchers.IO) {
        val cleanName = entry.name.trim()
        val cleanTarget = entry.commandOrUrl.trim()
        if (cleanName.isEmpty() || cleanTarget.isEmpty()) return@withContext false

        val payload = buildJsonObject {
            put("name", cleanName)
            when (entry.transport) {
                McpTransport.STDIO -> {
                    put("command", cleanTarget)
                    putJsonArray("args") { entry.args.forEach { add(it) } }
                }
                McpTransport.SSE, McpTransport.HTTP -> {
                    put("url", cleanTarget)
                }
            }
            if (entry.env.isNotEmpty()) {
                val safeEnv = CredentialGuards.filterSafeEnvUpdates(entry.env)
                if (safeEnv.isNotEmpty()) {
                    putJsonObject("env") { safeEnv.forEach { (k, v) -> put(k, v) } }
                }
            }
            if (profile.isNotBlank()) put("profile", profile.trim())
        }
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers", profile)
        val firstAttempt = runCatching { postJson(path, payload.toString()); true }
        if (firstAttempt.isSuccess) return@withContext true

        val errMsg = firstAttempt.exceptionOrNull()?.message.orEmpty()
        val isConflict = errMsg.contains("HTTP 409")
        val isPluginOwned = errMsg.contains("provided by plugin", ignoreCase = true)
        if (isConflict && !isPluginOwned) {
            val deleted = deleteMcpServer(profile, cleanName)
            if (deleted) {
                return@withContext runCatching { postJson(path, payload.toString()); true }.getOrDefault(false)
            }
        }
        false
    }

    suspend fun toggleMcpServer(profile: String, name: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val encName = CredentialGuards.encodePathSegment(name.trim())
        val payload = buildJsonObject {
            put("enabled", enabled)
            if (profile.isNotBlank()) put("profile", profile.trim())
        }
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers/$encName/enabled", profile)
        runCatching { putJson(path, payload.toString()); true }.getOrDefault(false)
    }

    suspend fun deleteMcpServer(profile: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val encName = CredentialGuards.encodePathSegment(name.trim())
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers/$encName", profile)
        runCatching { deleteJson(path); true }.getOrDefault(false)
    }

    suspend fun testMcpServer(profile: String, name: String): String = withContext(Dispatchers.IO) {
        val encName = CredentialGuards.encodePathSegment(name.trim())
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers/$encName/test", profile)
        runCatching {
            val res = postJson(path, "{}") as? JsonObject
            val toolsCount = (res?.get("tools") as? JsonArray)?.size ?: 0
            if (res?.bool("ok") == true) "Conectado ($toolsCount tools)" else (res?.str("error")?.ifBlank { null } ?: "Respuesta recibida")
        }.getOrElse { "Error: ${it.message}" }
    }

    suspend fun fetchMcpCatalog(profile: String): List<McpCatalogEntry> = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/mcp/catalog", profile))
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["entries"] as? JsonArray)
                ?: (el["catalog"] as? JsonArray)
                ?: (el["servers"] as? JsonArray)
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o.str("name").ifBlank { o.str("id") }.ifBlank { return@mapNotNull null }
            val authType = o.str("auth_type").ifBlank { o.str("auth") }
            val reqEnvArr = o["required_env"] as? JsonArray
            val requiredEnv = reqEnvArr?.mapNotNull { envEl ->
                when (envEl) {
                    is JsonObject -> {
                        val envName = envEl.str("env").ifBlank { envEl.str("key") }.ifBlank { return@mapNotNull null }
                        McpRequiredEnvUi(
                            env = envName,
                            label = envEl.str("label").ifBlank { envName },
                            required = envEl.boolOrDefault("required", true),
                            isSet = envEl.bool("is_set")
                        )
                    }
                    is JsonPrimitive -> if (envEl !is JsonNull && envEl.content.isNotBlank()) {
                        McpRequiredEnvUi(env = envEl.content)
                    } else null
                    else -> null
                }
            }.orEmpty()

            McpCatalogEntry(
                name = name,
                title = o.str("title").ifBlank { o.str("label") }.ifBlank { name },
                description = o.str("description"),
                transport = when (o.str("transport").lowercase()) {
                    "stdio" -> McpTransport.STDIO
                    "sse" -> McpTransport.SSE
                    else -> McpTransport.HTTP
                },
                requiresOauth = authType.equals("oauth", ignoreCase = true) ||
                    o.bool("oauth") ||
                    o.bool("requires_auth"),
                installed = o.bool("installed"),
                needsInstall = o.bool("needs_install"),
                requiredEnv = requiredEnv
            )
        }
    }

    suspend fun installMcpFromCatalog(
        profile: String,
        catalogName: String,
        env: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        val safeEnv = CredentialGuards.filterSafeEnvUpdates(env)
        val payload = buildJsonObject {
            put("name", catalogName.trim())
            put("enable", true)
            if (safeEnv.isNotEmpty()) {
                putJsonObject("env") { safeEnv.forEach { (k, v) -> put(k, v) } }
            }
            if (profile.isNotBlank()) put("profile", profile.trim())
        }
        val path = CredentialGuards.appendProfileParam("/api/mcp/catalog/install", profile)
        runCatching {
            val res = postJson(path, payload.toString()) as? JsonObject
            res?.boolOrDefault("ok", true) ?: true
        }.getOrDefault(false)
    }

    // ── Plugin Store & Security Scanner (`/api/dashboard/plugins/*`) ──────────

    suspend fun fetchPluginStoreCatalog(profile: String): List<PluginCatalogItemUi> = withContext(Dispatchers.IO) {
        val catEl = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/dashboard/plugins/catalog", profile))
        }.getOrNull()
        val hubEl = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/dashboard/plugins/hub", profile))
        }.getOrNull()

        val installedByCatalogOrName = mutableMapOf<String, JsonObject>()
        val hubRows = mutableListOf<JsonObject>()
        val hubArr = (hubEl as? JsonObject)?.get("plugins") as? JsonArray
        hubArr?.forEach { p ->
            val o = p as? JsonObject ?: return@forEach
            hubRows += o
            val catKey = o.str("catalog_name")
            val rowName = o.str("name")
            if (catKey.isNotBlank()) installedByCatalogOrName[catKey] = o
            if (rowName.isNotBlank()) installedByCatalogOrName.putIfAbsent(rowName, o)
        }

        val entriesArr = when (catEl) {
            is JsonArray -> catEl
            is JsonObject -> (catEl["entries"] as? JsonArray)
                ?: (catEl["catalog"] as? JsonArray)
                ?: (catEl["plugins"] as? JsonArray)
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        val combined = mutableListOf<PluginCatalogItemUi>()
        val matchedHubObjects = mutableSetOf<JsonObject>()

        for (item in entriesArr) {
            val o = item as? JsonObject ?: continue
            val catName = o.str("name")
            if (catName.isBlank()) continue
            val hubMatch = installedByCatalogOrName[catName]
            if (hubMatch != null) matchedHubObjects += hubMatch

            val isInstalled = hubMatch != null || o.bool("installed")
            // Profile-scoped `/api/dashboard/plugins/hub` runtime_status MUST take precedence over unscoped catalog!
            val runtimeStatus = hubMatch?.str("runtime_status")
                ?.ifBlank { hubMatch.str("status") }
                .orEmpty()
                .ifBlank { o.str("runtime_status").ifBlank { o.str("status") } }

            val installedDirName = hubMatch?.str("name").orEmpty().ifBlank { catName }
            val canonicalKey = hubMatch?.str("key").orEmpty().ifBlank { installedDirName }
            val updateAvail = (hubMatch?.bool("update_available") == true) || o.bool("update_available")
            val canRemove = hubMatch?.boolOrDefault("can_remove", hubMatch.str("source") != "bundled") ?: true
            val canUpdate = isInstalled && (
                updateAvail ||
                    (hubMatch?.bool("can_update_git") == true) ||
                    hubMatch?.str("catalog_name")?.isNotBlank() == true ||
                    true
                )

            combined += PluginCatalogItemUi(
                name = catName,
                description = o.str("description").ifBlank { hubMatch?.str("description").orEmpty() },
                repo = o.str("repo").ifBlank { hubMatch?.str("source").orEmpty() },
                shaShort = hubMatch?.str("installed_sha")?.take(7)
                    .orEmpty()
                    .ifBlank { o.str("sha_short").ifBlank { o.str("sha").take(7) } },
                tier = o.str("tier").ifBlank { hubMatch?.str("catalog_tier").orEmpty() }.ifBlank { "community" },
                maintainer = o.str("maintainer"),
                capabilitySummary = o.str("capability_summary"),
                installed = isInstalled,
                enabled = runtimeStatus == "enabled" || runtimeStatus == "active",
                updateAvailable = updateAvail,
                catalogName = catName,
                installedName = installedDirName,
                pluginKey = canonicalKey,
                canRemove = canRemove,
                canUpdate = canUpdate && canRemove,
                settingsSchema = parsePluginSettingsSchema(hubMatch?.get("settings_schema") as? JsonArray),
                scanFindings = parseScanFindings(hubMatch?.get("scan_findings") as? JsonArray)
            )
        }

        // Also surface local/git/bundled installed plugins not in the curated catalog
        for (o in hubRows) {
            if (o in matchedHubObjects) continue
            val rowName = o.str("name")
            if (rowName.isBlank()) continue
            val catName = o.str("catalog_name").ifBlank { rowName }
            val canonicalKey = o.str("key").ifBlank { rowName }
            val runtimeStatus = o.str("runtime_status").ifBlank { o.str("status") }
            val isBundled = o.str("source").equals("bundled", ignoreCase = true)
            val canRemove = o.boolOrDefault("can_remove", !isBundled)
            val updateAvail = o.bool("update_available")
            val canUpdate = canRemove && (updateAvail || o.bool("can_update_git") || o.str("catalog_name").isNotBlank())

            combined += PluginCatalogItemUi(
                name = rowName,
                description = o.str("description"),
                repo = o.str("source"),
                shaShort = o.str("installed_sha").take(7),
                tier = o.str("catalog_tier").ifBlank { if (isBundled) "official" else "local" },
                installed = true,
                enabled = runtimeStatus == "enabled" || runtimeStatus == "active",
                updateAvailable = updateAvail,
                catalogName = catName,
                installedName = rowName,
                pluginKey = canonicalKey,
                canRemove = canRemove,
                canUpdate = canUpdate,
                settingsSchema = parsePluginSettingsSchema(o["settings_schema"] as? JsonArray),
                scanFindings = parseScanFindings(o["scan_findings"] as? JsonArray)
            )
        }

        combined.sortedWith(compareByDescending<PluginCatalogItemUi> { it.installed }.thenBy { it.name })
    }

    suspend fun installPlugin(
        profile: String,
        catalogNameOrRepo: String,
        isCatalogName: Boolean,
        force: Boolean = false
    ): JsonObject? = withContext(Dispatchers.IO) {
        val clean = catalogNameOrRepo.trim()
        val payload = buildJsonObject {
            put("identifier", clean)
            if (isCatalogName) put("catalog_name", clean)
            put("enable", true)
            if (force) put("force", true)
        }
        val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/install", profile)
        runCatching { postJson(path, payload.toString()) as? JsonObject }.getOrNull()
    }

    suspend fun setPluginEnabled(profile: String, name: String, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val verb = if (enable) "enable" else "disable"
        val encName = CredentialGuards.encodeMultiSegmentPath(name)
        val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/$encName/$verb", profile)
        runCatching {
            val res = postJson(path, "{}") as? JsonObject
            res?.boolOrDefault("ok", true) ?: true
        }.getOrDefault(false)
    }

    suspend fun removePlugin(profile: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val encName = CredentialGuards.encodeMultiSegmentPath(name)
        val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/$encName", profile)
        runCatching {
            val res = deleteJson(path) as? JsonObject
            res?.boolOrDefault("ok", true) ?: true
        }.getOrDefault(false)
    }

    suspend fun updatePlugin(
        profile: String,
        installedName: String,
        acceptCapabilities: Boolean = false,
        force: Boolean = false
    ): PluginUpdateOutcome = withContext(Dispatchers.IO) {
        val cleanName = installedName.trim()
        if (cleanName.isEmpty()) return@withContext PluginUpdateOutcome.Failed("Nombre de plugin vacío")
        val encName = CredentialGuards.encodeMultiSegmentPath(cleanName)
        val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/$encName/update", profile)
        val payload = buildJsonObject {
            put("accept_capabilities", acceptCapabilities)
            put("force", force)
        }
        runCatching {
            val res = postJson(path, payload.toString()) as? JsonObject
            parsePluginUpdateOutcome(cleanName, res)
        }.getOrElse { e ->
            PluginUpdateOutcome.Failed(e.message ?: "Error al actualizar '$cleanName'")
        }
    }

    // ── Low-Level HTTP Helpers ────────────────────────────────────────────────

    internal fun buildRequest(path: String, baseUrl: String = activeBaseUrl): Request.Builder {
        val fullUrl = CredentialGuards.normalizeBaseUrl(baseUrl) + (if (path.startsWith("/")) path else "/$path")
        val builder = Request.Builder().url(fullUrl)
        CredentialGuards.extractTokenFromUrlOrRaw(loopbackSessionToken)?.let {
            builder.header("X-Hermes-Session-Token", it)
        }
        bearerAccessToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer ${it.trim()}")
        }
        return builder
    }

    private suspend inline fun <reified T> getTyped(
        path: String,
        baseUrl: String = activeBaseUrl,
        clientOverride: OkHttpClient? = null
    ): T = withContext(Dispatchers.IO) {
        val req = buildRequest(path, baseUrl).get().build()
        val client = clientOverride ?: httpClient
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} on GET $path: ${bodyStr.take(180)}")
            json.decodeFromString<T>(bodyStr)
        }
    }

    private suspend inline fun <reified T> postTyped(
        path: String,
        jsonBody: String,
        baseUrl: String = activeBaseUrl
    ): T = withContext(Dispatchers.IO) {
        val req = buildRequest(path, baseUrl).post(jsonBody.toRequestBody(jsonMedia)).build()
        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} on POST $path: ${bodyStr.take(180)}")
            json.decodeFromString<T>(bodyStr)
        }
    }

    suspend fun getJson(path: String, baseUrl: String = activeBaseUrl): JsonElement = withContext(Dispatchers.IO) {
        val req = buildRequest(path, baseUrl).get().build()
        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} on GET $path: ${bodyStr.take(180)}")
            if (bodyStr.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(bodyStr)
        }
    }

    suspend fun postJson(path: String, jsonBody: String, baseUrl: String = activeBaseUrl): JsonElement =
        withContext(Dispatchers.IO) {
            val req = buildRequest(path, baseUrl).post(jsonBody.toRequestBody(jsonMedia)).build()
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code} on POST $path: ${bodyStr.take(180)}")
                if (bodyStr.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(bodyStr)
            }
        }

    suspend fun putJson(path: String, jsonBody: String, baseUrl: String = activeBaseUrl): JsonElement =
        withContext(Dispatchers.IO) {
            val req = buildRequest(path, baseUrl).put(jsonBody.toRequestBody(jsonMedia)).build()
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code} on PUT $path: ${bodyStr.take(180)}")
                if (bodyStr.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(bodyStr)
            }
        }

    suspend fun deleteJson(path: String, baseUrl: String = activeBaseUrl): JsonElement =
        withContext(Dispatchers.IO) {
            val req = buildRequest(path, baseUrl).delete().build()
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code} on DELETE $path: ${bodyStr.take(180)}")
                if (bodyStr.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(bodyStr)
            }
        }

    private fun parseMcpServerObject(fallbackName: String, o: JsonObject): McpServerEntry {
        val url = o.str("url")
        val cmd = o.str("command")
        val transport = when {
            o.str("transport").equals("sse", true) -> McpTransport.SSE
            o.str("transport").equals("http", true) || url.isNotBlank() -> McpTransport.HTTP
            else -> McpTransport.STDIO
        }
        val args = (o["args"] as? JsonArray)?.mapNotNull {
            if (it is JsonPrimitive && it !is JsonNull) it.content else null
        }.orEmpty()
        val tools = (o["tools"] as? JsonArray)?.mapNotNull {
            when (it) {
                is JsonPrimitive -> if (it is JsonNull) null else it.content
                is JsonObject -> it.str("name").takeIf { n -> n.isNotBlank() }
                else -> null
            }
        }.orEmpty()
        return McpServerEntry(
            name = o.str("name").ifBlank { fallbackName },
            transport = transport,
            commandOrUrl = url.ifBlank { cmd },
            args = args,
            enabled = o.boolOrDefault("enabled", true),
            connected = o.bool("connected") || o.str("status") == "connected",
            tools = tools,
            statusDetail = o.str("status").ifBlank { if (o.boolOrDefault("enabled", true)) "Activo" else "Pausado" }
        )
    }

    companion object {
        /**
         * Safely converts a `PluginSettingField` JSON `value` or `default` element into its UI
         * draft string without turning `JsonNull` (`JsonPrimitive` subtype!) into `"null"` or
         * dropping `JsonObject`/`JsonArray` (`type: "json"`) values (`GF1-03` / `GF2-05`).
         */
        internal fun jsonElementToSettingString(el: JsonElement?): String = when (el) {
            null, is JsonNull -> ""
            is JsonPrimitive -> el.content
            is JsonObject, is JsonArray -> el.toString()
        }

        internal fun parsePluginSettingsSchema(arr: JsonArray?): List<PluginSettingFieldUi> {
            if (arr == null) return emptyList()
            return arr.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val key = o.str("key").ifBlank { return@mapNotNull null }
                val choices = (o["choices"] as? JsonArray)?.mapNotNull {
                    if (it is JsonPrimitive && it !is JsonNull) it.content else null
                }.orEmpty()
                PluginSettingFieldUi(
                    key = key,
                    type = o.str("type").ifBlank { "string" },
                    label = o.str("label").ifBlank { key },
                    description = o.str("description"),
                    required = o.bool("required"),
                    currentValue = jsonElementToSettingString(o["value"]),
                    defaultValue = jsonElementToSettingString(o["default"]),
                    choices = choices,
                    envKey = o.str("env").takeIf { it.isNotBlank() },
                    hasSecretValue = o.bool("has_value")
                )
            }
        }

        internal fun parseScanFindings(arr: JsonArray?): List<PluginScanIssueUi> {
            if (arr == null) return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                PluginScanIssueUi(
                    severity = o.str("severity").ifBlank { "info" },
                    category = o.str("category").ifBlank { "security" },
                    file = o.str("file"),
                    line = (o["line"] as? JsonPrimitive)?.intOrNull ?: 0,
                    description = o.str("description")
                )
            }
        }

        internal fun parsePluginUpdateOutcome(pluginName: String, obj: JsonObject?): PluginUpdateOutcome {
            if (obj == null) return PluginUpdateOutcome.Failed("Sin respuesta del servidor para '$pluginName'")

            if (obj.bool("consent_required")) {
                val linesFromArr = (obj["delta_lines"] as? JsonArray)?.mapNotNull {
                    if (it is JsonPrimitive && it !is JsonNull && it.content.isNotBlank()) it.content else null
                }.orEmpty()
                val fallbackDelta = if (linesFromArr.isEmpty()) {
                    when (val d = obj["delta"]) {
                        is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
                        is JsonObject -> d.entries.map { (k, v) -> "$k: ${jsonElementToSettingString(v)}" }
                        else -> emptyList()
                    }
                } else {
                    emptyList()
                }
                return PluginUpdateOutcome.ConsentRequired(
                    pluginName = obj.str("name").ifBlank { pluginName },
                    sha = obj.str("sha"),
                    deltaLines = (linesFromArr + fallbackDelta).ifEmpty {
                        listOf("Esta actualización declara permisos adicionales en el manifiesto del plugin.")
                    }
                )
            }

            if (obj.bool("scan_blocked")) {
                val verdict = obj.str("scan_verdict").ifBlank { "dangerous" }
                val findings = parseScanFindings(obj["scan_findings"] as? JsonArray)
                return PluginUpdateOutcome.ScanBlocked(
                    pluginName = obj.str("name").ifBlank { pluginName },
                    verdict = verdict,
                    findings = findings,
                    canForce = verdict.equals("caution", ignoreCase = true)
                )
            }

            if (obj.bool("ok")) {
                return PluginUpdateOutcome.Applied(
                    sha = obj.str("sha"),
                    unchanged = obj.bool("unchanged"),
                    restartRequired = obj.bool("restart_required")
                )
            }

            return PluginUpdateOutcome.Failed(
                obj.str("error").ifBlank { "No se pudo actualizar '$pluginName'" }
            )
        }

        fun defaultRoleForHandle(handle: String): String = when (handle.lowercase()) {
            "hermes", "default" -> "Agente principal multipropósito y coordinador personal"
            "orchestrator" -> "Director de escuadrones: divide proyectos y coordina especialistas"
            "programador" -> "Especialista en código, DevOps, servidores, Cloudflare Workers y ESPHome"
            "disenador" -> "Arquitecto UI/UX, sistemas de diseño, prototipos y artefactos visuales"
            "domo" -> "Especialista Home Assistant: luces, sensores, clima y automatizaciones"
            "sabio" -> "Investigación profunda, síntesis documental y análisis crítico"
            "redactor" -> "Copywriting, documentos ejecutivos, guiones y comunicación"
            "rapidito" -> "Respuestas ultrarrápidas y directas sin latencia de herramientas"
            else -> "Agente especialista Hermes"
        }

        private fun inferProviderGroup(envKey: String): String = when {
            envKey.startsWith("OPENAI") -> "OpenAI / Custom"
            envKey.startsWith("ANTHROPIC") -> "Anthropic"
            envKey.startsWith("GEMINI") || envKey.startsWith("GOOGLE") -> "Google Gemini"
            envKey.startsWith("OPENROUTER") -> "OpenRouter"
            envKey.startsWith("NOUS") -> "Nous Research"
            envKey.startsWith("GROQ") -> "Groq"
            envKey.startsWith("CEREBRAS") -> "Cerebras"
            envKey.startsWith("ELEVENLABS") -> "Voz / ElevenLabs"
            else -> "Integraciones & Sistema"
        }
    }
}

fun JsonObject.str(key: String): String {
    val el = this[key] ?: return ""
    if (el is JsonNull) return ""
    return (el as? JsonPrimitive)?.content.orEmpty()
}

fun JsonObject.bool(key: String): Boolean {
    val el = this[key] ?: return false
    if (el is JsonNull) return false
    return (el as? JsonPrimitive)?.booleanOrNull ?: false
}

fun JsonObject.boolOrDefault(key: String, default: Boolean): Boolean {
    val el = this[key] ?: return default
    if (el is JsonNull) return default
    return (el as? JsonPrimitive)?.booleanOrNull ?: default
}
