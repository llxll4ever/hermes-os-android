package art.rodriguezrodriguez.hermesos

import android.app.Application
import art.rodriguezrodriguez.hermesos.core.network.EncryptedCookieStore
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.core.rpc.HermesRpcClient
import art.rodriguezrodriguez.hermesos.core.storage.WorkspacePreferencesStore
import art.rodriguezrodriguez.hermesos.data.repository.HermesOsRepository
import art.rodriguezrodriguez.hermesos.feature.glance.HermesBotWallSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HermesOsApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var cookieStore: EncryptedCookieStore
        private set
    lateinit var restClient: HermesRestClient
        private set
    lateinit var rpcClient: HermesRpcClient
        private set
    lateinit var prefsStore: WorkspacePreferencesStore
        private set
    lateinit var repository: HermesOsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        cookieStore = EncryptedCookieStore(this)
        restClient = HermesRestClient(cookieStore)
        rpcClient = HermesRpcClient(restClient, appScope)
        prefsStore = WorkspacePreferencesStore(this)
        repository = HermesOsRepository(
            restClient = restClient,
            rpcClient = rpcClient,
            prefsStore = prefsStore,
            scope = appScope
        )

        // Refresco periodico del widget "Bot Wall" (idempotente, sobrevive reinicios).
        runCatching { HermesBotWallSyncWorker.schedule(this) }
    }

    companion object {
        @Volatile
        var instance: HermesOsApp? = null
            private set
    }
}
