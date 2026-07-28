package com.albugimed.app.admin

import android.content.Context
import android.os.PersistableBundle
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.admin.BlockerDeviceAdminReceiver
import com.albugimed.blockerspike.admin.OwnershipTransferPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Composant Device Owner permanent : ce nom qualifie ne doit plus changer. */
class AlbugimedDeviceAdminReceiver : BlockerDeviceAdminReceiver() {
    override fun onTransferOwnershipComplete(
        context: Context,
        bundle: PersistableBundle?,
    ) {
        super.onTransferOwnershipComplete(context, bundle)
        val transferredPolicy = OwnershipTransferPayload.decode(bundle) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Graph.init(context.applicationContext)
                Graph.policyRepository.replacePolicy(transferredPolicy)
                Graph.deviceOwnerController.reconcile(transferredPolicy)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
