package art.rodriguezrodriguez.hermesos.feature.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.*
import art.rodriguezrodriguez.hermesos.feature.widgets.*
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Modular "Desktop Mode for Phone & DeX" Workspace Canvas.
 *
 * - Supports instant Workspace Presets (Command Center, Team War Room, Voice & Domotics, DeX Multi-Window).
 * - Users can dynamically add, remove, reorder, and resize widgets (S / M / 2X / MAX).
 * - Automatically switches to true multi-column window tiling on wide screens / landscape / Samsung DeX.
 */
@Composable
fun DesktopCanvasScreen(
    vm: HermesOsViewModel,
    onRequestSystemVoiceInput: () -> Unit
) {
    val activePreset by vm.activePreset.collectAsState()
    val widgets by vm.desktopWidgets.collectAsState()
    val bots by vm.repo.bots.collectAsState()
    val rooms by vm.repo.teamRooms.collectAsState()
    val messagesByScope by vm.repo.messagesByScope.collectAsState()
    val focusedScope by vm.focusedChatScope.collectAsState()
    val voiceTarget by vm.voiceTargetScope.collectAsState()
    val isListening by vm.isListeningVoice.collectAsState()
    val lastTranscript by vm.lastVoiceTranscript.collectAsState()
    val activeGlobalModel by vm.repo.activeGlobalModel.collectAsState()
    val models by vm.repo.models.collectAsState()
    val mcpServers by vm.repo.mcpServers.collectAsState()
    val approvals by vm.repo.approvals.collectAsState()
    val connection by vm.repo.connection.collectAsState()

    var showWidgetCatalogDialog by remember { mutableStateOf(false) }

    // Snackbar host for undo widget removal
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideDesktop = maxWidth >= 700.dp // Landscape S24 Ultra, Foldable, or Samsung DeX

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Desktop Workspace Preset Bar + "+ Widget" Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkspacePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = activePreset == preset,
                            onClick = { vm.applyPreset(preset) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DashboardCustomize,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            label = { Text(preset.displayName, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { showWidgetCatalogDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Widget", fontSize = 12.sp)
                }
            }

            // If any widget is MAXIMIZED, focus it full-canvas with a restore banner
            val maximized = widgets.firstOrNull { it.span == WidgetSpan.MAXIMIZED }
            if (maximized != null) {
                RenderDesktopWidgetCard(
                    placement = maximized,
                    vm = vm,
                    bots = bots,
                    rooms = rooms,
                    messages = messagesByScope[focusedScope].orEmpty(),
                    focusedScope = focusedScope,
                    voiceTarget = voiceTarget,
                    isListening = isListening,
                    lastTranscript = lastTranscript,
                    activeGlobalModel = activeGlobalModel,
                    models = models,
                    mcpServers = mcpServers,
                    approvals = approvals,
                    connection = connection,
                    onRequestSystemVoiceInput = onRequestSystemVoiceInput,
                    onRemoveWithUndo = { placementId, title ->
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Widget \"$title\" eliminado",
                                actionLabel = "Deshacer",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.Dismissed) {
                                // User didn't click Undo - proceed with removal
                                vm.removeDesktopWidget(placementId)
                            }
                            // If result == SnackbarResult.ActionPerformed, user clicked Undo - do nothing
                        }
                    }
                )
            } else if (isWideDesktop) {
                // Multi-Window 2-Column Tiling Mode (Landscape / Samsung DeX)
                val sorted = widgets.sortedBy { it.order }
                val leftCol = sorted.filterIndexed { i, _ -> i % 2 == 0 }
                val rightCol = sorted.filterIndexed { i, _ -> i % 2 == 1 }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        leftCol.forEach { w ->
                            RenderDesktopWidgetCard(
                                placement = w,
                                vm = vm,
                                bots = bots,
                                rooms = rooms,
                                messages = messagesByScope[focusedScope].orEmpty(),
                                focusedScope = focusedScope,
                                voiceTarget = voiceTarget,
                                isListening = isListening,
                                lastTranscript = lastTranscript,
                                activeGlobalModel = activeGlobalModel,
                                models = models,
                                mcpServers = mcpServers,
                                approvals = approvals,
                                connection = connection,
                                onRequestSystemVoiceInput = onRequestSystemVoiceInput,
                                onRemoveWithUndo = { placementId, title ->
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Widget \"$title\" eliminado",
                                            actionLabel = "Deshacer",
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.Dismissed) {
                                            vm.removeDesktopWidget(placementId)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rightCol.forEach { w ->
                            RenderDesktopWidgetCard(
                                placement = w,
                                vm = vm,
                                bots = bots,
                                rooms = rooms,
                                messages = messagesByScope[focusedScope].orEmpty(),
                                focusedScope = focusedScope,
                                voiceTarget = voiceTarget,
                                isListening = isListening,
                                lastTranscript = lastTranscript,
                                activeGlobalModel = activeGlobalModel,
                                models = models,
                                mcpServers = mcpServers,
                                approvals = approvals,
                                connection = connection,
                                onRequestSystemVoiceInput = onRequestSystemVoiceInput,
                                onRemoveWithUndo = { placementId, title ->
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Widget \"$title\" eliminado",
                                            actionLabel = "Deshacer",
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.Dismissed) {
                                            vm.removeDesktopWidget(placementId)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                // Portrait Mobile Desktop Mode: SMALL widgets pair side-by-side; STANDARD/WIDE span full width
                val sorted = widgets.sortedBy { it.order }
                var idx = 0
                while (idx < sorted.size) {
                    val current = sorted[idx]
                    val next = sorted.getOrNull(idx + 1)
                    if (current.span == WidgetSpan.SMALL && next?.span == WidgetSpan.SMALL) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                RenderDesktopWidgetCard(
                                    placement = current,
                                    vm = vm,
                                    bots = bots,
                                    rooms = rooms,
                                    messages = messagesByScope[focusedScope].orEmpty(),
                                    focusedScope = focusedScope,
                                    voiceTarget = voiceTarget,
                                    isListening = isListening,
                                    lastTranscript = lastTranscript,
                                    activeGlobalModel = activeGlobalModel,
                                    models = models,
                                    mcpServers = mcpServers,
                                    approvals = approvals,
                                    connection = connection,
                                    onRequestSystemVoiceInput = onRequestSystemVoiceInput,
                                    onRemoveWithUndo = { placementId, title ->
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Widget \"$title\" eliminado",
                                                actionLabel = "Deshacer",
                                                duration = SnackbarDuration.Long
                                            )
                                            if (result == SnackbarResult.Dismissed) {
                                                vm.removeDesktopWidget(placementId)
                                            }
                                        }
                                    }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                RenderDesktopWidgetCard(
                                    placement = next,
                                    vm = vm,
                                    bots = bots,
                                    rooms = rooms,
                                    messages = messagesByScope[focusedScope].orEmpty(),
                                    focusedScope = focusedScope,
                                    voiceTarget = voiceTarget,
                                    isListening = isListening,
                                    lastTranscript = lastTranscript,
                                    activeGlobalModel = activeGlobalModel,
                                    models = models,
                                    mcpServers = mcpServers,
                                    approvals = approvals,
                                    connection = connection,
                                    onRequestSystemVoiceInput = onRequestSystemVoiceInput,
                                    onRemoveWithUndo = { placementId, title ->
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Widget \"$title\" eliminado",
                                                actionLabel = "Deshacer",
                                                duration = SnackbarDuration.Long
                                            )
                                            if (result == SnackbarResult.Dismissed) {
                                                vm.removeDesktopWidget(placementId)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        idx += 2
                    } else {
                        RenderDesktopWidgetCard(
                            placement = current,
                            vm = vm,
                            bots = bots,
                            rooms = rooms,
                            messages = messagesByScope[focusedScope].orEmpty(),
                            focusedScope = focusedScope,
                            voiceTarget = voiceTarget,
                            isListening = isListening,
                            lastTranscript = lastTranscript,
                            activeGlobalModel = activeGlobalModel,
                            models = models,
                            mcpServers = mcpServers,
                            approvals = approvals,
                            connection = connection,
                            onRequestSystemVoiceInput = onRequestSystemVoiceInput,
                            onRemoveWithUndo = { placementId, title ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Widget \"$title\" eliminado",
                                        actionLabel = "Deshacer",
                                        duration = SnackbarDuration.Long
                                    )
                                    if (result == SnackbarResult.Dismissed) {
                                        vm.removeDesktopWidget(placementId)
                                    }
                                }
                            }
                        )
                        idx += 1
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // SnackbarHost for undo widget removal
    SnackbarHost(hostState = snackbarHostState)

    if (showWidgetCatalogDialog) {
        AlertDialog(
            onDismissRequest = { showWidgetCatalogDialog = false },
            title = { Text("Agregar Widget al Escritorio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopWidgetType.entries.forEach { type ->
                        FilledTonalButton(
                            onClick = {
                                vm.addDesktopWidget(type)
                                showWidgetCatalogDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("+ ${type.defaultTitle}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWidgetCatalogDialog = false }) { Text("Cerrar") }
            }
        )
    }
}

@Composable
private fun RenderDesktopWidgetCard(
    placement: DesktopWidgetPlacement,
    vm: HermesOsViewModel,
    bots: List<BotProfileCard>,
    rooms: List<BotTeamRoom>,
    messages: List<ChatTurnMessage>,
    focusedScope: String,
    voiceTarget: String,
    isListening: Boolean,
    lastTranscript: String,
    activeGlobalModel: String,
    models: List<ModelOptionEntry>,
    mcpServers: List<McpServerEntry>,
    approvals: List<PendingToolApproval>,
    connection: ConnectionSnapshot,
    onRequestSystemVoiceInput: () -> Unit,
    onRemoveWithUndo: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val accent = when (placement.type) {
        DesktopWidgetType.BOT_WALL -> HermesTeal
        DesktopWidgetType.TEAM_CHAT -> HermesViolet
        DesktopWidgetType.VOICE_MIC -> HermesRose
        DesktopWidgetType.QUICK_MODEL_MCP -> HermesEmerald
        DesktopWidgetType.APPROVALS_INBOX -> HermesAmber
        DesktopWidgetType.VPS_TELEMETRY -> HermesTeal
    }

    DesktopWidgetWindow(
        placement = placement,
        accentColor = accent,
        onCycleSpan = { vm.cycleWidgetSpan(placement.id) },
        onMoveUp = { vm.moveWidgetOrder(placement.id, -1) },
        onMoveDown = { vm.moveWidgetOrder(placement.id, +1) },
        onRemove = { onRemoveWithUndo(placement.id, placement.customTitle) }
    ) {
        when (placement.type) {
            DesktopWidgetType.BOT_WALL -> BotWallWidgetContent(
                bots = bots,
                onOpenBotChat = { bot -> vm.focusChatOnScope("bot:${bot.profile}", jumpToDesktop = false) },
                onJumpToTeams = { vm.selectDestination(OsDockDestination.TEAMS) },
                onRefreshWall = { scope.launch { vm.repo.refreshBotWall() } }
            )
            DesktopWidgetType.VOICE_MIC -> VoiceMicWidgetContent(
                bots = bots,
                rooms = rooms,
                selectedScope = voiceTarget,
                isListening = isListening,
                lastTranscript = lastTranscript,
                onSelectTargetScope = { vm.setVoiceTarget(it) },
                onTriggerVoiceCapture = onRequestSystemVoiceInput,
                onQuickVoiceSend = { vm.onVoiceTranscriptCaptured(it) }
            )
            DesktopWidgetType.TEAM_CHAT -> TeamWarRoomWidgetContent(
                activeScope = focusedScope,
                bots = bots,
                rooms = rooms,
                messages = messages,
                onSelectScope = { vm.focusChatOnScope(it, jumpToDesktop = false) },
                onSendMessage = { vm.sendChat(focusedScope, it) }
            )
            DesktopWidgetType.QUICK_MODEL_MCP -> QuickModelMcpWidgetContent(
                activeModel = activeGlobalModel,
                models = models,
                mcpServers = mcpServers,
                onPickGlobalModel = { m ->
                    scope.launch { vm.repo.assignModelToGlobalOrBot(m.slug, m.provider, null) }
                },
                onOpenModelsHub = { vm.selectDestination(OsDockDestination.MODELS) },
                onOpenMcpHub = { vm.selectDestination(OsDockDestination.MCP) }
            )
            DesktopWidgetType.APPROVALS_INBOX -> ApprovalsInboxWidgetContent(
                approvals = approvals,
                onResolve = { req, allow -> vm.approveOrDeny(req, allow) }
            )
            DesktopWidgetType.VPS_TELEMETRY -> VpsTelemetryWidgetContent(
                connection = connection,
                onReconnect = {
                    scope.launch { vm.repo.connectUsingLadder() }
                },
                onOpenConnectionSettings = { vm.selectDestination(OsDockDestination.CONNECTION) }
            )
        }
    }
}
