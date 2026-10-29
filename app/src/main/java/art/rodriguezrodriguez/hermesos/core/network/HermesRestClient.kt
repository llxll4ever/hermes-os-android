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

/**
 * Observable Connection Ladder + Typed REST Client for `hermes dashboard` (port 9119).
 *
 * Implements the precedence rules from `apps/desktop/ENGINEERING.md`:
 * 1. Ordered candidate rungs (Tailscale Direct -> Cloudflare Tunnel -> Custom URL).
 * 2. Fast probe on `/api/health`, followed by session verification on `/api/auth/me`.
 * 3. Every profile-scoped endpoint carries `?profile=<name>` so multi-profile destructive
 *    routes never 400.
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
                    val meResult = runCatching { getAuthMe(base) }
                    val me = meResult.getOrNull()
                    val status = when {
                        !health.authRequired -> LinkStatus.CONNECTED
                        me != null && me.userId.isNotBlank() -> LinkStatus.CONNECTED
                        else -> LinkStatus.AUTH_REQUIRED
                    }
                    return@withContext ConnectionSnapshot(
                        status = status,
                        activeRung = rung.copy(baseUrl = base),
                        rungs = rungs,
                        serverVersion = health.displayVersion.ifBlank { health.version },
                        authRequired = health.authRequired,
                        authenticatedUser = me?.displayName?.ifBlank { me.userId }.orEmpty(),
                        latencyMs = elapsed,
                        lastError = null
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

    // ── Auth & WS Ticket Endpoints ────────────────────────────────────────────

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
     * Mints a fresh single-use 30-second ticket for `WS /api/ws`.
     * Never cached or reused across reconnects.
     */
    suspend fun mintWsTicket(baseUrl: String = activeBaseUrl): String? = withContext(Dispatchers.IO) {
        runCatching {
            postTyped<WsTicketResponse>("/api/auth/ws-ticket", "{}", baseUrl = baseUrl).ticket.takeIf { it.isNotBlank() }
        }.getOrNull()
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
        val obj = getJson("/api/profiles/$profile/soul") as? JsonObject
        obj?.str("content")?.ifBlank { obj.str("soul") }.orEmpty()
    }

    suspend fun updateProfileSoul(profile: String, soulMarkdown: String): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("content", soulMarkdown) }
        runCatching { putJson("/api/profiles/$profile/soul", payload.toString()); true }.getOrDefault(false)
    }

    suspend fun setProfileModel(profile: String, modelSlug: String, provider: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("model", modelSlug)
                if (!provider.isNullOrBlank()) put("provider", provider)
            }
            runCatching { putJson("/api/profiles/$profile/model", payload.toString()); true }.getOrDefault(false)
        }

    // ── Configs, Providers & Models (`/api/config`, `/api/env`, `/api/model`) ─

    suspend fun fetchRawConfigYaml(profile: String): String = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/config/raw", profile))
        when (el) {
            is JsonObject -> el.str("content").ifBlank { el.str("yaml") }.ifBlank { el.str("raw") }
            is JsonPrimitive -> el.content
            else -> ""
        }
    }

    suspend fun saveRawConfigYaml(profile: String, yamlText: String): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("content", yamlText)
            put("raw", yamlText)
        }
        val path = CredentialGuards.appendProfileParam("/api/config/raw", profile)
        runCatching { putJson(path, payload.toString()); true }.getOrDefault(false)
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
                        val preview = o.str("value").ifBlank { o.str("preview") }
                        result += EnvVarEntry(
                            key = key,
                            valuePreview = preview,
                            isConfigured = o.bool("configured") || o.bool("is_set") || preview.isNotBlank(),
                            isSecret = o.boolOrDefault("secret", true),
                            providerGroup = o.str("category").ifBlank { o.str("provider").ifBlank { inferProviderGroup(key) } }
                        )
                    }
                } else if (varsNode is JsonObject) {
                    for ((k, v) in varsNode) {
                        val preview = (v as? JsonPrimitive)?.content.orEmpty()
                        result += EnvVarEntry(
                            key = k,
                            valuePreview = preview,
                            isConfigured = preview.isNotBlank(),
                            isSecret = true,
                            providerGroup = inferProviderGroup(k)
                        )
                    }
                }
            }
            else -> Unit
        }
        result.sortedBy { it.key }
    }

    suspend fun updateEnvVars(profile: String, updates: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val safeUpdates = CredentialGuards.filterSafeEnvUpdates(updates)
        if (safeUpdates.isEmpty()) return@withContext false
        val payload = buildJsonObject {
            putJsonObject("env") {
                safeUpdates.forEach { (k, v) -> put(k, v) }
            }
            safeUpdates.forEach { (k, v) -> put(k, v) }
        }
        val path = CredentialGuards.appendProfileParam("/api/env", profile)
        runCatching { putJson(path, payload.toString()); true }.getOrDefault(false)
    }

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
                apiKeyPreview = o.str("api_key"),
                defaultModel = o.str("model").ifBlank { o.str("default_model") },
                active = o.bool("active")
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
        val payload = buildJsonObject {
            put("name", name.trim())
            put("base_url", baseUrl.trim())
            if (apiKey.isNotBlank() && !CredentialGuards.isRedactedPreview(apiKey)) {
                put("api_key", apiKey.trim())
            }
            if (defaultModel.isNotBlank()) put("model", defaultModel.trim())
        }
        val path = CredentialGuards.appendProfileParam("/api/providers/custom-endpoints", profile)
        runCatching { postJson(path, payload.toString()); true }.getOrDefault(false)
    }

    suspend fun deleteCustomEndpoint(profile: String, endpointId: String): Boolean = withContext(Dispatchers.IO) {
        val path = CredentialGuards.appendProfileParam("/api/providers/custom-endpoints/$endpointId", profile)
        runCatching { deleteJson(path); true }.getOrDefault(false)
    }

    suspend fun fetchModelOptions(profile: String): Pair<String, List<ModelOptionEntry>> = withContext(Dispatchers.IO) {
        val infoObj = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/model/info", profile)) as? JsonObject
        }.getOrNull()
        val currentModel = infoObj?.str("model").orEmpty()

        val optsEl = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/model/options", profile))
        }.getOrNull()

        val parsed = mutableListOf<ModelOptionEntry>()
        if (optsEl is JsonObject) {
            val providersArr = optsEl["providers"] as? JsonArray
            if (providersArr != null) {
                for (pEl in providersArr) {
                    val pObj = pEl as? JsonObject ?: continue
                    val pName = pObj.str("provider").ifBlank { pObj.str("name").ifBlank { "custom" } }
                    val modelsArr = pObj["models"] as? JsonArray ?: continue
                    for (mEl in modelsArr) {
                        when (mEl) {
                            is JsonPrimitive -> parsed += ModelOptionEntry(
                                slug = mEl.content,
                                provider = pName,
                                label = mEl.content,
                                isActiveGlobal = mEl.content == currentModel
                            )
                            is JsonObject -> {
                                val slug = mEl.str("id").ifBlank { mEl.str("model").ifBlank { mEl.str("slug") } }
                                if (slug.isNotBlank()) {
                                    parsed += ModelOptionEntry(
                                        slug = slug,
                                        provider = pName,
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
            flatModels?.forEach { mEl ->
                if (mEl is JsonPrimitive) {
                    parsed += ModelOptionEntry(slug = mEl.content, provider = "hermes", isActiveGlobal = mEl.content == currentModel)
                } else if (mEl is JsonObject) {
                    val slug = mEl.str("model").ifBlank { mEl.str("id") }
                    if (slug.isNotBlank()) {
                        parsed += ModelOptionEntry(
                            slug = slug,
                            provider = mEl.str("provider").ifBlank { "hermes" },
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
            val payload = buildJsonObject {
                put("model", modelSlug.trim())
                if (!provider.isNullOrBlank()) put("provider", provider.trim())
            }
            val path = CredentialGuards.appendProfileParam("/api/model/set", profile)
            runCatching { postJson(path, payload.toString()); true }.getOrDefault(false)
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
        val payload = buildJsonObject {
            put("name", entry.name.trim())
            when (entry.transport) {
                McpTransport.STDIO -> {
                    put("transport", "stdio")
                    put("command", entry.commandOrUrl.trim())
                    putJsonArray("args") { entry.args.forEach { add(it) } }
                }
                McpTransport.SSE -> {
                    put("transport", "sse")
                    put("url", entry.commandOrUrl.trim())
                }
                McpTransport.HTTP -> {
                    put("transport", "http")
                    put("url", entry.commandOrUrl.trim())
                }
            }
            if (entry.env.isNotEmpty()) {
                putJsonObject("env") { entry.env.forEach { (k, v) -> put(k, v) } }
            }
            put("enabled", entry.enabled)
        }
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers", profile)
        runCatching { postJson(path, payload.toString()); true }.getOrDefault(false)
    }

    suspend fun toggleMcpServer(profile: String, name: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("enabled", enabled) }
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers/$name/enabled", profile)
        runCatching { putJson(path, payload.toString()); true }.getOrDefault(false)
    }

    suspend fun deleteMcpServer(profile: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers/$name", profile)
        runCatching { deleteJson(path); true }.getOrDefault(false)
    }

    suspend fun testMcpServer(profile: String, name: String): String = withContext(Dispatchers.IO) {
        val path = CredentialGuards.appendProfileParam("/api/mcp/servers/$name/test", profile)
        runCatching {
            val res = postJson(path, "{}") as? JsonObject
            val toolsCount = (res?.get("tools") as? JsonArray)?.size ?: 0
            if (res?.bool("ok") == true) "Conectado ($toolsCount tools)" else (res?.str("error") ?: "Respuesta recibida")
        }.getOrElse { "Error: ${it.message}" }
    }

    suspend fun fetchMcpCatalog(profile: String): List<McpCatalogEntry> = withContext(Dispatchers.IO) {
        val el = getJson(CredentialGuards.appendProfileParam("/api/mcp/catalog", profile))
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["catalog"] as? JsonArray) ?: (el["servers"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val name = o.str("name").ifBlank { o.str("id") }.ifBlank { return@mapNotNull null }
            McpCatalogEntry(
                name = name,
                title = o.str("title").ifBlank { name },
                description = o.str("description"),
                transport = when (o.str("transport").lowercase()) {
                    "stdio" -> McpTransport.STDIO
                    "sse" -> McpTransport.SSE
                    else -> McpTransport.HTTP
                },
                requiresOauth = o.bool("oauth") || o.bool("requires_auth"),
                installed = o.bool("installed")
            )
        }
    }

    suspend fun installMcpFromCatalog(profile: String, catalogName: String): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("name", catalogName) }
        val path = CredentialGuards.appendProfileParam("/api/mcp/catalog/install", profile)
        runCatching { postJson(path, payload.toString()); true }.getOrDefault(false)
    }

    // ── Plugin Store & Security Scanner (`/api/dashboard/plugins/*`) ──────────

    suspend fun fetchPluginStoreCatalog(profile: String): List<PluginCatalogItemUi> = withContext(Dispatchers.IO) {
        val catEl = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/dashboard/plugins/catalog", profile))
        }.getOrNull()
        val hubEl = runCatching {
            getJson(CredentialGuards.appendProfileParam("/api/dashboard/plugins/hub", profile))
        }.getOrNull()

        val installedByName = mutableMapOf<String, JsonObject>()
        val hubArr = (hubEl as? JsonObject)?.get("plugins") as? JsonArray
        hubArr?.forEach { p ->
            val o = p as? JsonObject ?: return@forEach
            val key = o.str("catalog_name").ifBlank { o.str("name") }
            if (key.isNotBlank()) installedByName[key] = o
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
        val seen = mutableSetOf<String>()

        for (item in entriesArr) {
            val o = item as? JsonObject ?: continue
            val name = o.str("name")
            if (name.isBlank()) continue
            seen += name
            val hubMatch = installedByName[name]
            val isInstalled = o.bool("installed") || hubMatch != null
            val runtimeStatus = o.str("runtime_status").ifBlank { hubMatch?.str("status").orEmpty() }
            combined += PluginCatalogItemUi(
                name = name,
                description = o.str("description"),
                repo = o.str("repo"),
                shaShort = o.str("sha_short").ifBlank { o.str("sha").take(7) },
                tier = o.str("tier").ifBlank { "community" },
                maintainer = o.str("maintainer"),
                capabilitySummary = o.str("capability_summary"),
                installed = isInstalled,
                enabled = runtimeStatus == "enabled" || runtimeStatus == "active",
                updateAvailable = o.bool("update_available") || (hubMatch?.bool("update_available") == true),
                settingsSchema = parsePluginSettingsSchema(hubMatch?.get("settings_schema") as? JsonArray)
            )
        }

        // Also surface local/git-installed plugins not in the curated catalog
        for ((name, o) in installedByName) {
            if (name in seen) continue
            val status = o.str("status")
            combined += PluginCatalogItemUi(
                name = name,
                description = o.str("description"),
                repo = o.str("source"),
                shaShort = o.str("installed_sha").take(7),
                tier = o.str("catalog_tier").ifBlank { "local" },
                installed = true,
                enabled = status == "enabled" || status == "active",
                updateAvailable = o.bool("update_available"),
                settingsSchema = parsePluginSettingsSchema(o["settings_schema"] as? JsonArray)
            )
        }

        combined.sortedWith(compareByDescending<PluginCatalogItemUi> { it.installed }.thenBy { it.name })
    }

    suspend fun installPlugin(profile: String, catalogNameOrRepo: String, isCatalogName: Boolean): JsonObject? =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("identifier", catalogNameOrRepo.trim())
                if (isCatalogName) put("catalog_name", catalogNameOrRepo.trim())
                put("enable", true)
            }
            val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/install", profile)
            runCatching { postJson(path, payload.toString()) as? JsonObject }.getOrNull()
        }

    suspend fun setPluginEnabled(profile: String, name: String, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val verb = if (enable) "enable" else "disable"
        val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/$name/$verb", profile)
        runCatching { postJson(path, "{}"); true }.getOrDefault(false)
    }

    suspend fun removePlugin(profile: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val path = CredentialGuards.appendProfileParam("/api/dashboard/agent-plugins/$name", profile)
        runCatching { deleteJson(path); true }.getOrDefault(false)
    }

    // ── Low-Level HTTP Helpers ────────────────────────────────────────────────

    private fun buildRequest(path: String, baseUrl: String = activeBaseUrl): Request.Builder {
        val fullUrl = CredentialGuards.normalizeBaseUrl(baseUrl) + (if (path.startsWith("/")) path else "/$path")
        val builder = Request.Builder().url(fullUrl)
        loopbackSessionToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("X-Hermes-Session-Token", it)
        }
        bearerAccessToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
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
            if (!resp.isSuccessful) error("HTTP ${resp.code} on GET $path")
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
                if (!resp.isSuccessful) error("HTTP ${resp.code} on DELETE $path")
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
        val args = (o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val tools = (o["tools"] as? JsonArray)?.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.content
                is JsonObject -> it.str("name")
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
            statusDetail = o.str("status").ifBlank { if (o.bool("enabled")) "Activo" else "Pausado" }
        )
    }

    private fun parsePluginSettingsSchema(arr: JsonArray?): List<PluginSettingFieldUi> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val key = o.str("key").ifBlank { return@mapNotNull null }
            PluginSettingFieldUi(
                key = key,
                type = o.str("type").ifBlank { "string" },
                label = o.str("label").ifBlank { key },
                description = o.str("description"),
                required = o.bool("required"),
                currentValue = (o["value"] as? JsonPrimitive)?.content.orEmpty(),
                choices = (o["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                envKey = o.str("env").takeIf { it.isNotBlank() },
                hasSecretValue = o.bool("has_value")
            )
        }
    }

    companion object {
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

fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content
    ?.takeUnless { it == "null" }.orEmpty()

fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun JsonObject.boolOrDefault(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
