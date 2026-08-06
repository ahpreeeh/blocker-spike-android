package com.albugimed.blockerspike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.albugimed.blockerspike.admin.DeviceOwnerController
import com.albugimed.blockerspike.capture.CaptureDatabase
import com.albugimed.blockerspike.capture.CaptureOutboxRepository
import com.albugimed.blockerspike.guide.BlockGuideRepository
import com.albugimed.blockerspike.inference.InferenceClient
import com.albugimed.blockerspike.inference.PolicyEnforcer
import com.albugimed.blockerspike.inference.UnlockRequestCoordinator
import com.albugimed.blockerspike.policy.BlockPolicyRepository
import com.albugimed.blockerspike.reader.ReadingPositionRepository
import com.albugimed.blockerspike.sync.AgendaCacheRepository
import com.albugimed.blockerspike.sync.HttpSyncTransport
import com.albugimed.blockerspike.sync.KeystoreCredentialStore
import com.albugimed.blockerspike.sync.QueueCacheRepository
import com.albugimed.blockerspike.sync.DeviceCredentialStore
import com.albugimed.blockerspike.sync.StudyOutboxRepository
import com.albugimed.blockerspike.sync.SyncEngine
import com.albugimed.blockerspike.sync.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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

    /**
     * Côté études. Câblé ici pour la même raison que le reste : DataStore
     * n'accepte qu'une instance par fichier, et `Graph.init` n'est appelé
     * que dans le processus principal — jamais dans `:inference`.
     *
     * Ces trois objets ne connaissent rien de la coercition, et la
     * coercition ne les connaît pas. Le graphe est le seul endroit où les
     * deux moitiés se croisent, et elles ne s'y touchent pas.
     */
    lateinit var studyOutbox: StudyOutboxRepository
        private set
    lateinit var queueCache: QueueCacheRepository
        private set
    lateinit var agendaCache: AgendaCacheRepository
        private set
    lateinit var syncEngine: SyncEngine
        private set
    /** Magasin prive distinct de `block_policy`; aucun consommateur metier en V1.3. */
    lateinit var blockGuideRepository: BlockGuideRepository
        private set

    /**
     * Côté captures — V2.1. Base Room à part, et non un quatrième magasin
     * DataStore : une capture volumineuse ne doit jamais pouvoir retarder une
     * trace d'étude. Les deux files ne se croisent qu'ici, et ne se touchent
     * pas davantage qu'ailleurs.
     */
    lateinit var captureOutbox: CaptureOutboxRepository
        private set
    lateinit var captureCredentials: DeviceCredentialStore
        private set
    lateinit var captureTransport: SyncTransport
        private set

    /**
     * Positions de lecture — V2.2. Cinquieme magasin, et le seul dont le
     * contenu ne part JAMAIS sur le reseau : le lecteur connait la page, le
     * serveur ne l'apprend qu'au moment ou elle est declaree.
     */
    lateinit var readingPositions: ReadingPositionRepository
        private set

    /**
     * Portee de l'application. Publique depuis V2.2 : poser un signet ne doit
     * pas etre annule parce que l'ecran de lecture se ferme.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reconciliationStarted = false

    @Synchronized
    fun init(context: Context) {
        if (::policyRepository.isInitialized) return
        policyRepository = BlockPolicyRepository(context.applicationContext)
        deviceOwnerController = DeviceOwnerController(context.applicationContext)
        unlockRequestCoordinator = UnlockRequestCoordinator(
            repository = policyRepository,
            inference = InferenceClient(context.applicationContext),
            exactExpiryAvailable = deviceOwnerController::canEnforceExactExpiry,
            policyEnforcer = PolicyEnforcer { packageName, expectedSuspended ->
                deviceOwnerController.reconcileAndConfirmSuspension(
                    policy = policyRepository.policy.first(),
                    packageName = packageName,
                    expectedSuspended = expectedSuspended,
                )
            },
        )

        studyOutbox = StudyOutboxRepository(context.applicationContext)
        captureOutbox = CaptureOutboxRepository(
            CaptureDatabase.get(context.applicationContext).captures(),
        )
        captureCredentials = KeystoreCredentialStore(context.applicationContext)
        captureTransport = HttpSyncTransport()
        readingPositions = ReadingPositionRepository(context.applicationContext)
        queueCache = QueueCacheRepository(context.applicationContext)
        agendaCache = AgendaCacheRepository(context.applicationContext)
        blockGuideRepository = BlockGuideRepository(context.applicationContext)
        syncEngine = SyncEngine(
            outbox = studyOutbox,
            queueCache = queueCache,
            credentialStore = captureCredentials,
            transport = captureTransport,
            agendaCache = agendaCache,
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
