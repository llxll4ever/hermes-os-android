package art.rodriguezrodriguez.hermesos.data.repository

import art.rodriguezrodriguez.hermesos.core.model.BotLiveState
import art.rodriguezrodriguez.hermesos.core.model.BotProfileCard
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.network.str
import kotlinx.serialization.json.JsonObject

/**
 * Regla "Merge, don't clobber": combina el roster remoto (`/api/profiles`) con la
 * telemetria en vivo del plugin `hermes-mobile-wall` sin perder estados locales.
 *
 * Vive fuera de [HermesOsRepository] para que el refresco en segundo plano
 * (WorkManager) use exactamente la misma logica que la app en primer plano.
 */
object BotWallMerge {

    fun merge(
        current: List<BotProfileCard>,
        remoteProfiles: List<BotProfileCard>,
        wallBots: JsonObject?
    ): List<BotProfileCard> {
        val byProfile = current.associateBy { it.profile }.toMutableMap()

        for (rp in remoteProfiles) {
            val prev = byProfile[rp.profile]
            byProfile[rp.profile] = if (prev != null) {
                prev.copy(
                    displayName = rp.displayName.ifBlank { prev.displayName },
                    roleSummary = rp.roleSummary.ifBlank { prev.roleSummary },
                    activeModel = rp.activeModel.ifBlank { prev.activeModel },
                    activeProvider = rp.activeProvider.ifBlank { prev.activeProvider }
                )
            } else {
                rp
            }
        }

        wallBots?.forEach { (prof, el) ->
            val o = el as? JsonObject ?: return@forEach
            val prev = byProfile[prof] ?: BotProfileCard(
                profile = prof,
                handle = if (prof == "default") "hermes" else prof,
                displayName = prof.replaceFirstChar { it.uppercase() },
                roleSummary = HermesRestClient.defaultRoleForHandle(prof)
            )
            val mappedState = runCatching { BotLiveState.valueOf(o.str("state")) }
                .getOrDefault(prev.liveState)
            byProfile[prof] = prev.copy(
                liveState = mappedState,
                currentActivity = o.str("activity").ifBlank { prev.currentActivity },
                activeToolName = o.str("tool_name").takeIf { it.isNotBlank() },
                activeRoomId = o.str("room_id").takeIf { it.isNotBlank() }
            )
        }

        return byProfile.values.sortedBy { it.profile != "default" && it.profile != "orchestrator" }
    }
}