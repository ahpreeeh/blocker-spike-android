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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Les captures — **une base à part, pas un magasin de plus.**
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
 *
 * **Une capture confirmée n'est plus effacée** (version 2). Elle l'était, et
 * c'était le pire defaut du partage : partager depuis une autre application
 * marchait, la capture partait, puis disparaissait de l'appareil. Rien ne
 * distinguait un partage reussi d'un partage qui n'avait jamais eu lieu. Le
 * nom de la table reste `pending_captures` — le renommer coûterait une
 * migration par recopie pour un mot invisible a l'usage.
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
    /**
     * Instant où le serveur a confirmé l'avoir reçue, `null` tant qu'elle
     * attend. Ce que le téléphone sait s'arrête ici : **il ne sait pas si
     * l'atelier l'a triée.** L'affichage ne doit donc jamais dire « traitée ».
     */
    val sentAtMillis: Long? = null,
)

/** Où en est une capture, du point de vue du téléphone et de lui seul. */
enum class CaptureState { PENDING, SENT, REFUSED }

val PendingCaptureRow.state: CaptureState
    get() = when {
        deadReason != null -> CaptureState.REFUSED
        sentAtMillis != null -> CaptureState.SENT
        else -> CaptureState.PENDING
    }

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PendingCaptureRow): Long

    @Query(
        "select * from pending_captures where deadReason is null and sentAtMillis is null " +
            "order by capturedAtMillis asc limit :limit",
    )
    suspend fun pending(limit: Int): List<PendingCaptureRow>

    @Query("select count(*) from pending_captures where deadReason is null and sentAtMillis is null")
    fun pendingCount(): Flow<Int>

    /**
     * Tout, du plus récent au plus ancien.
     *
     * La borne n'est pas une purge : rien n'est effacé, on cesse seulement
     * d'afficher au-delà. Une note ne disparaît que sur geste explicite.
     */
    @Query("select * from pending_captures order by capturedAtMillis desc limit :limit")
    fun all(limit: Int): Flow<List<PendingCaptureRow>>

    /** Le serveur a confirmé. La ligne reste, elle change d'état. */
    @Query("update pending_captures set sentAtMillis = :sentAtMillis where captureId in (:captureIds)")
    suspend fun markSent(captureIds: List<String>, sentAtMillis: Long)

    @Query("update pending_captures set deadReason = :reason where captureId = :captureId")
    suspend fun bury(captureId: String, reason: String)

    /** Oubli d'**une** note, sur geste explicite. Jamais en bloc. */
    @Query("delete from pending_captures where captureId = :captureId")
    suspend fun forget(captureId: String)
}

@Database(entities = [PendingCaptureRow::class], version = 2, exportSchema = false)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captures(): CaptureDao

    companion object {
        /**
         * Ajoute la colonne, ne touche a rien d'autre. Les captures deja
         * envoyees avant cette version ont ete effacees a l'epoque : elles ne
         * reviendront pas, et aucune valeur inventee ne les remplacera.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("alter table pending_captures add column sentAtMillis integer")
            }
        }

        @Volatile
        private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    CaptureDatabase::class.java,
                    "captures.db",
                )
                .addMigrations(MIGRATION_1_2)
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
