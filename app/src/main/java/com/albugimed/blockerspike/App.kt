package com.albugimed.blockerspike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.albugimed.blockerspike.admin.DeviceOwnerController
import com.albugimed.blockerspike.inference.InferenceClient
import com.albugimed.blockerspike.inference.UnlockRequestCoordinator
import com.albugimed.blockerspike.policy.BlockPolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Android instantiates this Application in the isolated inference
        // process too. Keep all Device Owner and DataStore work in the main
        // process so a model crash cannot interfere with coercion.
        if (getProcessName().endsWith(":inference")) return
        Graph.init(this)
        Graph.startPolicyReconciliation()
        val channel = NotificationChannel(
            CHANNEL_BLOCK,
            getString(R.string.channel_block_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_BLOCK = "block_events"
    }
}

/**
 * Localisateur minimal pour le spike : le service d'accessibilité et les activités
 * doivent partager la même instance de repository (DataStore n'accepte qu'une
 * instance par fichier).
 */
object Graph {
    lateinit var policyRepository: BlockPolicyRepository
        private set
    lateinit var deviceOwnerController: DeviceOwnerController
        private set
    lateinit var unlockRequestCoordinator: UnlockRequestCoordinator
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reconciliationStarted = false

    @Synchronized
    fun init(context: Context) {
        if (::policyRepository.isInitialized) return
        policyRepository = BlockPolicyRepository(context.applicationContext)
        deviceOwnerController = DeviceOwnerController(context.applicationContext)
        unlockRequestCoordinator = UnlockRequestCoordinator(
            repository = policyRepository,
            inference = InferenceClient(context.applicationContext),
        )
    }

    @Synchronized
    fun startPolicyReconciliation() {
        if (reconciliationStarted) return
        reconciliationStarted = true
        applicationScope.launch {
            policyRepository.policy.collectLatest { policy ->
                deviceOwnerController.reconcile(policy)
            }
        }
    }
}
