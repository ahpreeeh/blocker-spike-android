package com.albugimed.blockerspike.admin

import android.content.ComponentName
import android.content.Context
import com.albugimed.blockerspike.BuildConfig

object OwnerIdentity {
    const val PERMANENT_PACKAGE = "com.albugimed.app"
    const val PERMANENT_RECEIVER =
        "com.albugimed.app.admin.AlbugimedDeviceAdminReceiver"
    private const val MIGRATION_RECEIVER =
        "com.albugimed.blockerspike.admin.BlockerDeviceAdminReceiver"

    fun adminComponent(context: Context): ComponentName = ComponentName(
        context.packageName,
        if (BuildConfig.PERMANENT_IDENTITY) PERMANENT_RECEIVER else MIGRATION_RECEIVER,
    )

    fun permanentComponent(): ComponentName =
        ComponentName(PERMANENT_PACKAGE, PERMANENT_RECEIVER)
}
