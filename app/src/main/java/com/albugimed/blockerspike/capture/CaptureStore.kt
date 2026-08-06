package com.albugimed.blockerspike.capture

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * La file d'attente des captures — **une base à part, pas un magasin de plus.**
 *
 * Trois raisons, et la troisième est celle qui a décidé :
 *
 *   1. `StudyOutboxRepository` ne transporte que des `StudyEvent` vers
 *      `/events` ; les captures vont ailleurs, avec un autre format ;
 *   2. DataStore réécrit tout son fichier à chaque modification, ce qui
 *      convient à quelques dizaines de traces courtes, pas à du texte libre ;
 *   3. **une capture volumineuse ne doit jamais pouvoir bloquer une trace
 *      d'étude.** C'est la règle qui avait déjà imposé trois magasins
 *      séparés en tranche 1, et elle vaut ici mot pour mot.
 *
 * `capture_id` est la clé primaire : un partage rejoué par erreur ne crée
 * pas deux lignes, ici comme sur le serveur.
 */
@Entity(tableName = "pending_captures")
data class PendingCaptureRow(
    @PrimaryKey val captureId: String,
    val kind: String,
    val capturedAtMillis: Long,
    val text: String,
    val subject: String?,
    val sourcePackage: String?,
    /**
     * Motif du refus, `null` tant que la capture attend. Une capture refusée
     * ne repart plus — le même envoi donnerait le même refus — et ne
     * disparaît pas : c'est la file morte, visible, du contrat §7.
     */
    val deadReason: String? = null,
)

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PendingCaptureRow): Long

    @Query("select * from pending_captures where deadReason is null order by capturedAtMillis asc limit :limit")
    suspend fun pending(limit: Int): List<PendingCaptureRow>

    @Query("select count(*) from pending_captures where deadReason is null")
    fun pendingCount(): Flow<Int>

    @Query("select * from pending_captures where deadReason is not null order by capturedAtMillis desc")
    fun dead(): Flow<List<PendingCaptureRow>>

    /** Retire ce que le serveur a accepté — ou déclaré déjà connu. */
    @Query("delete from pending_captures where captureId in (:captureIds)")
    suspend fun forget(captureIds: List<String>)

    @Query("update pending_captures set deadReason = :reason where captureId = :captureId")
    suspend fun bury(captureId: String, reason: String)

    /** Oubli d'**une** capture morte, sur geste explicite. Jamais en bloc. */
    @Query("delete from pending_captures where captureId = :captureId and deadReason is not null")
    suspend fun forgetDead(captureId: String)
}

@Database(entities = [PendingCaptureRow::class], version = 1, exportSchema = false)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captures(): CaptureDao

    companion object {
        @Volatile
        private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    CaptureDatabase::class.java,
                    "captures.db",
                )
                // Pas de `fallbackToDestructiveMigration` : une migration
                // manquante doit faire échouer l'ouverture, jamais effacer une
                // capture que le serveur n'a pas encore vue.
                .build()
                .also { instance = it }
        }
    }
}

fun PendingCaptureRow.toCapture(): Capture = Capture(
    captureId = captureId,
    kind = kind,
    capturedAtMillis = capturedAtMillis,
    text = text,
    subject = subject,
    sourcePackage = sourcePackage,
)

fun Capture.toRow(): PendingCaptureRow = PendingCaptureRow(
    captureId = captureId,
    kind = kind,
    capturedAtMillis = capturedAtMillis,
    text = text,
    subject = subject,
    sourcePackage = sourcePackage,
)
