package com.albugimed.blockerspike.admin

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.albugimed.blockerspike.BuildConfig
import com.albugimed.blockerspike.policy.PolicyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

data class DeviceOwnerRuntimeState(
    val adminActive: Boolean = false,
    val deviceOwner: Boolean = false,
    val backupServiceEnabled: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val managedTargetCount: Int = 0,
    val suspendedPackages: Set<String> = emptySet(),
    val lastReconcileAtMillis: Long? = null,
    val lastError: String? = null,
)

/** Applies the policy through Android's official Device Owner suspension API. */
class DeviceOwnerController(context: Context) {
    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val admin = OwnerIdentity.adminComponent(appContext)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val reconcileMutex = Mutex()

    private val _runtime = MutableStateFlow(readRuntime())
    val runtime: StateFlow<DeviceOwnerRuntimeState> = _runtime.asStateFlow()

    suspend fun reconcile(policy: PolicyState, nowMillis: Long = System.currentTimeMillis()) {
        reconcileMutex.withLock {
            val adminActive = dpm.isAdminActive(admin)
            val deviceOwner = dpm.isDeviceOwnerApp(appContext.packageName)
            val exactAlarmsAllowed = alarmManager.canScheduleExactAlarms()
            val previouslyManaged = preferences.getStringSet(KEY_MANAGED_PACKAGES, emptySet())
                ?.toSet()
                .orEmpty()
            val currentTargets = policy.blockedPackages
                .filterTo(linkedSetOf()) { it.isNotBlank() && it != appContext.packageName }

            if (!deviceOwner) {
                cancelAllowanceAlarm()
                _runtime.value = DeviceOwnerRuntimeState(
                    adminActive = adminActive,
                    deviceOwner = false,
                    exactAlarmsAllowed = exactAlarmsAllowed,
                    managedTargetCount = previouslyManaged.size,
                    lastReconcileAtMillis = nowMillis,
                )
                return@withLock
            }

            var backupServiceError: String? = null
            if (BuildConfig.PERMANENT_IDENTITY) {
                try {
                    if (!dpm.isBackupServiceEnabled(admin)) {
                        dpm.setBackupServiceEnabled(admin, true)
                    }
                } catch (error: Exception) {
                    backupServiceError =
                        "Google Backup enable failed: ${error.javaClass.simpleName}"
                }
            }
            val backupServiceEnabled = try {
                dpm.isBackupServiceEnabled(admin)
            } catch (_: Exception) {
                false
            }

            val desired = desiredSuspensions(
                previouslyManaged = previouslyManaged,
                currentTargets = currentTargets,
                policy = policy,
                nowMillis = nowMillis,
            )
            val actualSuspended = linkedSetOf<String>()
            val nextManaged = currentTargets.toMutableSet()
            val errors = mutableListOf<String>()
            backupServiceError?.let(errors::add)

            desired.forEach { (packageName, shouldSuspend) ->
                try {
                    val unchanged = dpm.setPackagesSuspended(
                        admin,
                        arrayOf(packageName),
                        shouldSuspend,
                    )
                    if (packageName in unchanged) {
                        errors += "$packageName refused by Android"
                        if (packageName in previouslyManaged) nextManaged += packageName
                    }
                    if (dpm.isPackageSuspended(admin, packageName)) {
                        actualSuspended += packageName
                    }
                } catch (error: Exception) {
                    errors += "$packageName: ${error.javaClass.simpleName}"
                    if (packageName in previouslyManaged) nextManaged += packageName
                }
            }

            preferences.edit()
                .putStringSet(KEY_MANAGED_PACKAGES, nextManaged)
                .apply()
            scheduleNextAllowanceExpiry(policy, nowMillis)
            _runtime.value = DeviceOwnerRuntimeState(
                adminActive = adminActive,
                deviceOwner = true,
                backupServiceEnabled = backupServiceEnabled,
                exactAlarmsAllowed = exactAlarmsAllowed,
                managedTargetCount = nextManaged.size,
                suspendedPackages = actualSuspended,
                lastReconcileAtMillis = nowMillis,
                lastError = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
        }
    }

    fun onAdminDisabled() {
        cancelAllowanceAlarm()
        preferences.edit().remove(KEY_MANAGED_PACKAGES).apply()
        _runtime.value = readRuntime()
    }

    /**
     * Migration unique du spike vers l'identite permanente. Android effectue
     * le changement atomiquement et migre les politiques systeme.
     */
    suspend fun transferToPermanentOwner(policy: PolicyState): Boolean =
        reconcileMutex.withLock {
            if (BuildConfig.PERMANENT_IDENTITY ||
                !dpm.isDeviceOwnerApp(appContext.packageName)
            ) {
                return@withLock false
            }
            val expectedCertificate = BuildConfig.PERMANENT_OWNER_CERT_SHA256
                .normalizeFingerprint()
            if (expectedCertificate.isBlank() || expectedCertificate == "UNCONFIGURED") {
                _runtime.value = _runtime.value.copy(
                    lastError = "Permanent signing certificate is not configured",
                )
                return@withLock false
            }

            val target = OwnerIdentity.permanentComponent()
            if (!dpm.isAdminActive(target)) {
                _runtime.value = _runtime.value.copy(
                    lastError = "Albugimed V0 is not an active device admin",
                )
                return@withLock false
            }
            if (!targetCertificateMatches(expectedCertificate)) {
                _runtime.value = _runtime.value.copy(
                    lastError = "Albugimed V0 signing certificate mismatch",
                )
                return@withLock false
            }

            return@withLock try {
                dpm.transferOwnership(
                    admin,
                    target,
                    OwnershipTransferPayload.encode(policy),
                )
                val transferred = dpm.isDeviceOwnerApp(OwnerIdentity.PERMANENT_PACKAGE)
                _runtime.value = readRuntime().copy(
                    lastError = if (transferred) null else "Ownership transfer did not complete",
                )
                transferred
            } catch (error: Exception) {
                _runtime.value = _runtime.value.copy(
                    lastError = "Ownership transfer failed: ${error.javaClass.simpleName}",
                )
                false
            }
        }

    /**
     * Prototype-only recovery path for a manually installed, non-testOnly DPC.
     * Every known target is unsuspended before the owner role is relinquished.
     */
    @Suppress("DEPRECATION")
    suspend fun relinquishDeviceOwner(): Boolean = reconcileMutex.withLock {
        if (BuildConfig.PERMANENT_IDENTITY) return@withLock false
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return@withLock false

        val managedPackages = preferences
            .getStringSet(KEY_MANAGED_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()
        val stillSuspended = mutableListOf<String>()
        managedPackages.forEach { packageName ->
            try {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                if (dpm.isPackageSuspended(admin, packageName)) {
                    stillSuspended += packageName
                }
            } catch (_: Exception) {
                // An absent package has no suspension left to clear.
            }
        }
        if (stillSuspended.isNotEmpty()) {
            _runtime.value = _runtime.value.copy(
                lastError = "Unable to unsuspend: ${stillSuspended.joinToString()}",
            )
            return@withLock false
        }

        return@withLock try {
            cancelAllowanceAlarm()
            preferences.edit().remove(KEY_MANAGED_PACKAGES).commit()
            dpm.clearDeviceOwnerApp(appContext.packageName)
            _runtime.value = readRuntime()
            true
        } catch (error: Exception) {
            _runtime.value = _runtime.value.copy(
                lastError = "Owner removal failed: ${error.javaClass.simpleName}",
            )
            false
        }
    }

    private fun readRuntime(): DeviceOwnerRuntimeState {
        val deviceOwner = dpm.isDeviceOwnerApp(appContext.packageName)
        val backupServiceEnabled = if (deviceOwner) {
            try {
                dpm.isBackupServiceEnabled(admin)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return DeviceOwnerRuntimeState(
            adminActive = dpm.isAdminActive(admin),
            deviceOwner = deviceOwner,
            backupServiceEnabled = backupServiceEnabled,
            exactAlarmsAllowed = alarmManager.canScheduleExactAlarms(),
            managedTargetCount = preferences
                .getStringSet(KEY_MANAGED_PACKAGES, emptySet())
                .orEmpty()
                .size,
        )
    }

    private fun targetCertificateMatches(expectedSha256: String): Boolean {
        val packageInfo = try {
            appContext.packageManager.getPackageInfo(
                OwnerIdentity.PERMANENT_PACKAGE,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        return signers.any { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
                } == expectedSha256
        }
    }

    private fun scheduleNextAllowanceExpiry(policy: PolicyState, nowMillis: Long) {
        cancelAllowanceAlarm()
        if (policy.failsafeOverride) return
        val nextExpiry = policy.blockedPackages
            .mapNotNull(policy.allowedUntil::get)
            .filter { it > nowMillis }
            .minOrNull()
            ?: return
        val operation = reconcilePendingIntent()
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextExpiry, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextExpiry, operation)
        }
    }

    private fun cancelAllowanceAlarm() {
        alarmManager.cancel(reconcilePendingIntent())
    }

    private fun reconcilePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, PolicyReconcileReceiver::class.java)
            .setAction(PolicyReconcileReceiver.ACTION_RECONCILE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val PREFS_NAME = "device_owner_enforcement"
        private const val KEY_MANAGED_PACKAGES = "managed_packages"
    }
}

private fun String.normalizeFingerprint(): String =
    replace(":", "").replace(" ", "").uppercase()

/** Pure state transition used by the controller and unit tests. */
internal fun desiredSuspensions(
    previouslyManaged: Set<String>,
    currentTargets: Set<String>,
    policy: PolicyState,
    nowMillis: Long,
): Map<String, Boolean> = (previouslyManaged + currentTargets)
    .associateWith { packageName ->
        packageName in currentTargets && policy.shouldBlock(packageName, nowMillis)
    }
