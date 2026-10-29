package art.rodriguezrodriguez.hermesos.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.*
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*

/**
 * Full-screen Chat Screen with internal scroll, IME padding, and proper message rendering.
 */
@Composable
fun ChatScreen(
    vm: HermesOsViewModel,
    scopeId: String,
    onBack: () -> Unit
) {
    val messages by vm.repo.messagesByScope.collectAsState()
    val scopeMessages = messages[scopeId].orEmpty()
    val bots by vm.repo.bots.collectAsState()
    val rooms by vm.repo.teamRooms.collectAsState()
    
    val lazyListState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    var draft by remember { mutableStateOf("") }
    
    // Determine scope info
    val (scopeType, scopeKey) = scopeId.split(":")
    val isRoom = scopeType == "room"
    val displayName = if (isRoom) {
        rooms.firstOrNull { it.roomId == scopeKey }?.name ?: scopeKey
    } else {
        bots.firstOrNull { it.profile == scopeKey }?.handle ?: scopeKey
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(scopeMessages.size) {
        if (scopeMessages.isNotEmpty()) {
            lazyListState.animateScrollToItem(scopeMessages.lastIndex)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermesBgDeep)
    ) {
        // Top App Bar
        Surface(
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = HermesTextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Text(displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = HermesTextPrimary)
                Spacer(Modifier.weight(1f))
            }
        }
        
        // Messages List
        Box(modifier = Modifier.weight(1f)) {
            if (scopeMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRoom) {
                            "Sala de equipo lista. Usa @orchestrator, @programador o @disenador para coordinar tareas."
                        } else {
                            "Canal directo con $displayName listo. Envía una misión o usa el micrófono."
                        },
                        fontSize = 14.sp,
                        color = HermesTextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(scopeMessages) { msg ->
                        ChatMessageBubble(message = msg, bots = bots)
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        
        // Input Bar with IME Padding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .imePadding()
        ) {
            ChatInputBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.sendChat(scopeId, draft)
                        draft = ""
                        keyboardController?.hide()
                    }
                },
                scopeId = scopeId,
                bots = bots
            )
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatTurnMessage,
    bots: List<BotProfileCard>
) {
    val isUser = message.authorKind == AuthorKind.USER
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.authorHandle.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (!isUser) {
                Text(
                    text = "@${message.authorHandle}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HermesViolet,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp)
                )
            }
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isUser) Color(0xFF006D5B) else HermesSurfaceElevated,
                border = if (!isUser) BorderStroke(1.dp, HermesBorderSubtle) else null,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (message.reasoning.isNotBlank()) {
                        Text(
                            text = "💭 ${message.reasoning}",
                            fontSize = 11.sp,
                            color = HermesTextSecondary.copy(alpha = 0.8f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    
                    Text(
                        text = message.content,
                        fontSize = 14.sp,
                        color = HermesTextPrimary,
                        lineHeight = 20.sp
                    )
                    
                    if (message.toolCalls.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            message.toolCalls.forEach { tool ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = HermesBgDeep,
                                    border = BorderStroke(1.dp, HermesBorderSubtle),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = tool.name,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = HermesTeal
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = tool.detail.take(80),
                                            fontSize = 11.sp,
                                            color = HermesTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = tool.status,
                                            fontSize = 10.sp,
                                            color = when (tool.status) {
                                                "completed" -> HermesEmerald
                                                "running" -> HermesTeal
                                                "pending" -> HermesAmber
                                                "error" -> HermesRose
                                                else -> HermesTextSecondary
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    if (message.isStreaming) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "streaming…",
                                fontSize = 10.sp,
                                color = HermesAmber
                            )
                        }
                    }
                }
            }
        }
        
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(HermesTeal),
                contentAlignment = Alignment.Center
            ) {
                Text("Tú", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color.Black)
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    scopeId: String,
    bots: List<BotProfileCard>
) {
    val isRoom = scopeId.startsWith("room:")
    
    Surface(
        color = HermesSurfaceCard,
        border = BorderStroke(1.dp, HermesBorderSubtle),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (isRoom && bots.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bots.take(8).forEach { bot ->
                        AssistChip(
                            onClick = { onDraftChange("$draft @${bot.handle} ".trimStart()) },
                            label = { Text("+@${bot.handle}", fontSize = 11.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Escribe un mensaje…", fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    maxLines = 5,
                    singleLine = false
                )
                
                IconButton(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Enviar",
                        tint = if (draft.isNotBlank()) HermesTeal else HermesTextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}