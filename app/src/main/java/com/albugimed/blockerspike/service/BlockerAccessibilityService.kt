package com.albugimed.blockerspike.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationManagerCompat
import com.albugimed.blockerspike.App
import com.albugimed.blockerspike.Graph
import com.albugimed.blockerspike.R
import com.albugimed.blockerspike.gate.BlockGateActivity
import com.albugimed.blockerspike.gate.GateLaunchTracker
import com.albugimed.blockerspike.log.InterceptionLog
import com.albugimed.blockerspike.policy.PolicyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        val receivedAt = SystemClock.uptimeMillis()
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
        val interruptedAt = SystemClock.uptimeMillis()
        val estimatedLatency = (interruptedAt - event.eventTime).coerceAtLeast(0L)
        InterceptionLog.add(
            InterceptionLog.TAG_INTERCEPT,
            "$pkg intercepté — retour accueil ${if (homeOk) "OK" else "ÉCHEC"} — " +
                "latence estimée ${estimatedLatency} ms " +
                "(traitement ${interruptedAt - receivedAt} ms)",
            packageName = pkg,
            latencyMillis = estimatedLatency,
            homeActionSucceeded = homeOk,
        )

        if (policy.variantB && Settings.canDrawOverlays(this)) {
            // startActivity() peut réussir sans que l'activité soit réellement
            // affichée. Un token confirmé dans onCreate permet le repli différé.
            val token = GateLaunchTracker.register()
            runCatching {
                startActivity(
                    BlockGateActivity.intent(this, pkg, token)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                scope.launch {
                    delay(GATE_CONFIRMATION_MILLIS)
                    if (GateLaunchTracker.consumeWasShown(token)) {
                        InterceptionLog.add(
                            InterceptionLog.TAG_GATE,
                            "T2-B confirmé : écran de blocage affiché",
                            packageName = pkg,
                        )
                    } else {
                        InterceptionLog.add(
                            InterceptionLog.TAG_GATE,
                            "T2-B non confirmé — repli notification",
                            packageName = pkg,
                        )
                        postBlockNotification(pkg)
                    }
                }
            }.onFailure { e ->
                GateLaunchTracker.discard(token)
                InterceptionLog.add(
                    InterceptionLog.TAG_GATE,
                    "T2-B refusé (${e.javaClass.simpleName}) — repli notification",
                    packageName = pkg,
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
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            InterceptionLog.add(
                InterceptionLog.TAG_ERROR,
                "Notification impossible : autorisation désactivée",
                packageName = pkg,
            )
            return
        }
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
        runCatching {
            getSystemService(NotificationManager::class.java).notify(pkg.hashCode(), notification)
        }.onFailure { error ->
            InterceptionLog.add(
                InterceptionLog.TAG_ERROR,
                "Échec notification : ${error.javaClass.simpleName}",
                packageName = pkg,
            )
        }
    }

    private fun resolveLauncherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 1_500L
        const val GATE_CONFIRMATION_MILLIS = 750L
        val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
    }
}
