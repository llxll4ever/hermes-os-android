package art.rodriguezrodriguez.hermesos.feature.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.*
import art.rodriguezrodriguez.hermesos.ui.theme.*

/**
 * Window-chrome wrapper giving every widget on the Hermes OS Desktop Canvas:
 * - Drag/Order controls (↑ / ↓)
 * - Size cycle button (S / M / W / MAX)
 * - Close widget button (×)
 */
@Composable
fun DesktopWidgetWindow(
    placement: DesktopWidgetPlacement,
    accentColor: Color = HermesTeal,
    onCycleSpan: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = HermesSurfaceCard,
        border = BorderStroke(1.dp, HermesBorderSubtle),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Window Titlebar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HermesSurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = placement.customTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = HermesTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = onCycleSpan,
                    label = {
                        Text(
                            text = when (placement.span) {
                                WidgetSpan.SMALL -> "S"
                                WidgetSpan.STANDARD -> "M"
                                WidgetSpan.WIDE -> "2X"
                                WidgetSpan.MAXIMIZED -> "MAX"
                            },
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.height(26.dp)
                )
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = HermesTextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = HermesTextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar widget", tint = HermesTextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                content = content
            )
        }
    }
}

// ── WIDGET 1: LIVE BOT ACTIVITY WALL ("Muro con los bots y qué están haciendo") ─

