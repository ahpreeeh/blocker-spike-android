package com.albugimed.blockerspike.inference

import android.content.Context
import java.io.File

/** Locates metadata only; it never opens or inspects the model contents. */
object ModelLocator {
    fun findModel(context: Context): Result<File> = runCatching {
        val candidates = modelDirectories(context)
            .flatMap { directory ->
                directory.mkdirs()
                directory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension.equals("litertlm", ignoreCase = true) }
            }
            .distinctBy { it.canonicalPath }

        check(candidates.isNotEmpty()) {
            "Aucun modele .litertlm dans ${modelDirectories(context).joinToString { it.path }}"
        }
        candidates
            .sortedWith(
                compareByDescending<File> { "e4b" in it.name.lowercase() }
                    .thenBy { it.name.lowercase() }
            )
            .first()
    }

    fun modelDirectories(context: Context): List<File> = buildList {
        add(File(context.noBackupFilesDir, "models"))
        context.getExternalFilesDir(null)?.let { add(File(it, "models")) }
    }
}
