package com.albugimed.blockerspike.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.albugimed.blockerspike.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PolicyReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ALLOWED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Graph.init(context.applicationContext)
                Graph.deviceOwnerController.reconcile(Graph.policyRepository.policy.first())
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECONCILE = "com.albugimed.blockerspike.action.RECONCILE"
        private val ALLOWED_ACTIONS = setOf(
            ACTION_RECONCILE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
        )
    }
}
