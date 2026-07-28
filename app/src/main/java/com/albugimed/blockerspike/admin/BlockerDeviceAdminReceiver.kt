package com.albugimed.blockerspike.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.MainActivity
import com.albugimed.blockerspike.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

open class BlockerDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        reconcile(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        reconcile(context)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        Graph.deviceOwnerController.onAdminDisabled()
        super.onDisabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    private fun reconcile(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            Graph.init(context.applicationContext)
            Graph.deviceOwnerController.reconcile(Graph.policyRepository.policy.first())
        }
    }
}
