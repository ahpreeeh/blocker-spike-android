package com.albugimed.blockerspike.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.albugimed.blockerspike.App
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.R
import com.albugimed.blockerspike.gate.BlockGateActivity
import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.PolicyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Écoute TYPE_WINDOW_STATE_CHANGED, lit uniquement le nom du package source,
 * interroge la politique déterministe et déclenche l'interruption (protocole §3).
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var policy = PolicyState()

    private var launcherPackages: Set<String> = emptySet()
    private var lastInterceptedPackage: String? = null
    private var lastInterceptedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Graph.init(this)
        launcherPackages = resolveLauncherPackages()
        scope.launch {
            Graph.policyRepository.policy.collect { policy = it }
        }
        InterceptionLog.add(InterceptionLog.TAG_SERVICE, "Service d'accessibilité connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in launcherPackages || pkg in SYSTEM_PACKAGES) return

        InterceptionLog.add(InterceptionLog.TAG_EVENT, "Premier plan : $pkg")

        val now = System.currentTimeMillis()
        if (!policy.shouldBlock(pkg, now)) return

        // Anti-rebond : le retour accueil peut générer une rafale d'événements
        // pour le même package pendant la transition.
        if (pkg == lastInterceptedPackage && now - lastInterceptedAt < DEBOUNCE_MILLIS) return
        lastInterceptedPackage = pkg
        lastInterceptedAt = now

        val homeOk = performGlobalAction(GLOBAL_ACTION_HOME)
        InterceptionLog.add(
            InterceptionLog.TAG_INTERCEPT,
            "$pkg intercepté — retour accueil ${if (homeOk) "OK" else "ÉCHEC"}",
        )

        if (policy.variantB && Settings.canDrawOverlays(this)) {
            // T2-B : SYSTEM_ALERT_WINDOW accordé => exemption de lancement en
            // arrière-plan. Repli sur la notification si Android 16 refuse.
            runCatching {
                startActivity(
                    BlockGateActivity.intent(this, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                InterceptionLog.add(InterceptionLog.TAG_INTERCEPT, "T2-B : écran de blocage lancé")
            }.onFailure { e ->
                InterceptionLog.add(
                    InterceptionLog.TAG_INTERCEPT,
                    "T2-B refusé (${e.javaClass.simpleName}) — repli notification",
                )
                postBlockNotification(pkg)
            }
        } else {
            postBlockNotification(pkg)
        }
    }

    override fun onInterrupt() {
        InterceptionLog.add(InterceptionLog.TAG_SERVICE, "onInterrupt()")
    }

    override fun onDestroy() {
        InterceptionLog.add(InterceptionLog.TAG_SERVICE, "Service détruit")
        scope.cancel()
        super.onDestroy()
    }

    private fun postBlockNotification(pkg: String) {
        val pending = PendingIntent.getActivity(
            this,
            pkg.hashCode(),
            BlockGateActivity.intent(this, pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, App.CHANNEL_BLOCK)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_blocked_title))
            .setContentText(getString(R.string.notif_blocked_text, pkg))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(pkg.hashCode(), notification)
    }

    private fun resolveLauncherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 1_500L
        val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
    }
}
