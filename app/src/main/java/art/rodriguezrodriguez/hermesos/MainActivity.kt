package art.rodriguezrodriguez.hermesos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import art.rodriguezrodriguez.hermesos.core.model.AuthorKind
import art.rodriguezrodriguez.hermesos.core.model.LinkStatus
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.feature.connection.ConnectionNodeScreen
import art.rodriguezrodriguez.hermesos.feature.desktop.DesktopCanvasScreen
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.feature.desktop.OsDockDestination
import art.rodriguezrodriguez.hermesos.feature.mcp.McpManagerScreen
import art.rodriguezrodriguez.hermesos.feature.models.ModelsAndProvidersScreen
import art.rodriguezrodriguez.hermesos.feature.plugins.PluginStoreScreen
import art.rodriguezrodriguez.hermesos.feature.teams.TeamsAndProfilesScreen
import art.rodriguezrodriguez.hermesos.ui.diagnostics.ContractDiagnosticsScreen
import art.rodriguezrodriguez.hermesos.ui.diagnostics.SettingsScreen
import art.rodriguezrodriguez.hermesos.ui.chat.ChatScreen
import art.rodriguezrodriguez.hermesos.ui.theme.*
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: HermesOsViewModel by viewModels {
        val app = application as HermesOsApp
        HermesOsViewModel.Factory(app.repository)
    }

    private val restClient: HermesRestClient by lazy {
        val app = application as HermesOsApp
        app.repository.restClient
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.setVoiceListeningState(false)
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                vm.onVoiceTranscriptCaptured(spoken)
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSystemSpeechPrompt()
        } else {
            vm.setVoiceListeningState(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "MX")
                ttsReady = true
            }
        }

        handleIncomingWidgetOrTileIntent(intent)

        setContent {
            HermesOsTheme {
                HermesOsShell(
                    vm = vm,
                    restClient = restClient,
                    onTriggerVoiceInput = { requestVoiceCapture() },
                    onSpeakText = { text ->
                        if (ttsReady && text.isNotBlank()) {
                            tts?.speak(text.take(360), TextToSpeech.QUEUE_FLUSH, null, "hermes-tts")
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingWidgetOrTileIntent(intent)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleIncomingWidgetOrTileIntent(incoming: Intent?) {
        if (incoming == null) return
        val focusScope = incoming.getStringExtra(EXTRA_FOCUS_SCOPE)
        if (!focusScope.isNullOrBlank()) {
            vm.setVoiceTarget(focusScope)
            vm.focusChatOnScope(focusScope, jumpToDesktop = true)
        }
        if (incoming.getBooleanExtra(EXTRA_START_VOICE, false)) {
            incoming.removeExtra(EXTRA_START_VOICE)
            requestVoiceCapture()
        }
    }

    private fun requestVoiceCapture() {
        val hasPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            launchSystemSpeechPrompt()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchSystemSpeechPrompt() {
        vm.setVoiceListeningState(true)
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con Hermes (${vm.voiceTargetScope.value})…")
        }
        runCatching {
            speechRecognizerLauncher.launch(recognizerIntent)
        }.onFailure {
            vm.setVoiceListeningState(false)
        }
    }

    companion object {
        const val EXTRA_START_VOICE = "extra_hermes_start_voice"
        const val EXTRA_FOCUS_SCOPE = "extra_hermes_focus_scope"
    }
}

@Composable
private fun HermesOsShell(
    vm: HermesOsViewModel,
    restClient: HermesRestClient,
    onTriggerVoiceInput: () -> Unit,
    onSpeakText: (String) -> Unit
) {
    val activeDest by vm.activeDestination.collectAsState()
    val connection by vm.repo.connection.collectAsState()
    val activeGlobalModel by vm.repo.activeGlobalModel.collectAsState()
    val approvals by vm.repo.approvals.collectAsState()
    val statusBanner by vm.repo.statusBanner.collectAsState()
    val focusedScope by vm.focusedChatScope.collectAsState()
    val messagesByScope by vm.repo.messagesByScope.collectAsState()

    var ttsAutoRead by remember { mutableStateOf(false) }
    var lastSpokenMsgId by remember { mutableStateOf("") }

    // Optional voice readback when a bot finishes responding
    val latestSettledBotMsg = messagesByScope[focusedScope]
        ?.lastOrNull { it.authorKind == AuthorKind.BOT && !it.isStreaming && it.content.isNotBlank() }

    LaunchedEffect(latestSettledBotMsg?.id, ttsAutoRead) {
        if (ttsAutoRead && latestSettledBotMsg != null && latestSettledBotMsg.id != lastSpokenMsgId) {
            lastSpokenMsgId = latestSettledBotMsg.id
            onSpeakText(latestSettledBotMsg.content)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(HermesBgDeep)
            .systemBarsPadding()
    ) {
        val useDesktopRail = maxWidth >= 700.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top OS Status & Quick-Control Bar ─────────────────────────────
            HermesTopOsBar(
                connectionStatus = connection.status,
                rungLabel = connection.activeRung?.kind?.name ?: "OFFLINE",
                wsConnected = connection.wsConnected,
                activeModel = activeGlobalModel,
                pendingApprovalsCount = approvals.size,
                ttsAutoRead = ttsAutoRead,
                onToggleTts = { ttsAutoRead = !ttsAutoRead },
                onTriggerVoice = onTriggerVoiceInput,
                onClickConnection = { vm.selectDestination(OsDockDestination.CONNECTION) },
                onClickModel = { vm.selectDestination(OsDockDestination.MODELS) }
            )

            // Auth Required Quick Notice
            if (connection.status == LinkStatus.AUTH_REQUIRED && activeDest != OsDockDestination.CONNECTION) {
                Surface(
                    color = HermesAmber.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, HermesAmber.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.selectDestination(OsDockDestination.CONNECTION) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Nodo Hermes detectado (${connection.serverVersion}). Toca aquí para iniciar sesión.",
                            fontSize = 12.sp,
                            color = HermesAmber,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = HermesAmber, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Transient Feedback Banner
            statusBanner?.let { msg ->
                Surface(
                    color = HermesTeal.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, HermesTeal.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = HermesTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { vm.repo.clearBanner() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ── Main Adaptive Workspace + Dock / Rail ─────────────────────────
            if (useDesktopRail) {
                Row(modifier = Modifier.weight(1f)) {
                    NavigationRail(
                        containerColor = HermesSurfaceCard
                    ) {
                        Spacer(Modifier.height(8.dp))
                        OsDockDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = activeDest == dest,
                                onClick = { vm.selectDestination(dest) },
                                icon = { Icon(iconForDestination(dest), contentDescription = dest.label) },
                                label = { Text(dest.label, fontSize = 11.sp) }
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        RenderDestinationContent(activeDest, vm, restClient, onTriggerVoiceInput)
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    RenderDestinationContent(activeDest, vm, restClient, onTriggerVoiceInput)
                }
                NavigationBar(
                    containerColor = HermesSurfaceCard,
                    tonalElevation = 4.dp
                ) {
                    OsDockDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = activeDest == dest,
                            onClick = { vm.selectDestination(dest) },
                            icon = { Icon(iconForDestination(dest), contentDescription = dest.label) },
                            label = { Text(dest.label, fontSize = 10.sp, maxLines = 1) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HermesTopOsBar(
    connectionStatus: LinkStatus,
    rungLabel: String,
    wsConnected: Boolean,
    activeModel: String,
    pendingApprovalsCount: Int,
    ttsAutoRead: Boolean,
    onToggleTts: () -> Unit,
    onTriggerVoice: () -> Unit,
    onClickConnection: () -> Unit,
    onClickModel: () -> Unit
) {
    val dotColor = when {
        connectionStatus == LinkStatus.CONNECTED && wsConnected -> HermesEmerald
        connectionStatus == LinkStatus.CONNECTED || connectionStatus == LinkStatus.AUTH_REQUIRED -> HermesAmber
        connectionStatus == LinkStatus.PROBING -> HermesTeal
        else -> HermesRose
    }

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
            // Left: Brand + Connection Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "HERMES OS",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    color = HermesTeal
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = HermesSurfaceElevated,
                    border = BorderStroke(1.dp, dotColor.copy(alpha = 0.8f)),
                    modifier = Modifier.clickable { onClickConnection() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = rungLabel + if (wsConnected) " · WS" else "",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HermesTextPrimary
                        )
                    }
                }
            }

            // Right: Model Badge, Approvals Indicator, TTS Toggle, Quick Mic
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeModel.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = HermesViolet.copy(alpha = 0.4f),
                        modifier = Modifier.clickable { onClickModel() }
                    ) {
                        Text(
                            text = activeModel.substringAfterLast('/').take(14),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HermesTextPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                if (pendingApprovalsCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = HermesAmber.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = "⚠ $pendingApprovalsCount",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = HermesAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onToggleTts,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (ttsAutoRead) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Lectura TTS",
                        tint = if (ttsAutoRead) HermesTeal else HermesTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onTriggerVoice,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(HermesRose.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Hablar con Hermes",
                        tint = HermesRose,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderDestinationContent(
    destination: OsDockDestination,
    vm: HermesOsViewModel,
    restClient: HermesRestClient,
    onTriggerVoiceInput: () -> Unit
) {
    when (destination) {
        OsDockDestination.DESKTOP -> DesktopCanvasScreen(
            vm = vm,
            onRequestSystemVoiceInput = onTriggerVoiceInput
        )
        OsDockDestination.TEAMS -> TeamsAndProfilesScreen(vm = vm)
        OsDockDestination.MODELS -> ModelsAndProvidersScreen(vm = vm)
        OsDockDestination.MCP -> McpManagerScreen(vm = vm)
        OsDockDestination.PLUGINS -> PluginStoreScreen(vm = vm)
        OsDockDestination.CONNECTION -> ConnectionNodeScreen(vm = vm)
                OsDockDestination.SETTINGS -> SettingsScreen(vm = vm, client = restClient)
                OsDockDestination.DIAGNOSTICS -> ContractDiagnosticsScreen(client = restClient)
                OsDockDestination.CHAT -> ChatScreen(vm = vm, scopeId = vm.focusedChatScope.value, onBack = { vm.selectDestination(OsDockDestination.DESKTOP) })
            }
        }

private fun iconForDestination(dest: OsDockDestination): ImageVector = when (dest) {
    OsDockDestination.DESKTOP -> Icons.Default.SpaceDashboard
    OsDockDestination.TEAMS -> Icons.Default.Groups
    OsDockDestination.MODELS -> Icons.Default.Tune
    OsDockDestination.MCP -> Icons.Default.Cable
    OsDockDestination.PLUGINS -> Icons.Default.Storefront
    OsDockDestination.CONNECTION -> Icons.Default.Router
    OsDockDestination.SETTINGS -> Icons.Default.Settings
    OsDockDestination.DIAGNOSTICS -> Icons.Default.BugReport
    OsDockDestination.CHAT -> Icons.Default.Chat
}




