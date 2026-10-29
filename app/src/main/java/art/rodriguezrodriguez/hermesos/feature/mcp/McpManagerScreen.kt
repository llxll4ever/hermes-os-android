package art.rodriguezrodriguez.hermesos.feature.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.McpServerEntry
import art.rodriguezrodriguez.hermesos.core.model.McpTransport
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Phase 5a Hub:
 * - Connect new custom MCP servers (`STDIO`, `HTTP`, `SSE`) with command/URL, args, and env vars.
 * - Manage installed MCP servers (toggle enabled, test live tools discovery, delete, hot `reload.mcp`).
 * - Search and 1-tap install from Hermes's curated MCP catalog (`/api/mcp/catalog`).
 */
@Composable
fun McpManagerScreen(vm: HermesOsViewModel) {
    val scope = rememberCoroutineScope()
    val bots by vm.repo.bots.collectAsState()
    val selectedProfile by vm.selectedManagementProfile.collectAsState()
    val servers by vm.repo.mcpServers.collectAsState()
    val catalog by vm.repo.mcpCatalog.collectAsState()

    var mcpName by remember { mutableStateOf("") }
    var mcpTransport by remember { mutableStateOf(McpTransport.HTTP) }
    var mcpCommandOrUrl by remember { mutableStateOf("") }
    var mcpArgsRaw by remember { mutableStateOf("") }
    var mcpEnvRaw by remember { mutableStateOf("") }
    var catalogSearch by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Hot Reload MCP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Conectores MCP",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = HermesTextPrimary
                )
                Text(
                    text = "${servers.count { it.enabled }} activos · ${catalog.size} en catálogo curado",
                    fontSize = 12.sp,
                    color = HermesTeal
                )
            }
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        if (vm.repo.rpcClient.connected.value) {
                            vm.repo.rpcClient.reloadMcpServers(confirm = true)
                        }
                        vm.repo.refreshMcpData(selectedProfile)
                    }
                }
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reload MCP", fontSize = 12.sp)
            }
        }

        // Profile Selector
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            bots.forEach { bot ->
                FilterChip(
                    selected = selectedProfile == bot.profile,
                    onClick = { vm.setManagementProfile(bot.profile) },
                    label = { Text("@${bot.handle}", fontSize = 12.sp) }
                )
            }
        }

        // 1. Connect New Custom MCP Card
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
                    text = "Conectar Nuevo Servidor MCP",
                    fontWeight = FontWeight.Bold,
                    color = HermesTeal
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpTransport.entries.forEach { t ->
                        FilterChip(
                            selected = mcpTransport == t,
                            onClick = { mcpTransport = t },
                            label = { Text(t.name) }
                        )
                    }
                }

                // Quick MCP Templates
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {
                            mcpName = "context7"
                            mcpTransport = McpTransport.HTTP
                            mcpCommandOrUrl = "https://mcp.context7.com/mcp"
                            mcpArgsRaw = ""
                        },
                        label = { Text("Plantilla: Context7 HTTP", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = {
                            mcpName = "filesystem"
                            mcpTransport = McpTransport.STDIO
                            mcpCommandOrUrl = "npx"
                            mcpArgsRaw = "-y @modelcontextprotocol/server-filesystem /home/opc"
                        },
                        label = { Text("Plantilla: Filesystem STDIO", fontSize = 11.sp) }
                    )
                    AssistChip(
                        onClick = {
                            mcpName = "home-assistant"
                            mcpTransport = McpTransport.SSE
                            mcpCommandOrUrl = "http://127.0.0.1:8123/mcp_server/sse"
                            mcpArgsRaw = ""
                        },
                        label = { Text("Plantilla: Home Assistant SSE", fontSize = 11.sp) }
                    )
                }

                OutlinedTextField(
                    value = mcpName,
                    onValueChange = { mcpName = it },
                    label = { Text("Identificador del MCP (ej. github, context7, ha-mcp)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mcpCommandOrUrl,
                    onValueChange = { mcpCommandOrUrl = it },
                    label = {
                        Text(
                            if (mcpTransport == McpTransport.STDIO) "Comando ejecutable (ej. npx, uvx, python3)"
                            else "URL del endpoint MCP (${mcpTransport.name})"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mcpTransport == McpTransport.STDIO) {
                    OutlinedTextField(
                        value = mcpArgsRaw,
                        onValueChange = { mcpArgsRaw = it },
                        label = { Text("Argumentos separados por espacio") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = mcpEnvRaw,
                    onValueChange = { mcpEnvRaw = it },
                    label = { Text("Variables ENV opcionales (KEY=VALUE por línea)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Button(
                    onClick = {
                        if (mcpName.isNotBlank() && mcpCommandOrUrl.isNotBlank()) {
                            val args = mcpArgsRaw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            val envMap = mcpEnvRaw.lines()
                                .mapNotNull { line ->
                                    val idx = line.indexOf('=')
                                    if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                                }
                                .toMap()
                            val entry = McpServerEntry(
                                name = mcpName.trim(),
                                transport = mcpTransport,
                                commandOrUrl = mcpCommandOrUrl.trim(),
                                args = args,
                                env = envMap,
                                enabled = true
                            )
                            scope.launch {
                                vm.repo.connectNewMcpServer(selectedProfile, entry)
                                mcpName = ""
                                mcpCommandOrUrl = ""
                                mcpArgsRaw = ""
                                mcpEnvRaw = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Cable, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Conectar MCP y Recargar Gateway")
                }
            }
        }

        // 2. Installed MCP Servers
        Text(
            text = "Servidores MCP Configurados (${servers.size})",
            fontWeight = FontWeight.Bold,
            color = HermesViolet
        )
        if (servers.isEmpty()) {
            Text(
                text = "Aún no hay servidores MCP listados para @$selectedProfile (o inicia sesión en el Nodo).",
                fontSize = 12.sp,
                color = HermesTextSecondary
            )
        }
        servers.forEach { srv ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = HermesSurfaceCard,
                border = BorderStroke(1.dp, if (srv.connected) HermesEmerald else HermesBorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(srv.name, fontWeight = FontWeight.Bold, color = HermesTextPrimary)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = HermesTeal.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = srv.transport.name,
                                        fontSize = 10.sp,
                                        color = HermesTeal,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = srv.commandOrUrl + if (srv.args.isNotEmpty()) " ${srv.args.joinToString(" ")}" else "",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = HermesTextSecondary
                            )
                            if (srv.tools.isNotEmpty()) {
                                Text(
                                    text = "${srv.tools.size} tools: ${srv.tools.take(5).joinToString(", ")}",
                                    fontSize = 11.sp,
                                    color = HermesEmerald
                                )
                            }
                        }
                        Switch(
                            checked = srv.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    vm.repo.toggleMcp(selectedProfile, srv.name, enabled)
                                }
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch { vm.repo.testMcp(selectedProfile, srv.name) }
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Probar Conexión", fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    if (vm.repo.restClient.deleteMcpServer(selectedProfile, srv.name)) {
                                        vm.repo.refreshMcpData(selectedProfile)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = HermesRose, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Quitar", fontSize = 12.sp, color = HermesRose)
                        }
                    }
                }
            }
        }

        // 3. Curated MCP Catalog Search & 1-Tap Install
        Text(
            text = "Catálogo Curado de MCPs (${catalog.size})",
            fontWeight = FontWeight.Bold,
            color = HermesTeal
        )
        OutlinedTextField(
            value = catalogSearch,
            onValueChange = { catalogSearch = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Buscar en catálogo MCP (GitHub, Notion, Cloudflare, Postgres…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        val filteredCatalog = remember(catalog, catalogSearch) {
            val q = catalogSearch.trim().lowercase()
            if (q.isEmpty()) catalog else catalog.filter {
                it.name.lowercase().contains(q) ||
                    it.title.lowercase().contains(q) ||
                    it.description.lowercase().contains(q)
            }
        }

        filteredCatalog.forEach { item ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = HermesSurfaceCard,
                border = BorderStroke(1.dp, HermesBorderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(item.title, fontWeight = FontWeight.Bold, color = HermesTextPrimary)
                            Text("(${item.transport.name})", fontSize = 11.sp, color = HermesTeal)
                            if (item.requiresOauth) {
                                Text("OAuth", fontSize = 10.sp, color = HermesAmber)
                            }
                        }
                        if (item.description.isNotBlank()) {
                            Text(item.description, fontSize = 12.sp, color = HermesTextSecondary)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (item.installed) {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text("Instalado", fontSize = 12.sp)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    vm.repo.installCuratedMcp(selectedProfile, item.name)
                                }
                            }
                        ) {
                            Text("Instalar", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
