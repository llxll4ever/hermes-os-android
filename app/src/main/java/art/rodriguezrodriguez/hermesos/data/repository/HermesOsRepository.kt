package art.rodriguezrodriguez.hermesos.data.repository

import art.rodriguezrodriguez.hermesos.core.model.*
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.network.str
import art.rodriguezrodriguez.hermesos.core.plugins.PluginSettingsCodec
import art.rodriguezrodriguez.hermesos.core.rpc.GatewayEventFrame
import art.rodriguezrodriguez.hermesos.core.rpc.HermesRpcClient
import art.rodriguezrodriguez.hermesos.core.rpc.ManagedPluginRpcInfo
import art.rodriguezrodriguez.hermesos.core.rpc.ServerRpcRequest
import art.rodriguezrodriguez.hermesos.core.storage.WorkspacePreferencesStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative state & action backbone of Hermes OS Android.
 *
 * Unifies:
 * - Connection Ladder & Authentication
 * - Live Bot Wall (merges `/api/profiles`, `/api/plugins/hermes-mobile-wall/wall`, and live `WS /api/ws` frames)
 * - Bot Teams (`groups.*` Hosted Rooms) & 1:1 Bot Sessions
 * - Configs, Providers, Custom Endpoints & Manual Model Registry
 * - MCP Server Connector + Curated MCP Catalog
 * - Plugin Store Search, Install/Toggle/Remove & Settings
 * - Voice Command Dispatch
 */
