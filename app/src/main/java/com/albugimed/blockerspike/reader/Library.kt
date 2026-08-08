package com.albugimed.blockerspike.reader

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * La bibliotheque : des dossiers, des livres, et rien d'autre.
 *
 * Elle vit **sur l'appareil et nulle part ailleurs**, exactement comme
 * [ReadingPosition] et pour la meme raison : ce que l'on range dit ce que l'on
 * etudie, et le §8 du contrat exclut toute statistique d'usage. Aucun encodeur
 * de contrat ici, aucune place dans `SyncTransport`.
 *
 * Le decoupage est volontairement pauvre. Un dossier n'a qu'un nom et un
 * parent ; un livre n'a qu'un titre, un dossier et des fichiers. Tout le reste
 * — la page, le nombre de pages, l'URI du document — vit deja dans le magasin
 * des positions, et le dupliquer ici garantirait qu'un jour les deux ne
 * diraient plus la meme chose.
 */

/**
 * Un dossier. `parentId == null` signifie « a la racine ».
 *
 * Aucune profondeur maximale : un plafond ne coute pas moins cher a ecrire
 * qu'un arbre libre, il se contente d'interdire au bout de quelques mois le
 * rangement qu'on aurait voulu. Ce qui garde l'ecran leger n'est pas le
 * plafond, c'est de ne montrer qu'un niveau a la fois.
 */
data class LibraryFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

/**
 * Un fichier d'un livre.
 *
 * `resourceId` est la **meme cle** que dans [ReadingPositionRepository] : c'est
 * elle qui relie le rangement a la page memorisee. Un fichier ajoute ici prend
 * une cle `lib_...` ; un document rattache depuis une etape de « A faire »
 * garde la sienne, celle du referentiel.
 *
 * `label` peut etre vide : on affiche alors le nom du fichier tel que le
 * selecteur systeme l'a donne. Le renommer sert aux tomes — « Tome 1 » se lit
 * mieux que « college_cardio_vol1_final(2).pdf ».
 */
data class LibraryBookFile(
    val resourceId: String,
    val label: String = "",
)

/**
 * Un livre : un titre, un dossier, et un ou plusieurs fichiers.
 *
 * Plusieurs fichiers pour un seul livre est le cas ordinaire et non
 * l'exception : un college scanne arrive en tomes, un poly en chapitres. Les
 * traiter comme des livres distincts obligerait a se souvenir soi-meme que les
 * trois vont ensemble, ce qui est precisement le travail qu'on demande a la
 * bibliotheque.
 */
data class LibraryBook(
    val id: String,
    val title: String,
    val folderId: String? = null,
    val files: List<LibraryBookFile> = emptyList(),
)

/**
 * L'etat complet de la bibliotheque, tel qu'il sort du magasin.
 *
 * Les questions posees par l'ecran — « que contient ce dossier ? », « ou suis-je
 * descendu ? » — sont resolues ici, sur des listes immuables, et non dans la
 * composition. C'est ce qui les rend testables sans Android.
 */
