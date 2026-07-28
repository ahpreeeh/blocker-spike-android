package com.albugimed.blockerspike.inference

import android.content.Context
import java.io.File

/**
 * Locates only the explicitly verified T3 model. The transfer procedure pushes
 * a `.part`, checks SHA-256 on-device, renames it atomically, then writes the
 * small verification marker read here.
 */
object ModelLocator {
    const val MODEL_FILE_NAME = "gemma-3n-e4b-it.litertlm"
    const val EXPECTED_SIZE_BYTES = 3_659_530_240L
    const val EXPECTED_SHA256 =
        "0B2A8980CE155FD97673D8E820B4D29D9C7D99B8FA6806F425D969B145BD52E0"

    fun findModel(context: Context): Result<File> = runCatching {
        val model = modelDirectories(context)
            .asSequence()
            .onEach { it.mkdirs() }
            .map { File(it, MODEL_FILE_NAME) }
            .firstOrNull(::isVerifiedModel)

        checkNotNull(model) {
            "Modele absent ou non verifie. Attendu: ${adbModelFile(context).path}"
        }
    }

    /** First directory is the practical adb target for a signed release APK. */
    fun modelDirectories(context: Context): List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(File(it, "models")) }
        add(File(context.noBackupFilesDir, "models"))
    }

    fun adbModelFile(context: Context): File {
        val external = context.getExternalFilesDir(null)
            ?: error("Stockage externe propre a l'application indisponible")
        return File(File(external, "models"), MODEL_FILE_NAME)
    }

    fun verificationMarker(modelFile: File): File =
        File(modelFile.parentFile, "${modelFile.name}.sha256")

    private fun isVerifiedModel(file: File): Boolean {
        if (!file.isFile || file.length() != EXPECTED_SIZE_BYTES) return false
        val marker = verificationMarker(file)
        return marker.isFile && runCatching {
            marker.readText().trim().uppercase() == EXPECTED_SHA256
        }.getOrDefault(false)
    }
}