class HermesOsRepository(
    val restClient: HermesRestClient,
    val rpcClient: HermesRpcClient,
    val prefsStore: WorkspacePreferencesStore,
    private val scope: CoroutineScope
) {
    private val _connection = MutableStateFlow(ConnectionSnapshot())
    val connection: StateFlow<ConnectionSnapshot> = _connection.asStateFlow()

    private val _bots = MutableStateFlow(WorkspacePreferencesStore.fallbackRosterCards())
    val bots: StateFlow<List<BotProfileCard>> = _bots.asStateFlow()

    private val _teamRooms = MutableStateFlow<List<BotTeamRoom>>(emptyList())
    val teamRooms: StateFlow<List<BotTeamRoom>> = _teamRooms.asStateFlow()

    private val _messagesByScope = MutableStateFlow<Map<String, List<ChatTurnMessage>>>(emptyMap())
    val messagesByScope: StateFlow<Map<String, List<ChatTurnMessage>>> = _messagesByScope.asStateFlow()

    private val _approvals = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val approvals: StateFlow<List<PendingToolApproval>> = _approvals.asStateFlow()

    private val _models = MutableStateFlow<List<ModelOptionEntry>>(emptyList())
    val models: StateFlow<List<ModelOptionEntry>> = _models.asStateFlow()

    private val _activeGlobalModel = MutableStateFlow("")
    val activeGlobalModel: StateFlow<String> = _activeGlobalModel.asStateFlow()

    private val _envVars = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val envVars: StateFlow<List<EnvVarEntry>> = _envVars.asStateFlow()

    private val _customEndpoints = MutableStateFlow<List<CustomEndpointEntry>>(emptyList())
    val customEndpoints: StateFlow<List<CustomEndpointEntry>> = _customEndpoints.asStateFlow()

    private val _rawConfigYaml = MutableStateFlow("")
    val rawConfigYaml: StateFlow<String> = _rawConfigYaml.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpServers: StateFlow<List<McpServerEntry>> = _mcpServers.asStateFlow()

    private val _mcpCatalog = MutableStateFlow<List<McpCatalogEntry>>(emptyList())
    val mcpCatalog: StateFlow<List<McpCatalogEntry>> = _mcpCatalog.asStateFlow()

    private val _pluginStore = MutableStateFlow<List<PluginCatalogItemUi>>(emptyList())
    val pluginStore: StateFlow<List<PluginCatalogItemUi>> = _pluginStore.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    // Maps bot profile -> active gateway session_id
    private val botSessions = ConcurrentHashMap<String, String>()
    private val sessionToProfile = ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            rpcClient.events.collect { frame -> onGatewayEvent(frame) }
        }
        scope.launch {
            rpcClient.serverRequests.collect { req -> onServerRequest(req) }
        }
        scope.launch {
            rpcClient.connected.collect { wsUp ->
                _connection.update { it.copy(wsConnected = wsUp) }
            }
        }
    }

    // ── Connection & Login Lifecycle ──────────────────────────────────────────

    suspend fun connectUsingLadder() {
        _connection.update { it.copy(status = LinkStatus.PROBING, lastError = null) }
        val rungs = prefsStore.connectionRungsFlow.first()
        val snap = restClient.resolveConnectionLadder(rungs)
        _connection.value = snap

        if (snap.status == LinkStatus.CONNECTED) {
            val wsOk = rpcClient.connect(authRequired = snap.authRequired)
            _connection.update { it.copy(wsConnected = wsOk) }
            refreshAllData()
        }
    }

    suspend fun login(username: String, password: String): Boolean {
        return runCatching {
            val res = restClient.loginWithPassword(username = username.trim(), password = password)
            if (res.ok) {
                connectUsingLadder()
                true
            } else {
                _statusBanner.value = "Credenciales rechazadas por Hermes Dashboard"
                false
            }
        }.getOrElse { e ->
            _statusBanner.value = "Error de login: ${e.message}"
            false
        }
    }

    suspend fun updateConnectionRungs(rungs: List<ConnectionRung>) {
        prefsStore.saveConnectionRungs(rungs)
        connectUsingLadder()
    }

    suspend fun refreshAllData(managementProfile: String = "default") = coroutineScope {
        launch { refreshBotWall() }
        launch { refreshTeamRooms() }
        launch { refreshModels(managementProfile) }
        launch { refreshProvidersAndConfig(managementProfile) }
        launch { refreshMcpData(managementProfile) }
        launch { refreshPluginStore(managementProfile) }
    }

    // ── Live Bot Wall ("Merge, don't clobber") ────────────────────────────────

    suspend fun refreshBotWall() {
        val remoteProfiles = runCatching { restClient.fetchProfiles() }.getOrDefault(emptyList())
        val wallTelemetry = runCatching {
            restClient.getJson("/api/plugins/hermes-mobile-wall/wall") as? JsonObject
        }.getOrNull()
        val wallBotsObj = wallTelemetry?.get("bots") as? JsonObject

        _bots.update { current -> BotWallMerge.merge(current, remoteProfiles, wallBotsObj) }
        prefsStore.cacheBotWallForHomeWidgets(_bots.value)
    }

    // ── Bot Teams (`Hosted Rooms`) & 1:1 Chat ─────────────────────────────────

    suspend fun refreshTeamRooms() {
        if (!rpcClient.connected.value) return
        val rooms = runCatching { rpcClient.listTeamRooms() }.getOrDefault(emptyList())
        _teamRooms.value = rooms
    }

    suspend fun createTeamRoom(name: String, memberProfiles: List<String>): BotTeamRoom? {
        if (!rpcClient.connected.value) {
            _statusBanner.value = "Conecta al gateway para crear equipos en vivo"
            return null
        }
        val created = runCatching { rpcClient.createTeamRoom(name, memberProfiles) }.getOrNull()
        if (created != null) {
            _teamRooms.update { listOf(created) + it.filterNot { r -> r.roomId == created.roomId } }
            _statusBanner.value = "Equipo '${created.name}' creado con ${created.members.size} bots"
        }
        return created
    }

    suspend fun sendMessageToScope(scopeId: String, text: String, isVoiceTurn: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        // Durable optimistic user row at send (`apps/desktop/ENGINEERING.md`)
        val userMsg = ChatTurnMessage(
            id = "usr-${UUID.randomUUID()}",
            scopeId = scopeId,
            authorHandle = "tú",
            authorKind = AuthorKind.USER,
            content = clean
        )
        appendMessage(scopeId, userMsg)

        if (scopeId.startsWith("room:")) {
            val roomId = scopeId.removePrefix("room:")
            runCatching {
                rpcClient.sendTeamRoomMessage(roomId, clean)
                delay(650)
                syncRoomTranscript(roomId)
            }.onFailure { e ->
                _statusBanner.value = "Error enviando a sala: ${e.message}"
            }
        } else {
            val profile = scopeId.removePrefix("bot:").ifBlank { "default" }
            updateBotState(profile, BotLiveState.THINKING, "Procesando: ${clean.take(48)}…")
            runCatching {
                val sid = botSessions[profile] ?: rpcClient.createOrResumeSession(profile).also { newId ->
                    if (newId.isNotBlank()) {
                        botSessions[profile] = newId
                        sessionToProfile[newId] = profile
                    }
                }
                rpcClient.submitPrompt(sessionId = sid, profile = profile, text = clean, voiceTurn = isVoiceTurn)
            }.onFailure { e ->
                updateBotState(profile, BotLiveState.ERROR, "Sin conexión WS: ${e.message}")
            }
        }
    }

    suspend fun syncRoomTranscript(roomId: String) {
        val logMsgs = runCatching { rpcClient.fetchTeamRoomLog(roomId) }.getOrDefault(emptyList())
        if (logMsgs.isNotEmpty()) {
            val scopeKey = "room:$roomId"
            _messagesByScope.update { current ->
                current + (scopeKey to logMsgs.map { it.copy(scopeId = scopeKey) })
            }
        }
        val (_, activities) = runCatching { rpcClient.fetchTeamRoomState(roomId) }.getOrDefault(null to emptyList())
        activities.forEach { (memberId, status) ->
            val st = if (status == "running") BotLiveState.THINKING else BotLiveState.IDLE
            updateBotState(memberId, st, "Sala $roomId: $status")
        }
    }

    suspend fun resolveApproval(approval: PendingToolApproval, allow: Boolean) {
        rpcClient.approveRoomAction(approval, allow)
        _approvals.update { list -> list.filterNot { it.requestId == approval.requestId } }
        updateBotState(
            approval.botProfile,
            if (allow) BotLiveState.RUNNING_TOOL else BotLiveState.IDLE,
            if (allow) "Permiso concedido (${approval.toolName})" else "Acción denegada (${approval.toolName})"
        )
    }

    // ── Configs, Providers & Manual Model Registry ────────────────────────────

    suspend fun refreshModels(profile: String = "default") {
        val manual = prefsStore.manualModelsFlow.first()
        val (currentModel, serverOptions) = runCatching { restClient.fetchModelOptions(profile) }
            .getOrDefault("" to emptyList())
        _activeGlobalModel.value = currentModel

        val manualOptions = manual.map {
            ModelOptionEntry(
                slug = it.slug,
                provider = it.provider,
                label = it.label,
                isManual = true,
                isActiveGlobal = it.slug == currentModel
            )
        }
        val merged = (manualOptions + serverOptions).distinctBy { "${it.provider}:${it.slug}" }
        _models.value = merged
    }

    suspend fun addManualModel(slug: String, provider: String, label: String) {
        prefsStore.addManualModel(
            ManualModelEntry(
                slug = slug.trim(),
                provider = provider.trim().ifBlank { "custom" },
                label = label.trim().ifBlank { slug.trim() }
            )
        )
        refreshModels()
        _statusBanner.value = "Modelo manual '${slug.trim()}' agregado al registro"
    }

    suspend fun removeManualModel(slug: String, provider: String) {
        prefsStore.removeManualModel(slug, provider)
        refreshModels()
        _statusBanner.value = "Modelo manual '$slug' eliminado"
    }

    suspend fun assignModelToGlobalOrBot(modelSlug: String, provider: String?, targetBotProfile: String?) {
        val ok = if (targetBotProfile.isNullOrBlank()) {
            restClient.setGlobalModel("default", modelSlug, provider)
        } else {
            restClient.setProfileModel(targetBotProfile, modelSlug, provider)
        }
        if (ok) {
            if (targetBotProfile.isNullOrBlank()) {
                _activeGlobalModel.value = modelSlug
            } else {
                _bots.update { list ->
                    list.map { if (it.profile == targetBotProfile) it.copy(activeModel = modelSlug) else it }
                }
            }
            _statusBanner.value = "Modelo '$modelSlug' asignado ${targetBotProfile?.let { "a @$it" } ?: "globalmente"}"
        }
    }

    suspend fun refreshProvidersAndConfig(profile: String = "default") {
        runCatching { restClient.fetchEnvVars(profile) }.onSuccess { _envVars.value = it }
        runCatching { restClient.fetchCustomEndpoints(profile) }.onSuccess { _customEndpoints.value = it }
        runCatching { restClient.fetchRawConfigYaml(profile) }.onSuccess { _rawConfigYaml.value = it }
    }

    suspend fun saveProviderSecret(profile: String, envKey: String, newValue: String) {
        if (restClient.updateEnvVars(profile, mapOf(envKey to newValue))) {
            refreshProvidersAndConfig(profile)
            _statusBanner.value = "Credencial $envKey actualizada de forma segura"
        }
    }

    suspend fun createCustomEndpoint(profile: String, name: String, baseUrl: String, apiKey: String, model: String) {
        if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            _statusBanner.value = "Ingresa nombre, Base URL y modelo default para registrar el provider"
            return
        }
        if (restClient.addCustomEndpoint(profile, name, baseUrl, apiKey, model)) {
            refreshProvidersAndConfig(profile)
            _statusBanner.value = "Provider '$name' conectado"
        } else {
            _statusBanner.value = "No se pudo registrar el provider '$name'"
        }
    }

    suspend fun saveConfigYaml(profile: String, yamlText: String) {
        if (restClient.saveRawConfigYaml(profile, yamlText)) {
            _rawConfigYaml.value = yamlText
            _statusBanner.value = "config.yaml guardado en Hermes ($profile)"
        }
    }

    // ── MCP Connector & Plugin Store ──────────────────────────────────────────

    suspend fun refreshMcpData(profile: String = "default") {
        runCatching { restClient.fetchMcpServers(profile) }.onSuccess { _mcpServers.value = it }
        runCatching { restClient.fetchMcpCatalog(profile) }.onSuccess { _mcpCatalog.value = it }
    }

    suspend fun connectNewMcpServer(profile: String, entry: McpServerEntry) {
        if (restClient.addOrUpdateMcpServer(profile, entry)) {
            if (rpcClient.connected.value) rpcClient.reloadMcpServers(confirm = true)
            refreshMcpData(profile)
            _statusBanner.value = "MCP '${entry.name}' registrado y recargado"
        }
    }

    suspend fun installCuratedMcp(profile: String, catalogName: String) {
        if (restClient.installMcpFromCatalog(profile, catalogName)) {
            if (rpcClient.connected.value) rpcClient.reloadMcpServers(confirm = true)
            refreshMcpData(profile)
            _statusBanner.value = "MCP '$catalogName' instalado desde el catálogo"
        }
    }

    suspend fun toggleMcp(profile: String, name: String, enabled: Boolean) {
        if (restClient.toggleMcpServer(profile, name, enabled)) {
            refreshMcpData(profile)
        }
    }

    suspend fun testMcp(profile: String, name: String) {
        val msg = restClient.testMcpServer(profile, name)
        _statusBanner.value = "MCP $name: $msg"
    }

    suspend fun refreshPluginStore(profile: String = "default") {
        val restItems = runCatching { restClient.fetchPluginStoreCatalog(profile) }.getOrNull()
        val rpcItems = if (rpcClient.connected.value) {
            runCatching { rpcClient.listManagedPlugins(profile) }.getOrNull()
        } else {
            null
        }
        if (restItems == null && rpcItems == null) return
        _pluginStore.value = mergePluginCatalogWithRpc(
            restItems = restItems.orEmpty(),
            rpcItems = rpcItems.orEmpty()
        )
    }

    suspend fun installStorePlugin(profile: String, identifier: String, fromCatalog: Boolean, force: Boolean = false) {
        val resp = when {
            rpcClient.connected.value -> rpcClient.installManagedPlugin(profile, identifier, fromCatalog, force)
            isDefaultProfile(profile) -> restClient.installPlugin(profile, identifier, fromCatalog, force)
            else -> {
                _statusBanner.value = "Conecta el WebSocket del Gateway para instalar plugins en @$profile"
                return
            }
        }
        if (resp == null) {
            _statusBanner.value = "No se pudo instalar '$identifier'"
            return
        }
        val scanArr = resp["scan_findings"] as? JsonArray
        if (!scanArr.isNullOrEmpty()) {
            _statusBanner.value = "Escáner de seguridad revisó '$identifier' (${scanArr.size} hallazgos)"
        } else {
            _statusBanner.value = "Plugin '$identifier' instalado"
        }
        refreshPluginStore(profile)
    }

    suspend fun toggleStorePlugin(profile: String, plugin: PluginCatalogItemUi, enable: Boolean) {
        val ok = when {
            rpcClient.connected.value -> rpcClient.toggleManagedPlugin(profile, plugin.pluginKey, enable)
            isDefaultProfile(profile) -> restClient.setPluginEnabled(profile, plugin.pluginKey, enable)
            else -> {
                _statusBanner.value = "Conecta el WebSocket del Gateway para alternar plugins en @$profile"
                return
            }
        }
        if (ok) {
            refreshPluginStore(profile)
            _statusBanner.value = "Plugin '${plugin.name}' ${if (enable) "activado" else "desactivado"} (@$profile)"
        }
    }

    suspend fun toggleStorePlugin(profile: String, name: String, enable: Boolean) {
        val match = _pluginStore.value.firstOrNull {
            it.name == name || it.pluginKey == name || it.installedName == name
        }
        if (match != null) {
            toggleStorePlugin(profile, match, enable)
        } else if (rpcClient.connected.value) {
            if (rpcClient.toggleManagedPlugin(profile, name, enable)) {
                refreshPluginStore(profile)
                _statusBanner.value = "Plugin '$name' ${if (enable) "activado" else "desactivado"}"
            }
        } else if (isDefaultProfile(profile) && restClient.setPluginEnabled(profile, name, enable)) {
            refreshPluginStore(profile)
            _statusBanner.value = "Plugin '$name' ${if (enable) "activado" else "desactivado"}"
        }
    }

    suspend fun uninstallStorePlugin(profile: String, plugin: PluginCatalogItemUi) {
        if (!plugin.canRemove) {
            _statusBanner.value = "'${plugin.name}' es un plugin integrado y no puede desinstalarse"
            return
        }
        val ok = when {
            rpcClient.connected.value -> rpcClient.removeManagedPlugin(profile, plugin.installedName)
            isDefaultProfile(profile) -> restClient.removePlugin(profile, plugin.installedName)
            else -> {
                _statusBanner.value = "Conecta el WebSocket del Gateway para desinstalar plugins en @$profile"
                return
            }
        }
        if (ok) {
            refreshPluginStore(profile)
            _statusBanner.value = "Plugin '${plugin.name}' desinstalado"
        }
    }

    suspend fun uninstallStorePlugin(profile: String, name: String) {
        val match = _pluginStore.value.firstOrNull {
            it.name == name || it.installedName == name || it.pluginKey == name
        }
        if (match != null) {
            uninstallStorePlugin(profile, match)
        } else if (rpcClient.connected.value) {
            if (rpcClient.removeManagedPlugin(profile, name)) {
                refreshPluginStore(profile)
                _statusBanner.value = "Plugin '$name' desinstalado"
            }
        } else if (isDefaultProfile(profile) && restClient.removePlugin(profile, name)) {
            refreshPluginStore(profile)
            _statusBanner.value = "Plugin '$name' desinstalado"
        }
    }

    suspend fun updateStorePlugin(
        profile: String,
        plugin: PluginCatalogItemUi,
        acceptCapabilities: Boolean = false,
        force: Boolean = false
    ): PluginUpdateOutcome {
        val outcome = when {
            rpcClient.connected.value -> rpcClient.updateManagedPlugin(
                profile = profile,
                installedName = plugin.installedName,
                acceptCapabilities = acceptCapabilities,
                force = force
            )
            isDefaultProfile(profile) -> restClient.updatePlugin(
                profile = profile,
                installedName = plugin.installedName,
                acceptCapabilities = acceptCapabilities,
                force = force
            )
            else -> PluginUpdateOutcome.Failed(
                "Conecta el WebSocket del Gateway para actualizar plugins del perfil @$profile"
            )
        }
        when (outcome) {
            is PluginUpdateOutcome.Applied -> {
                refreshPluginStore(profile)
                val suffix = when {
                    outcome.unchanged -> "ya estaba al día"
                    outcome.sha.isNotBlank() -> "actualizado a #${outcome.sha.take(7)}"
                    else -> "actualizado"
                }
                _statusBanner.value = "Plugin '${plugin.name}' $suffix"
            }
            is PluginUpdateOutcome.Failed -> {
                _statusBanner.value = outcome.message
            }
            is PluginUpdateOutcome.ConsentRequired,
            is PluginUpdateOutcome.ScanBlocked -> Unit
        }
        return outcome
    }

    suspend fun savePluginConfiguration(
        profile: String,
        plugin: PluginCatalogItemUi,
        drafts: Map<String, String>
    ): Result<List<String>> {
        val plan = PluginSettingsCodec.buildPayloads(plugin.settingsSchema, drafts)
            .getOrElse { return Result.failure(it) }

        val savedKeys = mutableListOf<String>()

        if (plan.secretEnvUpdates.isNotEmpty()) {
            val envRes = restClient.updateEnvVarsDetailed(profile, plan.secretEnvUpdates)
            savedKeys += envRes.updatedKeys
            if (envRes.failedKeys.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException(
                        "No se pudieron guardar las variables secretas: ${envRes.failedKeys.joinToString(", ")}"
                    )
                )
            }
        }

        if (plan.configValues.isNotEmpty()) {
            if (!rpcClient.connected.value) {
                return Result.failure(
                    IllegalStateException(
                        "WebSocket del Gateway desconectado. Reconecta en Nodo para guardar ajustes de '${plugin.name}'."
                    )
                )
            }
            val written = rpcClient.savePluginSettings(
                profile = profile,
                pluginKey = plugin.pluginKey,
                values = plan.configValues
            ).getOrElse { return Result.failure(it) }
            savedKeys += written
        }

        refreshPluginStore(profile)
        if (plan.secretEnvUpdates.isNotEmpty()) {
            refreshProvidersAndConfig(profile)
        }
        _statusBanner.value = "Configuración de '${plugin.name}' guardada (${savedKeys.size} ajustes)"
        return Result.success(savedKeys)
    }

    private fun isDefaultProfile(profile: String): Boolean {
        val clean = profile.trim()
        return clean.isEmpty() || clean.equals("default", ignoreCase = true)
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    // ── Real-Time Gateway Event Processing ────────────────────────────────────

    private fun onGatewayEvent(frame: GatewayEventFrame) {
        val profile = frame.memberId
            ?: frame.profile
            ?: frame.sessionId?.let { sessionToProfile[it] }
            ?: "default"
        val scopeId = frame.roomId?.let { "room:$it" } ?: "bot:$profile"

        when (frame.type) {
            "tool.start", "tool.started" -> {
                val tool = frame.payload.str("name").ifBlank { frame.payload.str("tool_name").ifBlank { "tool" } }
                updateBotState(profile, BotLiveState.RUNNING_TOOL, "Ejecutando $tool", toolName = tool)
            }
            "tool.complete", "tool.completed" -> {
                val tool = frame.payload.str("name").ifBlank { frame.payload.str("tool_name").ifBlank { "tool" } }
                updateBotState(profile, BotLiveState.THINKING, "Procesó resultado de $tool", toolName = tool)
            }
            "reasoning.delta" -> {
                val delta = frame.payload.str("delta").ifBlank { frame.payload.str("text") }
                updateBotState(profile, BotLiveState.THINKING, "Razonando…")
                if (delta.isNotBlank()) upsertStreamingBotMessage(scopeId, profile, reasoningDelta = delta)
            }
            "message.delta" -> {
                val delta = frame.payload.str("delta").ifBlank { frame.payload.str("text") }
                updateBotState(profile, BotLiveState.SPEAKING, "Respondiendo…")
                if (delta.isNotBlank()) upsertStreamingBotMessage(scopeId, profile, contentDelta = delta)
            }
            "message.complete", "turn.complete", "turn.settled" -> {
                val full = frame.payload.str("content").ifBlank { frame.payload.str("text") }
                finalizeStreamingBotMessage(scopeId, profile, full)
                updateBotState(profile, BotLiveState.IDLE, "Listo")
            }
            "error", "turn.error" -> {
                val msg = frame.payload.str("message").ifBlank { "Error en turno" }
                updateBotState(profile, BotLiveState.ERROR, msg)
            }
        }
    }

    private fun onServerRequest(req: ServerRpcRequest) {
        if (req.method.contains("approval", ignoreCase = true)) {
            val sid = req.params.str("session_id").takeIf { it.isNotBlank() }
            val prof = req.params.str("profile")
                .ifBlank { sid?.let { sessionToProfile[it] }.orEmpty() }
                .ifBlank { "default" }
            val tool = req.params.str("tool_name").ifBlank { req.params.str("name").ifBlank { "shell/tool" } }
            val cmd = req.params.str("command").ifBlank { req.params.str("description").ifBlank { req.params.toString().take(160) } }
            val approval = PendingToolApproval(
                requestId = req.id,
                botProfile = prof,
                sessionId = sid,
                roomId = req.params.str("room_id").takeIf { it.isNotBlank() },
                memberId = req.params.str("member_id").takeIf { it.isNotBlank() },
                taskId = req.params.str("task_id").takeIf { it.isNotBlank() },
                toolName = tool,
                commandPreview = cmd
            )
            _approvals.update { listOf(approval) + it.filterNot { a -> a.requestId == req.id } }
            updateBotState(prof, BotLiveState.WAITING_APPROVAL, "Solicita permiso: $tool", toolName = tool)
        }
    }

    private fun updateBotState(profile: String, state: BotLiveState, activity: String, toolName: String? = null) {
        _bots.update { list ->
            list.map { card ->
                if (card.profile == profile || card.handle == profile) {
                    card.copy(
                        liveState = state,
                        currentActivity = activity,
                        activeToolName = toolName ?: card.activeToolName,
                        updatedAtMs = System.currentTimeMillis()
                    )
                } else {
                    card
                }
            }
        }
    }

    private fun appendMessage(scopeId: String, msg: ChatTurnMessage) {
        _messagesByScope.update { map ->
            val existing = map[scopeId].orEmpty()
            map + (scopeId to (existing + msg))
        }
    }

    private fun upsertStreamingBotMessage(
        scopeId: String,
        profile: String,
        contentDelta: String = "",
        reasoningDelta: String = ""
    ) {
        _messagesByScope.update { map ->
            val list = map[scopeId].orEmpty().toMutableList()
            val lastIdx = list.indexOfLast { it.authorHandle == profile && it.isStreaming }
            if (lastIdx >= 0) {
                val cur = list[lastIdx]
                list[lastIdx] = cur.copy(
                    content = cur.content + contentDelta,
                    reasoning = cur.reasoning + reasoningDelta
                )
            } else {
                list += ChatTurnMessage(
                    id = "stream-$profile-${System.currentTimeMillis()}",
                    scopeId = scopeId,
                    authorHandle = profile,
                    authorKind = AuthorKind.BOT,
                    content = contentDelta,
                    reasoning = reasoningDelta,
                    isStreaming = true
                )
            }
            map + (scopeId to list)
        }
    }

    private fun finalizeStreamingBotMessage(scopeId: String, profile: String, finalContent: String) {
        _messagesByScope.update { map ->
            val list = map[scopeId].orEmpty().toMutableList()
            val lastIdx = list.indexOfLast { it.authorHandle == profile && it.isStreaming }
            if (lastIdx >= 0) {
                val cur = list[lastIdx]
                list[lastIdx] = cur.copy(
                    content = if (finalContent.isNotBlank()) finalContent else cur.content,
                    isStreaming = false
                )
            } else if (finalContent.isNotBlank()) {
                list += ChatTurnMessage(
                    id = "bot-$profile-${System.currentTimeMillis()}",
                    scopeId = scopeId,
                    authorHandle = profile,
                    authorKind = AuthorKind.BOT,
                    content = finalContent,
                    isStreaming = false
                )
            }
            map + (scopeId to list)
        }
    }

    companion object {
        internal fun mergePluginCatalogWithRpc(
            restItems: List<PluginCatalogItemUi>,
            rpcItems: List<ManagedPluginRpcInfo>
        ): List<PluginCatalogItemUi> {
            if (rpcItems.isEmpty()) return restItems

            val byCatalog = rpcItems.associateBy { it.catalogName }
            val byName = rpcItems.associateBy { it.name }
            val byKey = rpcItems.associateBy { it.key }
            val matchedKeys = mutableSetOf<String>()

            val merged = restItems.map { item ->
                val rpc = byCatalog[item.catalogName]
                    ?: byName[item.installedName]
                    ?: byName[item.name]
                    ?: byKey[item.pluginKey]
                if (rpc != null) {
                    matchedKeys += rpc.key
                    val isEnabled = rpc.status == "enabled" || rpc.status == "active"
                    item.copy(
                        installed = true,
                        enabled = isEnabled,
                        installedName = rpc.name,
                        pluginKey = rpc.key,
                        catalogName = rpc.catalogName.ifBlank { item.catalogName },
                        shaShort = rpc.installedSha.take(7).ifBlank { item.shaShort },
                        updateAvailable = rpc.updateAvailable || item.updateAvailable,
                        canRemove = rpc.canRemove && item.canRemove,
                        canUpdate = rpc.canRemove && (rpc.canUpdate || item.canUpdate),
                        settingsSchema = rpc.settingsSchema.ifEmpty { item.settingsSchema }
                    )
                } else {
                    item
                }
            }.toMutableList()

            for (rpc in rpcItems) {
                if (rpc.key in matchedKeys) continue
                val isEnabled = rpc.status == "enabled" || rpc.status == "active"
                val isBundled = !rpc.canRemove
                merged += PluginCatalogItemUi(
                    name = rpc.name,
                    description = rpc.description,
                    repo = rpc.source,
                    shaShort = rpc.installedSha.take(7),
                    tier = rpc.catalogTier.ifBlank { if (isBundled) "official" else "local" },
                    installed = true,
                    enabled = isEnabled,
                    updateAvailable = rpc.updateAvailable,
                    catalogName = rpc.catalogName.ifBlank { rpc.name },
                    installedName = rpc.name,
                    pluginKey = rpc.key,
                    canRemove = rpc.canRemove,
                    canUpdate = rpc.canUpdate,
                    settingsSchema = rpc.settingsSchema
                )
            }

            return merged.sortedWith(
                compareByDescending<PluginCatalogItemUi> { it.installed }.thenBy { it.name }
            )
        }
    }
}