data class Library(
    val folders: List<LibraryFolder> = emptyList(),
    val books: List<LibraryBook> = emptyList(),
) {
    fun folder(id: String?): LibraryFolder? =
        if (id == null) null else folders.firstOrNull { it.id == id }

    /** Les dossiers d'un niveau, par ordre alphabetique. */
    fun childFolders(parentId: String?): List<LibraryFolder> =
        folders.filter { it.parentId == parentId }
            .sortedBy { it.name.lowercase() }

    /** Les livres poses a ce niveau, par ordre alphabetique. */
    fun booksIn(folderId: String?): List<LibraryBook> =
        books.filter { it.folderId == folderId }
            .sortedBy { it.title.lowercase() }

    /**
     * Le chemin depuis la racine jusqu'a ce dossier, celui du fil d'Ariane.
     *
     * Une chaine de parents abimee — un dossier dont le parent a disparu, ou
     * un cycle laisse par une version anterieure — s'arrete au lieu de boucler
     * indefiniment : mieux vaut un fil d'Ariane incomplet qu'un ecran fige.
     */
    fun path(folderId: String?): List<LibraryFolder> {
        val chemin = ArrayDeque<LibraryFolder>()
        val vus = mutableSetOf<String>()
        var courant = folder(folderId)
        while (courant != null && vus.add(courant.id)) {
            chemin.addFirst(courant)
            courant = folder(courant.parentId)
        }
        return chemin.toList()
    }

    /** Le dossier et tout ce qui pend dessous. */
    fun subtree(folderId: String): Set<String> {
        val trouves = mutableSetOf(folderId)
        var restants = folders.filter { it.id != folderId }
        var ajoute = true
        while (ajoute) {
            val (dedans, dehors) = restants.partition { it.parentId?.let(trouves::contains) == true }
            ajoute = dedans.isNotEmpty()
            dedans.forEach { trouves += it.id }
            restants = dehors
        }
        return trouves
    }

    /** Le livre qui porte ce fichier, s'il est deja range quelque part. */
    fun bookOwning(resourceId: String): LibraryBook? =
        books.firstOrNull { book -> book.files.any { it.resourceId == resourceId } }

    /** Tous les fichiers connus de la bibliotheque, tous livres confondus. */
    fun knownResourceIds(): Set<String> =
        books.flatMapTo(mutableSetOf()) { book -> book.files.map { it.resourceId } }

    /**
     * Les destinations possibles d'un deplacement.
     *
     * Un dossier ne peut pas descendre dans lui-meme ni dans l'un de ses
     * enfants : la bibliotheque se detacherait de la racine et deviendrait
     * inatteignable, sans qu'aucun message ne l'explique.
     */
    fun destinationsFor(movedFolderId: String?): List<LibraryFolder> {
        val interdits = movedFolderId?.let(::subtree).orEmpty()
        return folders.filterNot { it.id in interdits }
            .sortedBy { path(it.id).joinToString("/") { dossier -> dossier.name.lowercase() } }
    }
}

/**
 * Ce qui est lu sur ce qui est a lire.
 *
 * Deux nombres bruts plutot qu'un pourcentage deja calcule : un livre en trois
 * tomes est la somme de ses tomes, et une somme de pourcentages ne veut rien
 * dire des que les tomes n'ont pas la meme longueur.
 */
data class ReadingProgress(
    val pagesRead: Int = 0,
    val pageTotal: Int = 0,
) {
    /**
     * Le pourcentage, ou `null` quand aucun total n'est connu — un document
     * jamais ouvert n'a pas encore dit combien il comptait de pages, et
     * afficher « 0 % » a cet endroit serait une affirmation, pas un fait.
     */
    val percent: Int?
        get() = if (pageTotal <= 0) {
            null
        } else {
            ((pagesRead * 100f) / pageTotal).roundToInt().coerceIn(0, 100)
        }

    operator fun plus(other: ReadingProgress) = ReadingProgress(
        pagesRead = pagesRead + other.pagesRead,
        pageTotal = pageTotal + other.pageTotal,
    )

    companion object {
        val NONE = ReadingProgress()
    }
}

/** Ce qu'un fichier apporte au compte : sa page courante sur son total. */
fun fileProgress(position: ReadingPosition?): ReadingProgress {
    if (position == null || position.pageCount <= 0) return ReadingProgress.NONE
    return ReadingProgress(
        pagesRead = position.page.coerceIn(1, position.pageCount),
        pageTotal = position.pageCount,
    )
}

/** Un livre vaut la somme de ses fichiers, jamais la moyenne de leurs taux. */
fun bookProgress(
    book: LibraryBook,
    positions: Map<String, ReadingPosition>,
): ReadingProgress = book.files.fold(ReadingProgress.NONE) { total, file ->
    total + fileProgress(positions[file.resourceId])
}

