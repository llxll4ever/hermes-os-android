package art.rodriguezrodriguez.hermesos.feature.plugins

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.model.PluginCatalogItemUi
import art.rodriguezrodriguez.hermesos.core.model.PluginSettingFieldUi
import art.rodriguezrodriguez.hermesos.core.model.PluginUpdateOutcome
import art.rodriguezrodriguez.hermesos.core.plugins.PluginSettingsCodec
import art.rodriguezrodriguez.hermesos.core.plugins.PluginSettingsValidationException
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.ui.theme.*
import kotlinx.coroutines.launch

private data class PendingPluginUpdatePrompt(
    val plugin: PluginCatalogItemUi,
    val outcome: PluginUpdateOutcome,
    val acceptedCapabilitiesSoFar: Boolean = false
)

/**
 * Phase 5b Hub — Hermes Plugin Store with:
 * - Search, Tier Filters, Git Install & Security Scanner badges
 * - Dynamic Typed Settings Editor (`settingsSchema`: `string`, `number`, `boolean`, `enum`, `secret`, `json`)
 * - Update Consent (`consent_required` capability delta) & Security Scan Gate (`caution` force vs `dangerous` hard-block)
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

    var editingSettingsPlugin by remember { mutableStateOf<PluginCatalogItemUi?>(null) }
    var pendingUpdatePrompt by remember { mutableStateOf<PendingPluginUpdatePrompt?>(null) }
    var updatingPluginKey by remember { mutableStateOf<String?>(null) }

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

    fun triggerUpdate(
        plugin: PluginCatalogItemUi,
        acceptCapabilities: Boolean = false,
        force: Boolean = false
    ) {
        updatingPluginKey = plugin.pluginKey
        scope.launch {
            val outcome = vm.repo.updateStorePlugin(
                profile = selectedProfile,
                plugin = plugin,
                acceptCapabilities = acceptCapabilities,
                force = force
            )
            updatingPluginKey = null
            when (outcome) {
                is PluginUpdateOutcome.ConsentRequired -> {
                    pendingUpdatePrompt = PendingPluginUpdatePrompt(
                        plugin = plugin,
                        outcome = outcome,
                        acceptedCapabilitiesSoFar = acceptCapabilities
                    )
                }
                is PluginUpdateOutcome.ScanBlocked -> {
                    pendingUpdatePrompt = PendingPluginUpdatePrompt(
                        plugin = plugin,
                        outcome = outcome,
                        acceptedCapabilitiesSoFar = acceptCapabilities
                    )
                }
                is PluginUpdateOutcome.Applied,
                is PluginUpdateOutcome.Failed -> {
                    pendingUpdatePrompt = null
                }
            }
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
            val isUpdatingThis = updatingPluginKey == plugin.pluginKey

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
                                        vm.repo.toggleStorePlugin(selectedProfile, plugin, enable)
                                    }
                                }
                            )
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        vm.repo.installStorePlugin(selectedProfile, plugin.catalogName, fromCatalog = true)
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
                                text = when {
                                    plugin.settingsSchema.isNotEmpty() ->
                                        "${plugin.settingsSchema.size} ajustes configurables (@$selectedProfile)"
                                    plugin.enabled -> "Plugin activo en @$selectedProfile"
                                    else -> "Instalado (pausado)"
                                },
                                fontSize = 11.sp,
                                color = HermesTextSecondary,
                                modifier = Modifier.weight(1f)
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (plugin.settingsSchema.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { editingSettingsPlugin = plugin },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Tune,
                                            contentDescription = null,
                                            tint = HermesTeal,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Configurar (${plugin.settingsSchema.size})",
                                            fontSize = 11.sp,
                                            color = HermesTeal
                                        )
                                    }
                                }

                                if (plugin.updateAvailable || plugin.canUpdate) {
                                    FilledTonalButton(
                                        onClick = { triggerUpdate(plugin) },
                                        enabled = !isUpdatingThis,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.SystemUpdateAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (isUpdatingThis) "Actualizando…" else "Actualizar",
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (plugin.canRemove) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                vm.repo.uninstallStorePlugin(selectedProfile, plugin)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = HermesRose,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Desinstalar", fontSize = 11.sp, color = HermesRose)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 1. Dynamic Typed Settings Editor Dialog
    editingSettingsPlugin?.let { targetPlugin ->
        PluginSettingsDialog(
            plugin = targetPlugin,
            profile = selectedProfile,
            onDismiss = { editingSettingsPlugin = null },
            onSave = { drafts ->
                vm.repo.savePluginConfiguration(selectedProfile, targetPlugin, drafts)
            }
        )
    }

    // 2. Capability Consent & Security Scanner Gate Dialog
    pendingUpdatePrompt?.let { prompt ->
        PluginSecurityConsentDialog(
            prompt = prompt,
            onDismiss = { pendingUpdatePrompt = null },
            onAcceptCapabilities = {
                val p = prompt.plugin
                pendingUpdatePrompt = null
                triggerUpdate(p, acceptCapabilities = true, force = false)
            },
            onForceCautionScan = {
                val p = prompt.plugin
                val acceptedCaps = prompt.acceptedCapabilitiesSoFar
                pendingUpdatePrompt = null
                triggerUpdate(p, acceptCapabilities = acceptedCaps, force = true)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSettingsDialog(
    plugin: PluginCatalogItemUi,
    profile: String,
    onDismiss: () -> Unit,
    onSave: suspend (Map<String, String>) -> Result<List<String>>
) {
    val scope = rememberCoroutineScope()
    val drafts = remember(plugin.pluginKey, plugin.settingsSchema) {
        mutableStateMapOf<String, String>().apply {
            putAll(PluginSettingsCodec.initialDrafts(plugin.settingsSchema))
        }
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorFieldKey by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = HermesSurfaceCard,
        title = {
            Column {
                Text(
                    text = "Configurar ${plugin.name}",
                    fontWeight = FontWeight.Bold,
                    color = HermesTextPrimary
                )
                Text(
                    text = "Perfil @$profile · Clave '${plugin.pluginKey}'",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HermesTeal
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                plugin.settingsSchema.forEach { field ->
                    PluginSettingFieldControl(
                        field = field,
                        value = drafts[field.key].orEmpty(),
                        isError = errorFieldKey == field.key,
                        onValueChange = { next ->
                            drafts[field.key] = next
                            if (errorFieldKey == field.key) {
                                errorFieldKey = null
                                errorMessage = null
                            }
                        }
                    )
                }

                errorMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = HermesRose.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, HermesRose.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = HermesRose,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    isSaving = true
                    errorMessage = null
                    errorFieldKey = null
                    scope.launch {
                        val res = onSave(drafts.toMap())
                        isSaving = false
                        res.onSuccess {
                            onDismiss()
                        }.onFailure { err ->
                            errorFieldKey = (err as? PluginSettingsValidationException)?.fieldKey
                            errorMessage = err.message ?: "Error al guardar la configuración"
                        }
                    }
                }
            ) {
                Text(if (isSaving) "Guardando…" else "Guardar")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text("Cancelar")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSettingFieldControl(
    field: PluginSettingFieldUi,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit
) {
    val type = field.type.trim().lowercase()
    val titleSuffix = if (field.required) " *" else ""

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (type) {
            "boolean" -> {
                val checked = value.equals("true", ignoreCase = true) || value == "1"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = field.label + titleSuffix,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = HermesTextPrimary
                        )
                        if (field.description.isNotBlank()) {
                            Text(field.description, fontSize = 11.sp, color = HermesTextSecondary)
                        }
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = { onValueChange(if (it) "true" else "false") }
                    )
                }
            }

            "enum" -> {
                Text(
                    text = field.label + titleSuffix,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = HermesTextPrimary
                )
                if (field.description.isNotBlank()) {
                    Text(field.description, fontSize = 11.sp, color = HermesTextSecondary)
                }
                if (field.choices.size <= 5 && field.choices.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        field.choices.forEach { choice ->
                            FilterChip(
                                selected = value == choice,
                                onClick = { onValueChange(choice) },
                                label = { Text(choice, fontSize = 12.sp) }
                            )
                        }
                    }
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = {},
                            readOnly = true,
                            isError = isError,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            field.choices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice) },
                                    onClick = {
                                        onValueChange(choice)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            "secret" -> {
                val envBadge = field.envKey ?: field.key.uppercase()
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("${field.label}$titleSuffix ($envBadge)") },
                    placeholder = {
                        Text(
                            if (field.hasSecretValue) "Configurado en $envBadge (deja vacío para conservar)"
                            else "Ingresa valor secreto para /api/env",
                            fontSize = 11.sp
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (field.description.isNotBlank()) {
                    Text(field.description, fontSize = 11.sp, color = HermesTextSecondary)
                }
            }

            "number" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(field.label + titleSuffix) },
                    placeholder = {
                        if (field.defaultValue.isNotBlank()) Text("Default: ${field.defaultValue}")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (field.description.isNotBlank()) {
                    Text(field.description, fontSize = 11.sp, color = HermesTextSecondary)
                }
            }

            "json" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("${field.label}$titleSuffix (JSON)") },
                    placeholder = { Text("{\"clave\": \"valor\"} o [\"item\"]", fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    isError = isError,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                if (field.description.isNotBlank()) {
                    Text(field.description, fontSize = 11.sp, color = HermesTextSecondary)
                }
            }

            else -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(field.label + titleSuffix) },
                    placeholder = {
                        if (field.defaultValue.isNotBlank()) Text("Default: ${field.defaultValue}")
                    },
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (field.description.isNotBlank()) {
                    Text(field.description, fontSize = 11.sp, color = HermesTextSecondary)
                }
            }
        }
    }
}

@Composable
private fun PluginSecurityConsentDialog(
    prompt: PendingPluginUpdatePrompt,
    onDismiss: () -> Unit,
    onAcceptCapabilities: () -> Unit,
    onForceCautionScan: () -> Unit
) {
    when (val outcome = prompt.outcome) {
        is PluginUpdateOutcome.ConsentRequired -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = HermesSurfaceCard,
                title = {
                    Text(
                        text = "Permisos ampliados en '${outcome.pluginName}'",
                        fontWeight = FontWeight.Bold,
                        color = HermesAmber
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "La nueva revisión${outcome.sha.takeIf { it.isNotBlank() }?.let { " (#${it.take(8)})" }.orEmpty()} solicita capacidades adicionales:",
                            fontSize = 12.sp,
                            color = HermesTextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = HermesSurfaceElevated,
                            border = BorderStroke(1.dp, HermesAmber.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                outcome.deltaLines.forEach { line ->
                                    Text(
                                        text = "• $line",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = HermesAmber
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = onAcceptCapabilities) {
                        Text("Aceptar capacidades y actualizar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                }
            )
        }

        is PluginUpdateOutcome.ScanBlocked -> {
            val isDangerous = !outcome.canForce
            val accent = if (isDangerous) HermesRose else HermesAmber
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = HermesSurfaceCard,
                title = {
                    Text(
                        text = if (isDangerous) {
                            "Actualización bloqueada (DANGEROUS)"
                        } else {
                            "Revisión de seguridad (CAUTION)"
                        },
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isDangerous) {
                                "El escáner de Hermes bloqueó '${outcome.pluginName}' con veredicto '${outcome.verdict}'. Por seguridad, el backend no permite forzar veredictos peligrosos."
                            } else {
                                "El escáner detectó ${outcome.findings.size} hallazgo(s) de precaución en '${outcome.pluginName}'. Revisa cada línea antes de forzar:"
                            },
                            fontSize = 12.sp,
                            color = HermesTextPrimary
                        )
                        outcome.findings.forEach { f ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = HermesSurfaceElevated,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "[${f.severity.uppercase()}] ${f.category} — ${f.file}:${f.line}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = accent
                                    )
                                    if (f.description.isNotBlank()) {
                                        Text(
                                            text = f.description,
                                            fontSize = 11.sp,
                                            color = HermesTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (outcome.canForce) {
                        Button(onClick = onForceCautionScan) {
                            Text("Forzar bajo mi responsabilidad")
                        }
                    } else {
                        Button(onClick = onDismiss) {
                            Text("Entendido")
                        }
                    }
                },
                dismissButton = {
                    if (outcome.canForce) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancelar")
                        }
                    }
                }
            )
        }

        else -> Unit
    }
}
