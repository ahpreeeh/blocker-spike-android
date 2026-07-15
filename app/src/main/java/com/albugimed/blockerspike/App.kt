package com.albugimed.blockerspike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.albugimed.blockerspike.policy.BlockPolicyRepository

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
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

    fun init(context: Context) {
        if (::policyRepository.isInitialized) return
        policyRepository = BlockPolicyRepository(context.applicationContext)
    }
}
