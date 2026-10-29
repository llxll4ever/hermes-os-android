package art.rodriguezrodriguez.hermesos.feature.glance

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import art.rodriguezrodriguez.hermesos.core.model.LinkStatus
import art.rodriguezrodriguez.hermesos.core.network.EncryptedCookieStore
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.storage.WorkspacePreferencesStore
import art.rodriguezrodriguez.hermesos.data.repository.BotWallMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * Refresco en segundo plano del widget de pantalla de inicio "Bot Wall".
 *
 * Contexto: [HermesWallGlanceWidget] solo pinta desde la cache local de
 * [WorkspacePreferencesStore]; antes de este worker la cache unicamente se
 * actualizaba cuando la app estaba en primer plano. Aqui se replica el mismo
 * camino de datos (escalera de conexion + `/api/profiles` + telemetria del
 * plugin `hermes-mobile-wall`) sin abrir ninguna Activity y sin WebSocket.
 *
 * Reglas:
 * - Sin nodos configurados: no hay nada que hacer -> success.
 * - Sin sesion valida (AUTH_REQUIRED): reintentar no sirve, el usuario debe
 *   volver a autenticarse -> success.
 * - Fallo de red o servidor: retry con backoff exponencial, con techo de
 *   intentos para no drenar bateria indefinidamente.
 * - La cache solo se escribe cuando hay datos nuevos; el widget nunca se queda
 *   sin contenido por un fallo de red.
 */
class HermesBotWallSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefsStore = WorkspacePreferencesStore(applicationContext)
        val rungs = prefsStore.connectionRungsFlow.first()
        if (rungs.none { it.enabled && it.baseUrl.isNotBlank() }) {
            return@withContext Result.success()
        }

        val restClient = HermesRestClient(EncryptedCookieStore(applicationContext))
        val snapshot = runCatching { restClient.resolveConnectionLadder(rungs) }.getOrNull()
            ?: return@withContext retryOrGiveUp()

        when (snapshot.status) {
            LinkStatus.CONNECTED -> Unit
            LinkStatus.AUTH_REQUIRED -> return@withContext Result.success()
            else -> return@withContext retryOrGiveUp()
        }

        val remoteProfiles = runCatching { restClient.fetchProfiles() }.getOrDefault(emptyList())
        val wallTelemetry = runCatching {
            restClient.getJson("/api/plugins/hermes-mobile-wall/wall") as? JsonObject
        }.getOrNull()
        if (remoteProfiles.isEmpty() && wallTelemetry == null) {
            return@withContext retryOrGiveUp()
        }

        val wallBots = wallTelemetry?.get("bots") as? JsonObject
        val cached = prefsStore.readCachedBotWallForHomeWidgets()
        val merged = BotWallMerge.merge(cached, remoteProfiles, wallBots)
        prefsStore.cacheBotWallForHomeWidgets(merged)

        runCatching { HermesWallGlanceWidget().updateAll(applicationContext) }
        Result.success()
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    companion object {
        const val UNIQUE_WORK_NAME = "hermes_bot_wall_sync"

        private const val INTERVAL_MINUTES = 30L
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MINUTES = 10L

        /**
         * Idempotente: `UPDATE` reemplaza la programacion existente sin duplicar
         * trabajo. WorkManager persiste el trabajo periodico entre reinicios del
         * dispositivo, por lo que no hace falta un receiver propio de BOOT_COMPLETED.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HermesBotWallSyncWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}