package art.rodriguezrodriguez.hermesos.feature.teams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.BotProfileCard
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Full Bot Squads (`Hosted Rooms` + `Bot Relay`) & Specialist Profile / `SOUL.md` Studio.
 */
@Composable
fun TeamsAndProfilesScreen(vm: HermesOsViewModel) {
    val scope = rememberCoroutineScope()
    val bots by vm.repo.bots.collectAsState()
    val rooms by vm.repo.teamRooms.collectAsState()
    val models by vm.repo.models.collectAsState()

    var roomName by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf("orchestrator", "programador", "disenador")) }

    var newBotName by remember { mutableStateOf("") }
    var newBotRole by remember { mutableStateOf("") }

    var editingSoulBot by remember { mutableStateOf<BotProfileCard?>(null) }
    var soulEditorText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Equipos de Trabajo & Bots Especialistas",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = HermesTextPrimary
        )

        // 1. Create Team Squad (`groups.create`)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Armar Nuevo Equipo (Hosted Bot Room)", fontWeight = FontWeight.Bold, color = HermesTeal)

                // 1-Tap Squad Templates
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            roomName = "Squad Full-Stack & UI"
                            selectedMembers = setOf("orchestrator", "programador", "disenador")
                        },
                        label = { Text("Plantilla: Full-Stack", fontSize = 12.sp) }
                    )
                    AssistChip(
                        onClick = {
                            roomName = "Squad Investigación & Copy"
                            selectedMembers = setOf("orchestrator", "sabio", "redactor")
                        },
                        label = { Text("Plantilla: Research + Copy", fontSize = 12.sp) }
                    )
                    AssistChip(
                        onClick = {
                            roomName = "Comando Casa Inteligente"
                            selectedMembers = setOf("domo", "rapidito", "programador")
                        },
                        label = { Text("Plantilla: Domótica HA", fontSize = 12.sp) }
                    )
                }

                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Nombre del Equipo / Sala") },
                    placeholder = { Text("Ej. Escuadrón App Android") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Selecciona los bots del equipo:", fontSize = 12.sp, color = HermesTextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bots.forEach { bot ->
                        val picked = bot.profile in selectedMembers
                        FilterChip(
                            selected = picked,
                            onClick = {
                                selectedMembers = if (picked) selectedMembers - bot.profile else selectedMembers + bot.profile
                            },
                            label = { Text("@${bot.handle}") }
                        )
                    }
                }

                Button(
                    onClick = {
                        if (roomName.isNotBlank() && selectedMembers.isNotEmpty()) {
                            scope.launch {
                                val created = vm.repo.createTeamRoom(roomName, selectedMembers.toList())
                                if (created != null) {
                                    roomName = ""
                                    vm.focusChatOnScope("room:${created.roomId}", jumpToDesktop = true)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.GroupAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Crear Equipo y Abrir en Desktop")
                }
            }
        }

        // 2. Active Hosted Team Rooms
        if (rooms.isNotEmpty()) {
            Text("Equipos Activos (${rooms.size})", fontWeight = FontWeight.Bold, color = HermesViolet)
            rooms.forEach { room ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = HermesSurfaceCard,
                    border = BorderStroke(1.dp, HermesBorderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(room.name, fontWeight = FontWeight.Bold, color = HermesTextPrimary)
                            Text(
                                text = "Miembros: " + room.members.joinToString(", ") { "@${it.handle}" },
                                fontSize = 12.sp,
                                color = HermesTextSecondary
                            )
                        }
                        FilledTonalButton(onClick = { vm.focusChatOnScope("room:${room.roomId}", jumpToDesktop = true) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Abrir Sala")
                        }
                    }
                }
            }
        }

        // 3. Roster of Specialist Bots + Per-Bot Model & SOUL.md Editor
        Text("Roster de Bots (${bots.size})", fontWeight = FontWeight.Bold, color = HermesTeal)
        bots.forEach { bot ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = HermesSurfaceCard,
                border = BorderStroke(1.dp, HermesBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("@${bot.handle} (${bot.profile})", fontWeight = FontWeight.Bold, color = HermesTextPrimary)
                            Text(bot.roleSummary, fontSize = 12.sp, color = HermesTextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    editingSoulBot = bot
                                    scope.launch {
                                        soulEditorText = vm.repo.restClient.fetchProfileSoul(bot.profile)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SOUL", fontSize = 12.sp)
                            }
                            FilledTonalButton(onClick = { vm.focusChatOnScope("bot:${bot.profile}", jumpToDesktop = true) }) {
                                Text("Chat", fontSize = 12.sp)
                            }
                        }
                    }

                    // Quick Model Assignment per Bot
                    if (models.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Modelo:", fontSize = 11.sp, color = HermesTextSecondary)
                            models.take(6).forEach { m ->
                                AssistChip(
                                    onClick = {
                                        scope.launch {
                                            vm.repo.assignModelToGlobalOrBot(m.slug, m.provider, bot.profile)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = m.label.take(20),
                                            fontSize = 11.sp,
                                            color = if (bot.activeModel == m.slug) HermesTeal else HermesTextPrimary
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Create New Bot Profile
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Crear Nuevo Perfil de Bot", fontWeight = FontWeight.Bold, color = HermesTextPrimary)
                OutlinedTextField(
                    value = newBotName,
                    onValueChange = { newBotName = it },
                    label = { Text("Handle / Perfil (ej. auditor-sec)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newBotRole,
                    onValueChange = { newBotRole = it },
                    label = { Text("Especialidad / Descripción") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        if (newBotName.isNotBlank()) {
                            scope.launch {
                                if (vm.repo.restClient.createBotProfile(newBotName, newBotRole, null)) {
                                    newBotName = ""
                                    newBotRole = ""
                                    vm.repo.refreshBotWall()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Crear Bot en Hermes")
                }
            }
        }
    }

    // SOUL.md Editor Modal
    editingSoulBot?.let { targetBot ->
        AlertDialog(
            onDismissRequest = { editingSoulBot = null },
            title = { Text("SOUL.md de @${targetBot.handle}") },
            text = {
                OutlinedTextField(
                    value = soulEditorText,
                    onValueChange = { soulEditorText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            vm.repo.restClient.updateProfileSoul(targetBot.profile, soulEditorText)
                            editingSoulBot = null
                        }
                    }
                ) {
                    Text("Guardar SOUL")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSoulBot = null }) { Text("Cancelar") }
            }
        )
    }
}
