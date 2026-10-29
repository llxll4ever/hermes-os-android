package art.rodriguezrodriguez.hermesos.feature.models

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.network.CredentialGuards
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch

private enum class ModelsHubTab(val title: String) {
    MODELS("Modelos & Registro Manual"),
    PROVIDERS("Providers & API Keys"),
    CONFIG_YAML("config.yaml")
}

/**
 * Phase 4 Hub:
 * - Add or remove models manually (`WorkspacePreferencesStore` + `/api/model/options` & `/api/model/set`) and assign them
 *   globally or per specialist bot (`@programador`, `@disenador`, `@domo`, etc.).
 * - Manage custom OpenAI-compatible endpoints (OmniRoute, Ollama, Proxy Router, Groq, Cerebras)
 *   and safely update `/api/env` secrets without ever overwriting redacted values.
 * - Inspect and edit `config.yaml` per profile.
 */
@Composable
fun ModelsAndProvidersScreen(vm: HermesOsViewModel) {
    val scope = rememberCoroutineScope()
    val bots by vm.repo.bots.collectAsState()
    val selectedProfile by vm.selectedManagementProfile.collectAsState()
    val models by vm.repo.models.collectAsState()
    val activeGlobalModel by vm.repo.activeGlobalModel.collectAsState()
    val customEndpoints by vm.repo.customEndpoints.collectAsState()
    val envVars by vm.repo.envVars.collectAsState()
    val rawYaml by vm.repo.rawConfigYaml.collectAsState()

    var activeTab by remember { mutableStateOf(ModelsHubTab.MODELS) }

    // Manual Model Form State
    var manualSlug by remember { mutableStateOf("") }
    var manualProvider by remember { mutableStateOf("custom") }
    var manualLabel by remember { mutableStateOf("") }
    var modelSearch by remember { mutableStateOf("") }

    // Custom Endpoint Form State
    var epName by remember { mutableStateOf("") }
    var epUrl by remember { mutableStateOf("") }
    var epApiKey by remember { mutableStateOf("") }
    var epDefaultModel by remember { mutableStateOf("") }

    // Env Var Editor State
    var newEnvKey by remember { mutableStateOf("") }
    var newEnvValue by remember { mutableStateOf("") }
    var editingEnvKey by remember { mutableStateOf<String?>(null) }
    var editingEnvSecret by remember { mutableStateOf("") }

    // Raw YAML Draft State
    var yamlDraft by remember(rawYaml, selectedProfile) { mutableStateOf(rawYaml) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Modelos, Providers & Configs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = HermesTextPrimary
                )
                Text(
                    text = "Global activo: ${activeGlobalModel.ifBlank { "auto / default" }}",
                    fontSize = 12.sp,
                    color = HermesTeal
                )
            }
            IconButton(
                onClick = {
                    scope.launch {
                        vm.repo.refreshModels(selectedProfile)
                        vm.repo.refreshProvidersAndConfig(selectedProfile)
                    }
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refrescar", tint = HermesTeal)
            }
        }

        // Target Bot Profile Selector Bar
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Perfil objetivo para configs y asignación por bot:",
                fontSize = 11.sp,
                color = HermesTextSecondary
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                bots.forEach { bot ->
                    FilterChip(
                        selected = selectedProfile == bot.profile,
                        onClick = { vm.setManagementProfile(bot.profile) },
                        label = {
                            Text(
                                text = "@${bot.handle}" + if (bot.activeModel.isNotBlank()) " · ${bot.activeModel.take(14)}" else "",
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }
        }

        // Sub-Tab Switcher
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelsHubTab.entries.forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    label = { Text(tab.title, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        when (activeTab) {
            ModelsHubTab.MODELS -> {
                // 1. Add Model Manually Card
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
                            text = "Agregar Modelo Manualmente",
                            fontWeight = FontWeight.Bold,
                            color = HermesTeal
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = {
                                    manualSlug = "omniroute/claude-3.7-sonnet"
                                    manualProvider = "omniroute"
                                    manualLabel = "OmniRoute Claude Sonnet"
                                },
                                label = { Text("+ OmniRoute", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    manualSlug = "qwen2.5-coder:14b"
                                    manualProvider = "ollama"
                                    manualLabel = "Ollama Qwen 2.5 Coder"
                                },
                                label = { Text("+ Ollama Local", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    manualSlug = "llama-3.3-70b-versatile"
                                    manualProvider = "groq"
                                    manualLabel = "Groq Llama 3.3 70B"
                                },
                                label = { Text("+ Groq", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    manualSlug = "gemini-2.5-pro"
                                    manualProvider = "google"
                                    manualLabel = "Gemini 2.5 Pro"
                                },
                                label = { Text("+ Gemini", fontSize = 11.sp) }
                            )
                        }

                        OutlinedTextField(
                            value = manualSlug,
                            onValueChange = { manualSlug = it },
                            label = { Text("Slug exacto del modelo (ej. anthropic/claude-opus-4-6)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = manualProvider,
                                onValueChange = { manualProvider = it },
                                label = { Text("Provider") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = manualLabel,
                                onValueChange = { manualLabel = it },
                                label = { Text("Alias visible") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                if (manualSlug.isNotBlank()) {
                                    scope.launch {
                                        vm.repo.addManualModel(manualSlug, manualProvider, manualLabel)
                                        manualSlug = ""
                                        manualLabel = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Guardar en Registro Manual")
                        }
                    }
                }

                // 2. Search & Assign Models List
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Filtrar modelos (${models.size} disponibles)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val filteredModels = remember(models, modelSearch) {
                    val q = modelSearch.trim().lowercase()
                    if (q.isEmpty()) models else models.filter {
                        it.slug.lowercase().contains(q) ||
                            it.label.lowercase().contains(q) ||
                            it.provider.lowercase().contains(q)
                    }
                }

                filteredModels.forEach { m ->
                    val isGlobal = m.slug == activeGlobalModel || m.isActiveGlobal
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = HermesSurfaceCard,
                        border = BorderStroke(
                            1.dp,
                            if (isGlobal) HermesTeal else HermesBorderSubtle
                        )
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
                                    Text(
                                        text = m.label,
                                        fontWeight = FontWeight.Bold,
                                        color = HermesTextPrimary
                                    )
                                    Text(
                                        text = "${m.provider} · ${m.slug}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = HermesTextSecondary
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (m.isManual) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = HermesViolet.copy(alpha = 0.22f)
                                        ) {
                                            Text(
                                                text = "MANUAL",
                                                fontSize = 10.sp,
                                                color = HermesViolet,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (isGlobal) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = HermesTeal.copy(alpha = 0.22f)
                                        ) {
                                            Text(
                                                text = "GLOBAL",
                                                fontSize = 10.sp,
                                                color = HermesTeal,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            vm.repo.assignModelToGlobalOrBot(m.slug, m.provider, null)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Usar Global", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            vm.repo.assignModelToGlobalOrBot(m.slug, m.provider, selectedProfile)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Asignar @$selectedProfile", fontSize = 12.sp)
                                }
                                if (m.isManual) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                vm.repo.removeManualModel(m.slug, m.provider)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Quitar modelo manual",
                                            tint = HermesRose
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ModelsHubTab.PROVIDERS -> {
                // 1. Custom OpenAI-Compatible Provider Endpoints
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
                            text = "Conectar Provider / Endpoint OpenAI-Compatible",
                            fontWeight = FontWeight.Bold,
                            color = HermesTeal
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = {
                                    epName = "OmniRoute Local"
                                    epUrl = "http://127.0.0.1:20128/v1"
                                    epDefaultModel = "omniroute/auto-combo"
                                },
                                label = { Text("OmniRoute :20128", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    epName = "Ollama VPS"
                                    epUrl = "http://127.0.0.1:11434/v1"
                                    epDefaultModel = "qwen2.5-coder:14b"
                                },
                                label = { Text("Ollama :11434", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    epName = "Hermes Proxy Router"
                                    epUrl = "http://127.0.0.1:8889/v1"
                                    epDefaultModel = "auto"
                                },
                                label = { Text("Proxy Router :8889", fontSize = 11.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    epName = "Groq Cloud"
                                    epUrl = "https://api.groq.com/openai/v1"
                                    epDefaultModel = "llama-3.3-70b-versatile"
                                },
                                label = { Text("Groq API", fontSize = 11.sp) }
                            )
                        }

                        OutlinedTextField(
                            value = epName,
                            onValueChange = { epName = it },
                            label = { Text("Nombre del Provider") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = epUrl,
                            onValueChange = { epUrl = it },
                            label = { Text("Base URL (ej. http://127.0.0.1:20128/v1)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = epApiKey,
                                onValueChange = { epApiKey = it },
                                label = { Text("API Key (opcional)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = epDefaultModel,
                                onValueChange = { epDefaultModel = it },
                                label = { Text("Modelo default") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                if (epName.isNotBlank() && epUrl.isNotBlank()) {
                                    scope.launch {
                                        vm.repo.createCustomEndpoint(
                                            profile = selectedProfile,
                                            name = epName,
                                            baseUrl = epUrl,
                                            apiKey = epApiKey,
                                            model = epDefaultModel
                                        )
                                        epName = ""
                                        epUrl = ""
                                        epApiKey = ""
                                        epDefaultModel = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Hub, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Registrar Provider en @$selectedProfile")
                        }
                    }
                }

                // Existing Custom Endpoints
                if (customEndpoints.isNotEmpty()) {
                    Text(
                        text = "Endpoints Personalizados (${customEndpoints.size})",
                        fontWeight = FontWeight.Bold,
                        color = HermesViolet
                    )
                    customEndpoints.forEach { ep ->
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
                                    Text(ep.name, fontWeight = FontWeight.Bold, color = HermesTextPrimary)
                                    Text(ep.baseUrl, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = HermesTeal)
                                    if (ep.defaultModel.isNotBlank()) {
                                        Text("Modelo: ${ep.defaultModel}", fontSize = 11.sp, color = HermesTextSecondary)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (vm.repo.restClient.deleteCustomEndpoint(selectedProfile, ep.id)) {
                                                vm.repo.refreshProvidersAndConfig(selectedProfile)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar", tint = HermesRose)
                                }
                            }
                        }
                    }
                }

                // 2. API Keys & Environment Secrets (`/api/env`)
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
                            text = "Actualizar o Agregar API Key (/api/env)",
                            fontWeight = FontWeight.Bold,
                            color = HermesAmber
                        )
                        Text(
                            text = "Protegido por CredentialGuards: nunca reenvía valores «redacted:…».",
                            fontSize = 11.sp,
                            color = HermesTextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newEnvKey,
                                onValueChange = { newEnvKey = it.uppercase() },
                                label = { Text("Variable (ej. GROQ_API_KEY)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newEnvValue,
                                onValueChange = { newEnvValue = it },
                                label = { Text("Secreto nuevo") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                if (newEnvKey.isNotBlank() &&
                                    newEnvValue.isNotBlank() &&
                                    !CredentialGuards.isRedactedPreview(newEnvValue)
                                ) {
                                    scope.launch {
                                        vm.repo.saveProviderSecret(selectedProfile, newEnvKey.trim(), newEnvValue.trim())
                                        newEnvKey = ""
                                        newEnvValue = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Guardar Credencial en @$selectedProfile")
                        }
                    }
                }

                envVars.forEach { env ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HermesSurfaceCard,
                        border = BorderStroke(1.dp, HermesBorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = env.key,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = HermesTextPrimary
                                )
                                Text(
                                    text = "${env.providerGroup} · ${env.valuePreview.ifBlank { if (env.isConfigured) "Configurada" else "Sin configurar" }}",
                                    fontSize = 11.sp,
                                    color = if (env.isConfigured) HermesEmerald else HermesTextSecondary
                                )
                            }
                            TextButton(
                                onClick = {
                                    editingEnvKey = env.key
                                    editingEnvSecret = ""
                                }
                            ) {
                                Text("Rotar Key", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            ModelsHubTab.CONFIG_YAML -> {
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
                                text = "config.yaml (@$selectedProfile)",
                                fontWeight = FontWeight.Bold,
                                color = HermesTeal
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        vm.repo.refreshProvidersAndConfig(selectedProfile)
                                    }
                                }
                            ) {
                                Text("Recargar desde VPS")
                            }
                        }

                        OutlinedTextField(
                            value = yamlDraft,
                            onValueChange = { yamlDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp),
                            placeholder = { Text("# config.yaml del perfil @$selectedProfile…") },
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        )

                        Button(
                            onClick = {
                                scope.launch {
                                    vm.repo.saveConfigYaml(selectedProfile, yamlDraft)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Guardar config.yaml en Hermes")
                        }
                    }
                }
            }
        }
    }

    // Rotate Existing Secret Dialog
    editingEnvKey?.let { targetKey ->
        AlertDialog(
            onDismissRequest = { editingEnvKey = null },
            title = { Text("Actualizar $targetKey") },
            text = {
                OutlinedTextField(
                    value = editingEnvSecret,
                    onValueChange = { editingEnvSecret = it },
                    label = { Text("Nuevo valor secreto") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editingEnvSecret.isNotBlank() && !CredentialGuards.isRedactedPreview(editingEnvSecret)) {
                            scope.launch {
                                vm.repo.saveProviderSecret(selectedProfile, targetKey, editingEnvSecret.trim())
                                editingEnvKey = null
                            }
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEnvKey = null }) { Text("Cancelar") }
            }
        )
    }
}
