package art.rodriguezrodriguez.hermesos.feature.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.ConnectionKind
import art.rodriguezrodriguez.hermesos.core.model.ConnectionRung
import art.rodriguezrodriguez.hermesos.core.model.LinkStatus
import art.rodriguezrodriguez.hermesos.core.model.defaultConnectionRungs
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Node Authentication & Observable Connection Ladder Screen.
 *
 * Precedence:
 * 1. Tailscale Direct (`http://100.103.166.20:9119`)
 * 2. Cloudflare Tunnel (`https://hermes.rodriguezrodriguez.art`)
 * 3. Any user-added Custom URLs (LAN, SSH port-forward, reverse proxy)
 */
@Composable
fun ConnectionNodeScreen(vm: HermesOsViewModel) {
    val scope = rememberCoroutineScope()
    val connection by vm.repo.connection.collectAsState()
    val rungs by vm.connectionRungs.collectAsState()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }

    var sessionTokenDraft by remember {
        mutableStateOf(vm.repo.restClient.loopbackSessionToken.orEmpty())
    }

    var customLabel by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }

    val statusColor = when (connection.status) {
        LinkStatus.CONNECTED -> HermesEmerald
        LinkStatus.AUTH_REQUIRED -> HermesAmber
        LinkStatus.PROBING -> HermesTeal
        LinkStatus.DEGRADED -> HermesAmber
        LinkStatus.DISCONNECTED -> HermesRose
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Nodo Hermes & Escalera de Conexión",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = HermesTextPrimary
        )

        // 1. Live Link Telemetry Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, statusColor)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Estado: ${connection.status.name}",
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                        Text(
                            text = connection.activeRung?.let { "${it.label} (${it.baseUrl})" }
                                ?: "Sin nodo activo",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HermesTextPrimary
                        )
                    }
                    FilledTonalButton(
                        onClick = { scope.launch { vm.repo.connectUsingLadder() } }
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Probar", fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Hermes: ${connection.serverVersion.ifBlank { "—" }}",
                        fontSize = 12.sp,
                        color = HermesTeal
                    )
                    Text(
                        text = "Latencia: ${if (connection.latencyMs > 0) "${connection.latencyMs} ms" else "—"}",
                        fontSize = 12.sp,
                        color = HermesTextSecondary
                    )
                    Text(
                        text = if (connection.wsConnected) "WS: Multiplex OK" else "WS: Offline",
                        fontSize = 12.sp,
                        color = if (connection.wsConnected) HermesEmerald else HermesAmber
                    )
                }

                if (connection.authenticatedUser.isNotBlank()) {
                    Text(
                        text = "Sesión autenticada como: ${connection.authenticatedUser}",
                        fontSize = 12.sp,
                        color = HermesEmerald
                    )
                }
                connection.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                    Text(text = err, fontSize = 11.sp, color = HermesRose)
                }
            }
        }

        // 2. Dashboard Password Login (`/auth/password-login`)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Autenticación Hermes Dashboard",
                    fontWeight = FontWeight.Bold,
                    color = HermesTeal
                )
                Text(
                    text = "Obtiene cookie cifrada y ticket efímero de 30s para el bus WebSocket host-scoped (los 8 bots).",
                    fontSize = 11.sp,
                    color = HermesTextSecondary
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (username.isNotBlank() && password.isNotBlank()) {
                                isLoggingIn = true
                                scope.launch {
                                    val ok = vm.repo.login(username, password)
                                    if (ok) password = ""
                                    isLoggingIn = false
                                }
                            }
                        },
                        enabled = !isLoggingIn,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isLoggingIn) "Autenticando…" else "Iniciar Sesión")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.repo.rpcClient.disconnect()
                            vm.repo.restClient.cookieStore.clearAll()
                            vm.repo.restClient.loopbackSessionToken = null
                            vm.repo.restClient.bearerAccessToken = null
                            sessionTokenDraft = ""
                            scope.launch { vm.repo.connectUsingLadder() }
                        }
                    ) {
                        Text("Limpiar Sesión")
                    }
                }
            }
        }

        // 3. Editable Connection Ladder (Tailscale -> Cloudflare -> Custom)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Escalera de Conexión (Failover Automático)",
                        fontWeight = FontWeight.Bold,
                        color = HermesViolet
                    )
                    TextButton(
                        onClick = {
                            scope.launch { vm.repo.updateConnectionRungs(defaultConnectionRungs()) }
                        }
                    ) {
                        Text("Restaurar Default", fontSize = 11.sp)
                    }
                }

                rungs.forEachIndexed { idx, rung ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HermesSurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (connection.activeRung?.id == rung.id) HermesEmerald else HermesBorderSubtle
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${idx + 1}. ${rung.label}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = HermesTextPrimary
                                )
                                Text(
                                    text = rung.baseUrl,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = HermesTeal
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (idx > 0) {
                                    IconButton(
                                        onClick = {
                                            val mut = rungs.toMutableList()
                                            val item = mut.removeAt(idx)
                                            mut.add(idx - 1, item)
                                            scope.launch { vm.repo.updateConnectionRungs(mut) }
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir prioridad")
                                    }
                                }
                                Switch(
                                    checked = rung.enabled,
                                    onCheckedChange = { en ->
                                        val updated = rungs.map { if (it.id == rung.id) it.copy(enabled = en) else it }
                                        scope.launch { vm.repo.updateConnectionRungs(updated) }
                                    }
                                )
                                if (rung.kind == ConnectionKind.CUSTOM) {
                                    IconButton(
                                        onClick = {
                                            val updated = rungs.filterNot { it.id == rung.id }
                                            scope.launch { vm.repo.updateConnectionRungs(updated) }
                                        }
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Borrar", tint = HermesRose)
                                    }
                                }
                            }
                        }
                    }
                }

                // Add Custom Rung
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customLabel,
                        onValueChange = { customLabel = it },
                        label = { Text("Etiqueta (ej. Túnel SSH)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("URL (http/https)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (customUrl.isNotBlank()) {
                            val next = rungs + ConnectionRung(
                                id = "custom-${UUID.randomUUID().toString().take(6)}",
                                label = customLabel.ifBlank { "Nodo Personalizado" },
                                baseUrl = customUrl.trim(),
                                kind = ConnectionKind.CUSTOM,
                                enabled = true
                            )
                            customLabel = ""
                            customUrl = ""
                            scope.launch { vm.repo.updateConnectionRungs(next) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar Peldaño a la Escalera")
                }
            }
        }

        // 4. Optional Loopback Session Token (`X-Hermes-Session-Token`)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Token Directo Loopback (Opcional)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = HermesTextPrimary
                )
                OutlinedTextField(
                    value = sessionTokenDraft,
                    onValueChange = { sessionTokenDraft = it },
                    label = { Text("X-Hermes-Session-Token (si usas túnel ADB/SSH a 127.0.0.1:9119)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FilledTonalButton(
                    onClick = {
                        val clean = sessionTokenDraft.trim().takeIf { it.isNotBlank() }
                        vm.repo.restClient.loopbackSessionToken = clean
                        vm.repo.restClient.cookieStore.saveBearerOrSessionToken("loopback_session_token", clean)
                        scope.launch { vm.repo.connectUsingLadder() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Aplicar Token y Reconectar")
                }
            }
        }
    }
}
