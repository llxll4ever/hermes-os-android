package art.rodriguezrodriguez.hermesos.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import art.rodriguezrodriguez.hermesos.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.hermesDataStore by preferencesDataStore(name = "hermes_os_workspace")

/**
 * Durable local store for:
 * - Desktop Mode Widget placements & active WorkspacePreset
 * - Connection Ladder rungs (Tailscale, Cloudflare, Custom URLs)
 * - Manual Model Registry (models manually added/removed by the user)
 * - Cached Live Bot Wall summary for Android Launcher AppWidgets (Jetpack Glance)
 */
class WorkspacePreferencesStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val ACTIVE_PRESET = stringPreferencesKey("active_preset")
        val WIDGET_LAYOUT = stringPreferencesKey("widget_layout_json")
        val CONNECTION_RUNGS = stringPreferencesKey("connection_rungs_json")
        val MANUAL_MODELS = stringPreferencesKey("manual_models_json")
        val GLANCE_WALL_SUMMARY = stringPreferencesKey("glance_wall_summary_json")
        val ACTIVE_MANAGEMENT_PROFILE = stringPreferencesKey("active_management_profile")
    }

    val activePresetFlow: Flow<WorkspacePreset> = context.hermesDataStore.data.map { prefs ->
        val raw = prefs[Keys.ACTIVE_PRESET]
        WorkspacePreset.entries.firstOrNull { it.name == raw } ?: WorkspacePreset.COMMAND_CENTER
    }

    val widgetsFlow: Flow<List<DesktopWidgetPlacement>> = context.hermesDataStore.data.map { prefs ->
        val raw = prefs[Keys.WIDGET_LAYOUT]
        if (raw.isNullOrBlank()) {
            defaultWidgetsForPreset(WorkspacePreset.COMMAND_CENTER)
        } else {
            runCatching { json.decodeFromString<List<DesktopWidgetPlacement>>(raw) }
                .getOrElse { defaultWidgetsForPreset(WorkspacePreset.COMMAND_CENTER) }
        }
    }

    val connectionRungsFlow: Flow<List<ConnectionRung>> = context.hermesDataStore.data.map { prefs ->
        val raw = prefs[Keys.CONNECTION_RUNGS]
        if (raw.isNullOrBlank()) {
            defaultConnectionRungs()
        } else {
            runCatching { json.decodeFromString<List<ConnectionRung>>(raw) }
                .getOrElse { defaultConnectionRungs() }
        }
    }

    val manualModelsFlow: Flow<List<ManualModelEntry>> = context.hermesDataStore.data.map { prefs ->
        val raw = prefs[Keys.MANUAL_MODELS]
        if (raw.isNullOrBlank()) {
            defaultSeedManualModels()
        } else {
            runCatching { json.decodeFromString<List<ManualModelEntry>>(raw) }
                .getOrElse { defaultSeedManualModels() }
        }
    }

    suspend fun applyWorkspacePreset(preset: WorkspacePreset) {
        val widgets = defaultWidgetsForPreset(preset)
        context.hermesDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_PRESET] = preset.name
            prefs[Keys.WIDGET_LAYOUT] = json.encodeToString(widgets)
        }
    }

    suspend fun saveWidgets(widgets: List<DesktopWidgetPlacement>) {
        val normalized = widgets.mapIndexed { idx, w -> w.copy(order = idx) }
        context.hermesDataStore.edit { prefs ->
            prefs[Keys.WIDGET_LAYOUT] = json.encodeToString(normalized)
        }
    }

    suspend fun saveConnectionRungs(rungs: List<ConnectionRung>) {
        context.hermesDataStore.edit { prefs ->
            prefs[Keys.CONNECTION_RUNGS] = json.encodeToString(rungs)
        }
    }

    suspend fun addManualModel(entry: ManualModelEntry) {
        val current = manualModelsFlow.first().toMutableList()
        current.removeAll { it.slug.equals(entry.slug, ignoreCase = true) && it.provider.equals(entry.provider, ignoreCase = true) }
        current.add(0, entry)
        context.hermesDataStore.edit { prefs ->
            prefs[Keys.MANUAL_MODELS] = json.encodeToString(current)
        }
    }

    suspend fun removeManualModel(slug: String, provider: String) {
        val current = manualModelsFlow.first().filterNot {
            it.slug.equals(slug, ignoreCase = true) && it.provider.equals(provider, ignoreCase = true)
        }
        context.hermesDataStore.edit { prefs ->
            prefs[Keys.MANUAL_MODELS] = json.encodeToString(current)
        }
    }

    suspend fun cacheBotWallForHomeWidgets(bots: List<BotProfileCard>) {
        context.hermesDataStore.edit { prefs ->
            prefs[Keys.GLANCE_WALL_SUMMARY] = json.encodeToString(bots.take(8))
        }
    }

    suspend fun readCachedBotWallForHomeWidgets(): List<BotProfileCard> {
        val raw = context.hermesDataStore.data.first()[Keys.GLANCE_WALL_SUMMARY]
        if (raw.isNullOrBlank()) return fallbackRosterCards()
        return runCatching { json.decodeFromString<List<BotProfileCard>>(raw) }
            .getOrElse { fallbackRosterCards() }
    }

    companion object {
        fun defaultSeedManualModels(): List<ManualModelEntry> = listOf(
            ManualModelEntry("omniroute/auto-combo", "omniroute", "OmniRoute Smart Combo"),
            ManualModelEntry("llama-3.3-70b-versatile", "groq", "Groq Llama 3.3 70B"),
            ManualModelEntry("qwen-3-235b-a22b", "cerebras", "Cerebras Qwen 3 235B")
        )

        /**
         * Mirrors `/home/opc/.hermes/bot_relay/roster.json` so the Live Bot Wall
         * renders all 8 real specialist profiles immediately even before first sync completes.
         */
        fun fallbackRosterCards(): List<BotProfileCard> = listOf(
            BotProfileCard("default", "hermes", "Hermes", "Agente principal multipropósito y coordinador personal"),
            BotProfileCard("orchestrator", "orchestrator", "Orchestrator", "Director de equipos: divide tareas y coordina bots"),
            BotProfileCard("programador", "programador", "Programador", "Especialista en código, DevOps, Cloudflare Workers y ESPHome"),
            BotProfileCard("disenador", "disenador", "Diseñador", "Arquitecto UI/UX, sistemas visuales y prototipos"),
            BotProfileCard("domo", "domo", "Domo", "Especialista Home Assistant: luces, sensores y automatizaciones"),
            BotProfileCard("sabio", "sabio", "Sabio", "Investigación externa profunda y síntesis analítica"),
            BotProfileCard("redactor", "redactor", "Redactor", "Redacción ejecutiva, copy y documentación"),
            BotProfileCard("rapidito", "rapidito", "Rapidito", "Respuestas rápidas y charla casual directo al grano")
        )
    }
}