/** Un dossier vaut la somme de tout ce qu'il contient, sous-dossiers compris. */
fun folderProgress(
    library: Library,
    folderId: String?,
    positions: Map<String, ReadingPosition>,
): ReadingProgress {
    val dedans = if (folderId == null) null else library.subtree(folderId)
    return library.books
        .filter { livre -> dedans == null || livre.folderId?.let(dedans::contains) == true }
        .fold(ReadingProgress.NONE) { total, livre -> total + bookProgress(livre, positions) }
}

/** Le nombre de livres d'un dossier, sous-dossiers compris. */
fun bookCount(library: Library, folderId: String): Int {
    val dedans = library.subtree(folderId)
    return library.books.count { livre -> livre.folderId?.let(dedans::contains) == true }
}

/**
 * Le fichier sur lequel on lit : le dernier touche.
 *
 * C'est lui qui porte la pastille de page sur la couverture. Un livre en trois
 * tomes n'a qu'une page courante qui ait un sens — celle du tome ouvert.
 */
fun currentFileOf(
    book: LibraryBook,
    positions: Map<String, ReadingPosition>,
): ReadingPosition? = book.files
    .mapNotNull { positions[it.resourceId] }
    .maxByOrNull { it.updatedAtMillis }

/**
 * Le titre propose au moment de l'ajout, tire du nom du fichier.
 *
 * Le nom d'un PDF telecharge est rarement un titre, mais il en contient
 * toujours un : l'extension, les tirets et les tirets bas partent, le reste
 * est propose tel quel **et reste modifiable**. Deviner mieux que ca
 * demanderait de lire les metadonnees du document, qui sont fausses une fois
 * sur deux — un scan porte le nom du logiciel qui l'a produit.
 */
fun bookTitleFromFileName(fileName: String): String {
    val sansExtension = fileName.substringBeforeLast('.', fileName)
        .takeIf { it.isNotBlank() }
        ?: fileName
    val propre = sansExtension
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    return propre.ifBlank { "Document" }
}

/**
 * Serialisation locale, separee de `StudyEventJson` et de `CaptureJson`.
 *
 * Ces deux-la decrivent un contrat reseau, celle-ci decrit un fichier prive.
 * Les melanger rendrait facile d'envoyer par erreur ce qui ne doit pas partir.
 */
internal object LibraryJson {

    fun encodeFolder(folder: LibraryFolder): String = JSONObject()
        .put("id", folder.id)
        .put("name", folder.name)
        .apply { folder.parentId?.let { put("parent_id", it) } }
        .toString()

    fun decodeFolder(serialized: String): LibraryFolder? {
        val json = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return LibraryFolder(
            id = id,
            name = json.optString("name").takeIf { it.isNotBlank() } ?: "Dossier",
            parentId = json.optString("parent_id").takeIf { it.isNotBlank() },
        )
    }

    fun encodeBook(book: LibraryBook): String = JSONObject()
        .put("id", book.id)
        .put("title", book.title)
        .apply { book.folderId?.let { put("folder_id", it) } }
        .put(
            "files",
            JSONArray().apply {
                book.files.forEach { file ->
                    put(
                        JSONObject()
                            .put("resource_id", file.resourceId)
                            .put("label", file.label),
                    )
                }
            },
        )
        .toString()

    fun decodeBook(serialized: String): LibraryBook? {
        val json = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val fichiers = json.optJSONArray("files") ?: JSONArray()
        return LibraryBook(
            id = id,
            title = json.optString("title").takeIf { it.isNotBlank() } ?: "Sans titre",
            folderId = json.optString("folder_id").takeIf { it.isNotBlank() },
            files = (0 until fichiers.length()).mapNotNull { index ->
                val entree = fichiers.optJSONObject(index) ?: return@mapNotNull null
                val resourceId = entree.optString("resource_id")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                LibraryBookFile(resourceId = resourceId, label = entree.optString("label"))
            },
        )
    }
}
