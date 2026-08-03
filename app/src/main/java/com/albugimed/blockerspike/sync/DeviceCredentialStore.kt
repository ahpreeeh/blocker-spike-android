package com.albugimed.blockerspike.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Où vit le jeton d'appareil.
 *
 * Frontière étroite : le moteur d'envoi ne connaît que ces quatre méthodes,
 * ce qui rend ses tests possibles sans appareil.
 */
interface DeviceCredentialStore {
    fun read(): DeviceCredentials?
    fun write(credentials: DeviceCredentials): Boolean
    fun rememberDeviceId(deviceId: String): Boolean
    fun clear()

    /**
     * Y a-t-il un enrôlement, sans déchiffrer quoi que ce soit ?
     *
     * Sert à connaître l'état **dès la première image affichée**. Sans cela,
     * un appareil déjà enrôlé voit le formulaire d'enrôlement le temps que la
     * première requête parte — ce qui se lit comme « on m'a déconnecté », et
     * pousse à ressaisir un jeton qui n'avait aucun problème.
     */
    fun hasCredentials(): Boolean
}

/**
 * Implémentation chiffrée, adossée au Keystore Android.
 *
 * AES-256-GCM avec une clé qui ne quitte jamais le Keystore, et un IV neuf à
 * chaque écriture. C'est exactement ce que faisait `EncryptedSharedPreferences`,
 * sans la dépendance : le §10 du contrat impose de revérifier la surface
 * réseau à chaque bibliothèque ajoutée, et celle-ci aurait tiré Tink pour
 * rendre un service que la plateforme rend déjà — d'autant qu'elle est
 * dépréciée sur les niveaux d'API que cette application vise.
 *
 * Si le déchiffrement échoue — restauration, réinstallation, clé perdue —
 * l'enregistrement est effacé et l'utilisateur ré-enrôle. Un secret
 * illisible n'est pas récupérable ; prétendre le contraire produirait des
 * `401` inexplicables.
 */
class KeystoreCredentialStore(
    context: Context,
    private val logger: SyncLogger = SystemSyncLogger,
) : DeviceCredentialStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    override fun read(): DeviceCredentials? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val sealed = preferences.getString(KEY_TOKEN, null) ?: return null

        val token = decrypt(sealed) ?: run {
            logger.add(
                SyncLogger.TAG_ERROR,
                "Jeton d'appareil illisible : enrôlement à refaire.",
            )
            clear()
            return null
        }

        return DeviceCredentials(
            baseUrl = baseUrl,
            token = token,
            deviceId = preferences.getString(KEY_DEVICE_ID, null),
        )
    }

    override fun write(credentials: DeviceCredentials): Boolean {
        val sealed = encrypt(credentials.token) ?: return false
        return preferences.edit()
            .putString(KEY_BASE_URL, credentials.baseUrl)
            .putString(KEY_TOKEN, sealed)
            .apply {
                if (credentials.deviceId == null) remove(KEY_DEVICE_ID)
                else putString(KEY_DEVICE_ID, credentials.deviceId)
            }
            .commit()
    }

    override fun rememberDeviceId(deviceId: String): Boolean =
        preferences.edit().putString(KEY_DEVICE_ID, deviceId).commit()

    override fun hasCredentials(): Boolean =
        preferences.contains(KEY_BASE_URL) &&
            preferences.contains(KEY_TOKEN) &&
            preferences.contains(KEY_DEVICE_ID)

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private fun encrypt(clear: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(clear.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }.getOrElse { error ->
        logger.add(
            SyncLogger.TAG_ERROR,
            "Chiffrement du jeton impossible : ${error.javaClass.simpleName}",
        )
        null
    }

    private fun decrypt(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORE_NAME = "study_credentials"
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "albugimed.study.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_ID = "device_id"
    }
}
