package art.rodriguezrodriguez.hermesos.feature.plugins

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
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Phase 5b Hub — Hermes Plugin Store with Search, Tier Filters, Git Install & Security Scanner badges.
 */
@Composable
fun PluginStoreScreen(vm: HermesOsViewModel) {
    val scope = rememberCoroutineScope()
    val bots by vm.repo.bots.collectAsState()
    val selectedProfile by vm.selectedManagementProfile.collectAsState()
    val plugins by vm.repo.pluginStore.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf("all") }
    var customGitRepo by remember { mutableStateOf("") }

    val tiers = listOf(
        "all" to "Todos",
        "installed" to "Instalados",
        "official" to "Official",
        "verified" to "Verified",
        "community" to "Community"
    )

    val filteredPlugins = remember(plugins, searchQuery, selectedTier) {
        val q = searchQuery.trim().lowercase()
        plugins.filter { p ->
            val matchesTier = when (selectedTier) {
                "all" -> true
                "installed" -> p.installed
                else -> p.tier.equals(selectedTier, ignoreCase = true)
            }
            val matchesQuery = q.isEmpty() ||
                p.name.lowercase().contains(q) ||
                p.description.lowercase().contains(q) ||
                p.maintainer.lowercase().contains(q) ||
                p.capabilitySummary.lowercase().contains(q) ||
                p.repo.lowercase().contains(q)
            matchesTier && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Store Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tienda de Plugins Hermes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = HermesTextPrimary
                )
                Text(
                    text = "${plugins.count { it.installed }} instalados · ${plugins.size} disponibles en catálogo",
                    fontSize = 12.sp,
                    color = HermesTeal
                )
            }
            IconButton(
                onClick = { scope.launch { vm.repo.refreshPluginStore(selectedProfile) } }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar Tienda", tint = HermesTeal)
            }
        }

        // Target Profile Bar
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

        // Store Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = HermesTeal) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                    }
                }
            },
            label = { Text("Buscar plugins por nombre, capacidad, autor o repo…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Category / Tier Filter Chips
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tiers.forEach { (key, label) ->
                FilterChip(
                    selected = selectedTier == key,
                    onClick = { selectedTier = key },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // Install Direct from GitHub / Git URL Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Instalar Plugin desde GitHub / Git URL",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = HermesViolet
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customGitRepo,
                        onValueChange = { customGitRepo = it },
                        placeholder = { Text("owner/repo o https://github.com/...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (customGitRepo.isNotBlank()) {
                                scope.launch {
                                    vm.repo.installStorePlugin(selectedProfile, customGitRepo.trim(), fromCatalog = false)
                                    customGitRepo = ""
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Instalar")
                    }
                }
            }
        }

        // Store Results
        if (filteredPlugins.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = HermesSurfaceCard,
                border = BorderStroke(1.dp, HermesBorderSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = HermesTextSecondary)
                    Text(
                        text = if (plugins.isEmpty()) "Conecta e inicia sesión en el Nodo Hermes para cargar el catálogo oficial de plugins."
                        else "No se encontraron plugins para '$searchQuery'.",
                        fontSize = 12.sp,
                        color = HermesTextSecondary
                    )
                }
            }
        }

        filteredPlugins.forEach { plugin ->
            val tierColor = when (plugin.tier.lowercase()) {
                "official" -> HermesTeal
                "verified" -> HermesViolet
                "local" -> HermesEmerald
                else -> HermesAmber
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HermesSurfaceCard,
                border = BorderStroke(1.dp, if (plugin.installed && plugin.enabled) HermesEmerald else HermesBorderSubtle)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = plugin.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = HermesTextPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = tierColor.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = plugin.tier.uppercase(),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tierColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (plugin.updateAvailable) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = HermesAmber.copy(alpha = 0.22f)
                                    ) {
                                        Text(
                                            text = "UPDATE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HermesAmber,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            if (plugin.maintainer.isNotBlank() || plugin.repo.isNotBlank()) {
                                Text(
                                    text = listOfNotNull(
                                        plugin.maintainer.takeIf { it.isNotBlank() }?.let { "por $it" },
                                        plugin.repo.takeIf { it.isNotBlank() },
                                        plugin.shaShort.takeIf { it.isNotBlank() }?.let { "#$it" }
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = HermesTextSecondary
                                )
                            }
                        }

                        if (plugin.installed) {
                            Switch(
                                checked = plugin.enabled,
                                onCheckedChange = { enable ->
                                    scope.launch {
                                        vm.repo.toggleStorePlugin(selectedProfile, plugin.name, enable)
                                    }
                                }
                            )
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        vm.repo.installStorePlugin(selectedProfile, plugin.name, fromCatalog = true)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Instalar", fontSize = 12.sp)
                            }
                        }
                    }

                    if (plugin.description.isNotBlank()) {
                        Text(plugin.description, fontSize = 12.sp, color = HermesTextPrimary)
                    }

                    if (plugin.capabilitySummary.isNotBlank()) {
                        Text(
                            text = "Capacidades: ${plugin.capabilitySummary}",
                            fontSize = 11.sp,
                            color = HermesTeal
                        )
                    }

                    if (plugin.scanFindings.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = HermesAmber.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "Escaneo de seguridad: ${plugin.scanFindings.size} hallazgo(s) revisados",
                                fontSize = 11.sp,
                                color = HermesAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (plugin.installed) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (plugin.settingsSchema.isNotEmpty()) "${plugin.settingsSchema.size} ajustes configurables"
                                else if (plugin.enabled) "Plugin activo en @$selectedProfile"
                                else "Instalado (pausado)",
                                fontSize = 11.sp,
                                color = HermesTextSecondary
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        vm.repo.uninstallStorePlugin(selectedProfile, plugin.name)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = HermesRose, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Desinstalar", fontSize = 12.sp, color = HermesRose)
                            }
                        }
                    }
                }
            }
        }
    }
}
