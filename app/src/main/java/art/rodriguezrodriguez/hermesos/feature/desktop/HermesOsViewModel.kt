package art.rodriguezrodriguez.hermesos.feature.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import art.rodriguezrodriguez.hermesos.core.model.*
import art.rodriguezrodriguez.hermesos.data.repository.HermesOsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class OsDockDestination(val label: String) {
    DESKTOP("Desktop"),
    TEAMS("Equipos"),
    MODELS("Modelos"),
    MCP("MCPs"),
    PLUGINS("Tienda"),
    CONNECTION("Nodo")
}

class HermesOsViewModel(
    val repo: HermesOsRepository
) : ViewModel() {

    private val _activeDestination = MutableStateFlow(OsDockDestination.DESKTOP)
    val activeDestination: StateFlow<OsDockDestination> = _activeDestination.asStateFlow()

    private val _focusedChatScope = MutableStateFlow("bot:default")
    val focusedChatScope: StateFlow<String> = _focusedChatScope.asStateFlow()

    private val _selectedManagementProfile = MutableStateFlow("default")
    val selectedManagementProfile: StateFlow<String> = _selectedManagementProfile.asStateFlow()

    private val _voiceTargetScope = MutableStateFlow("bot:domo")
    val voiceTargetScope: StateFlow<String> = _voiceTargetScope.asStateFlow()

    private val _isListeningVoice = MutableStateFlow(false)
    val isListeningVoice: StateFlow<Boolean> = _isListeningVoice.asStateFlow()

    private val _lastVoiceTranscript = MutableStateFlow("")
    val lastVoiceTranscript: StateFlow<String> = _lastVoiceTranscript.asStateFlow()

    val activePreset: StateFlow<WorkspacePreset> = repo.prefsStore.activePresetFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, WorkspacePreset.COMMAND_CENTER)

    val desktopWidgets: StateFlow<List<DesktopWidgetPlacement>> = repo.prefsStore.widgetsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultWidgetsForPreset(WorkspacePreset.COMMAND_CENTER))

    val connectionRungs: StateFlow<List<ConnectionRung>> = repo.prefsStore.connectionRungsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultConnectionRungs())

    init {
        viewModelScope.launch {
            repo.connectUsingLadder()
        }
    }

    fun selectDestination(dest: OsDockDestination) {
        _activeDestination.value = dest
    }

    fun setManagementProfile(profile: String) {
        _selectedManagementProfile.value = profile
        viewModelScope.launch {
            repo.refreshModels(profile)
            repo.refreshProvidersAndConfig(profile)
            repo.refreshMcpData(profile)
            repo.refreshPluginStore(profile)
        }
    }

    // ── Desktop Mode Modular Widget Canvas Actions ────────────────────────────

    fun applyPreset(preset: WorkspacePreset) {
        viewModelScope.launch {
            repo.prefsStore.applyWorkspacePreset(preset)
        }
    }

    fun addDesktopWidget(type: DesktopWidgetType, targetScope: String = _focusedChatScope.value) {
        viewModelScope.launch {
            val current = desktopWidgets.value.toMutableList()
            current += DesktopWidgetPlacement(
                id = "w-${type.name.lowercase()}-${UUID.randomUUID().toString().take(5)}",
                type = type,
                span = if (type == DesktopWidgetType.TEAM_CHAT || type == DesktopWidgetType.BOT_WALL) WidgetSpan.WIDE else WidgetSpan.STANDARD,
                targetScope = targetScope,
                order = current.size
            )
            repo.prefsStore.saveWidgets(current)
        }
    }

    fun removeDesktopWidget(widgetId: String) {
        viewModelScope.launch {
            val updated = desktopWidgets.value.filterNot { it.id == widgetId }
            repo.prefsStore.saveWidgets(updated)
        }
    }

    fun cycleWidgetSpan(widgetId: String) {
        viewModelScope.launch {
            val updated = desktopWidgets.value.map { w ->
                if (w.id != widgetId) w else {
                    val next = when (w.span) {
                        WidgetSpan.SMALL -> WidgetSpan.STANDARD
                        WidgetSpan.STANDARD -> WidgetSpan.WIDE
                        WidgetSpan.WIDE -> WidgetSpan.MAXIMIZED
                        WidgetSpan.MAXIMIZED -> WidgetSpan.SMALL
                    }
                    w.copy(span = next)
                }
            }
            repo.prefsStore.saveWidgets(updated)
        }
    }

    fun moveWidgetOrder(widgetId: String, delta: Int) {
        viewModelScope.launch {
            val list = desktopWidgets.value.sortedBy { it.order }.toMutableList()
            val idx = list.indexOfFirst { it.id == widgetId }
            if (idx < 0) return@launch
            val targetIdx = (idx + delta).coerceIn(0, list.lastIndex)
            if (targetIdx == idx) return@launch
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            repo.prefsStore.saveWidgets(list)
        }
    }

    fun focusChatOnScope(scopeId: String, jumpToDesktop: Boolean = true) {
        _focusedChatScope.value = scopeId
        if (scopeId.startsWith("room:")) {
            viewModelScope.launch {
                repo.syncRoomTranscript(scopeId.removePrefix("room:"))
            }
        }
        if (jumpToDesktop) {
            _activeDestination.value = OsDockDestination.DESKTOP
        }
    }

    // ── Chat, Voice & Team Dispatch ───────────────────────────────────────────

    fun sendChat(scopeId: String, text: String) {
        viewModelScope.launch {
            repo.sendMessageToScope(scopeId, text, isVoiceTurn = false)
        }
    }

    fun setVoiceTarget(scopeId: String) {
        _voiceTargetScope.value = scopeId
    }

    fun setVoiceListeningState(listening: Boolean) {
        _isListeningVoice.value = listening
    }

    fun onVoiceTranscriptCaptured(transcript: String) {
        _isListeningVoice.value = false
        val clean = transcript.trim()
        if (clean.isEmpty()) return
        _lastVoiceTranscript.value = clean
        val target = _voiceTargetScope.value
        _focusedChatScope.value = target
        viewModelScope.launch {
            repo.sendMessageToScope(target, clean, isVoiceTurn = true)
        }
    }

    fun approveOrDeny(approval: PendingToolApproval, allow: Boolean) {
        viewModelScope.launch {
            repo.resolveApproval(approval, allow)
        }
    }

    class Factory(private val repo: HermesOsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HermesOsViewModel(repo) as T
        }
    }
}