@Composable
fun BotWallWidgetContent(
    bots: List<BotProfileCard>,
    onOpenBotChat: (BotProfileCard) -> Unit,
    onJumpToTeams: () -> Unit,
    onRefreshWall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val activeCount = bots.count { it.liveState != BotLiveState.IDLE }
        Text(
            text = "${bots.size} especialistas • $activeCount trabajando ahora",
            style = MaterialTheme.typography.bodySmall,
            color = if (activeCount > 0) HermesTeal else HermesTextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onJumpToTeams, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("+ Equipo", fontSize = 12.sp)
            }
            IconButton(onClick = onRefreshWall, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refrescar muro", modifier = Modifier.size(16.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bots.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { bot ->
                    BotStatusTile(
                        bot = bot,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenBotChat(bot) }
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BotStatusTile(
    bot: BotProfileCard,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val stateColor = when (bot.liveState) {
        BotLiveState.IDLE -> HermesTextSecondary
        BotLiveState.THINKING -> HermesViolet
        BotLiveState.RUNNING_TOOL -> HermesTeal
        BotLiveState.WAITING_APPROVAL -> HermesAmber
        BotLiveState.SPEAKING -> HermesEmerald
        BotLiveState.ERROR -> HermesRose
    }
    val stateLabel = when (bot.liveState) {
        BotLiveState.IDLE -> "IDLE"
        BotLiveState.THINKING -> "THINKING"
        BotLiveState.RUNNING_TOOL -> bot.activeToolName?.let { "TOOL: $it" } ?: "RUNNING TOOL"
        BotLiveState.WAITING_APPROVAL -> "NEEDS APPROVAL"
        BotLiveState.SPEAKING -> "SPEAKING"
        BotLiveState.ERROR -> "ERROR"
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = HermesBgDeep,
        border = BorderStroke(
            width = if (bot.liveState == BotLiveState.IDLE) 1.dp else 1.5.dp,
            color = if (bot.liveState == BotLiveState.IDLE) HermesBorderSubtle else stateColor
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "@${bot.handle}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = HermesTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stateLabel,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = stateColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (bot.liveState == BotLiveState.IDLE) bot.roleSummary else bot.currentActivity,
                fontSize = 11.sp,
                color = HermesTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            if (bot.activeModel.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = bot.activeModel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HermesTeal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── WIDGET 2: VOICE MICROPHONE ORB ("Micrófono + Destino Bot/Equipo") ─────────

@Composable
fun VoiceMicWidgetContent(
    bots: List<BotProfileCard>,
    rooms: List<BotTeamRoom>,
    selectedScope: String,
    isListening: Boolean,
    lastTranscript: String,
    onSelectTargetScope: (String) -> Unit,
    onTriggerVoiceCapture: () -> Unit,
    onQuickVoiceSend: (String) -> Unit
) {
    var manualVoiceSimulation by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Target selector strip (Bots + Team Rooms)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rooms.forEach { room ->
                val scope = "room:${room.roomId}"
                FilterChip(
                    selected = selectedScope == scope,
                    onClick = { onSelectTargetScope(scope) },
                    label = { Text("Equipo: ${room.name}", fontSize = 11.sp) }
                )
            }
            bots.take(6).forEach { bot ->
                val scope = "bot:${bot.profile}"
                FilterChip(
                    selected = selectedScope == scope,
                    onClick = { onSelectTargetScope(scope) },
                    label = { Text("@${bot.handle}", fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Glowing Push-To-Talk Orb
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isListening) {
                            listOf(HermesRose, HermesViolet)
                        } else {
                            listOf(HermesTeal, HermesViolet)
                        }
                    )
                )
                .clickable(onClick = onTriggerVoiceCapture),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                contentDescription = "Hablar con Hermes",
                tint = Color.Black,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isListening) "Escuchando voz en vivo… toca para enviar" else "Toca el orbe para hablar → $selectedScope",
            fontSize = 12.sp,
            color = if (isListening) HermesAmber else HermesTextSecondary
        )

        if (lastTranscript.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "“$lastTranscript”",
                fontSize = 12.sp,
                color = HermesTeal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = manualVoiceSimulation,
                onValueChange = { manualVoiceSimulation = it },
                placeholder = { Text("Comando rápido de voz/texto…", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    if (manualVoiceSimulation.isNotBlank()) {
                        onQuickVoiceSend(manualVoiceSimulation)
                        manualVoiceSimulation = ""
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = HermesTeal)
            }
        }
    }
}

// ── WIDGET 3: RICH MULTI-CHAT & TEAM WAR ROOM ("Ventana de Chat & Equipos") ───

@Composable
fun TeamWarRoomWidgetContent(
    activeScope: String,
    bots: List<BotProfileCard>,
    rooms: List<BotTeamRoom>,
    messages: List<ChatTurnMessage>,
    onSelectScope: (String) -> Unit,
    onSendMessage: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }

    // Scope Tabs (Active Team Rooms + Specialist Bots)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rooms.forEach { room ->
            val key = "room:${room.roomId}"
            FilterChip(
                selected = activeScope == key,
                onClick = { onSelectScope(key) },
                leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text(room.name, fontSize = 12.sp) }
            )
        }
        bots.forEach { bot ->
            val key = "bot:${bot.profile}"
            FilterChip(
                selected = activeScope == key,
                onClick = { onSelectScope(key) },
                label = { Text("@${bot.handle}", fontSize = 12.sp) }
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Transcript Surface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 170.dp, max = 330.dp),
        shape = RoundedCornerShape(12.dp),
        color = HermesBgDeep,
        border = BorderStroke(1.dp, HermesBorderSubtle)
    ) {
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (activeScope.startsWith("room:")) {
                        "Sala de equipo lista. Usa @orchestrator, @programador o @disenador para coordinar tareas."
                    } else {
                        "Canal directo con ${activeScope.removePrefix("bot:")} listo. Envía una misión o usa el micrófono."
                    },
                    fontSize = 12.sp,
                    color = HermesTextSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                messages.takeLast(8).forEach { msg ->
                    val isUser = msg.authorKind == AuthorKind.USER
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isUser) Color(0xFF143534) else HermesSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(9.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isUser) "Tú" else "@${msg.authorHandle}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isUser) HermesTeal else HermesViolet
                                )
                                if (msg.isStreaming) {
                                    Text("streaming…", fontSize = 10.sp, color = HermesAmber)
                                }
                            }
                            if (msg.reasoning.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "💭 ${msg.reasoning.takeLast(160)}",
                                    fontSize = 11.sp,
                                    color = HermesTextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = msg.content,
                                fontSize = 13.sp,
                                color = HermesTextPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    // Quick @mention bar for Team Rooms
    if (activeScope.startsWith("room:")) {
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            bots.take(6).forEach { bot ->
                SuggestionChip(
                    onClick = { draft = "$draft @${bot.handle} ".trimStart() },
                    label = { Text("+@${bot.handle}", fontSize = 11.sp) }
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Escribe misión para $activeScope…", fontSize = 13.sp) },
            modifier = Modifier.weight(1f),
            maxLines = 3
        )
        FilledIconButton(
            onClick = {
                if (draft.isNotBlank()) {
                    onSendMessage(draft)
                    draft = ""
                }
            }
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
        }
    }
}

// ── WIDGET 4: QUICK MODEL & MCP SWITCHER ──────────────────────────────────────

@Composable
fun QuickModelMcpWidgetContent(
    activeModel: String,
    models: List<ModelOptionEntry>,
    mcpServers: List<McpServerEntry>,
    onPickGlobalModel: (ModelOptionEntry) -> Unit,
    onOpenModelsHub: () -> Unit,
    onOpenMcpHub: () -> Unit
) {
    Text(
        text = "Modelo Global: ${activeModel.ifBlank { "Auto / Perfil" }}",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = HermesTeal
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        models.take(5).forEach { m ->
            FilterChip(
                selected = m.slug == activeModel,
                onClick = { onPickGlobalModel(m) },
                label = { Text(m.label.take(22), fontSize = 11.sp) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "MCPs activos: ${mcpServers.count { it.enabled }}/${mcpServers.size}",
            fontSize = 12.sp,
            color = HermesTextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onOpenModelsHub) { Text("Modelos", fontSize = 12.sp) }
            TextButton(onClick = onOpenMcpHub) { Text("MCPs", fontSize = 12.sp) }
        }
    }
}

// ── WIDGET 5: PENDING TOOL APPROVALS INBOX ────────────────────────────────────

@Composable
fun ApprovalsInboxWidgetContent(
    approvals: List<PendingToolApproval>,
    onResolve: (PendingToolApproval, Boolean) -> Unit
) {
    if (approvals.isEmpty()) {
        Text(
            text = "Sin aprobaciones pendientes. Tus bots operan con normalidad.",
            fontSize = 12.sp,
            color = HermesTextSecondary
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            approvals.forEach { req ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = HermesBgDeep,
                    border = BorderStroke(1.dp, HermesAmber)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "@${req.botProfile} solicita ejecutar ${req.toolName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = HermesAmber
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = req.commandPreview,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HermesTextPrimary,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onResolve(req, true) },
                                colors = ButtonDefaults.buttonColors(containerColor = HermesEmerald)
                            ) {
                                Text("Aprobar", fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { onResolve(req, false) }) {
                                Text("Denegar", fontSize = 12.sp, color = HermesRose)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── WIDGET 6: VPS TELEMETRY & NODE HEALTH ─────────────────────────────────────

@Composable
fun VpsTelemetryWidgetContent(
    connection: ConnectionSnapshot,
    onReconnect: () -> Unit,
    onOpenConnectionSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Ruta: ${connection.activeRung?.label ?: "Sin conexión"}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = HermesTextPrimary
        )
        Text(
            text = "Hermes v${connection.serverVersion.ifBlank { "?" }} • Latencia: ${connection.latencyMs} ms • WS: ${if (connection.wsConnected) "ONLINE" else "OFFLINE"}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (connection.wsConnected) HermesEmerald else HermesAmber
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onReconnect) { Text("Reconectar", fontSize = 12.sp) }
            TextButton(onClick = onOpenConnectionSettings) { Text("Configurar Escalera", fontSize = 12.sp) }
        }
    }
}
