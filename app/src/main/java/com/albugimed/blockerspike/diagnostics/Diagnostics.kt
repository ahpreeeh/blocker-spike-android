package com.albugimed.blockerspike.diagnostics

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.albugimed.blockerspike.admin.OwnerIdentity
import com.albugimed.blockerspike.service.BlockerAccessibilityService

/** Autorisations et accès à vérifier sur l'appareil (protocole §6). */
data class DiagnosticsState(
    val deviceAdminActive: Boolean = false,
    val deviceOwner: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
)

fun readDiagnostics(context: Context): DiagnosticsState {
    val powerManager = context.getSystemService(PowerManager::class.java)
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val admin = OwnerIdentity.adminComponent(context)
    return DiagnosticsState(
        deviceAdminActive = dpm.isAdminActive(admin),
        deviceOwner = dpm.isDeviceOwnerApp(context.packageName),
        exactAlarmsAllowed = alarmManager.canScheduleExactAlarms(),
        accessibilityEnabled = isAccessibilityServiceEnabled(context),
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        canDrawOverlays = Settings.canDrawOverlays(context),
        ignoringBatteryOptimizations =
            powerManager.isIgnoringBatteryOptimizations(context.packageName),
    )
}

fun openDeviceAdminSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun openExactAlarmSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, BlockerAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabledServices
        .split(':')
        .any { ComponentName.unflattenFromString(it) == expected }
}

fun openAccessibilitySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun openBatterySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
