package fukuro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

@Serializable
data class BookRecommendation(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val description: String? = null,
    val coverUrl: String? = null,
    val detailUrl: String,
    val isbn: String? = null,
    val publishedYear: Int? = null,
    val provider: String,
    val reason: String,
    val score: Double = 0.0,
)

@Serializable
private data class RecommendationCache(
    val fetchedAt: Long = 0,
    val books: List<BookRecommendation> = emptyList(),
)

/**
 * Discovers books outside the user's library. Open Library is always available;
 * Google Books joins in when the user has supplied a project API key in Settings.
 */
class RecommendationService(
    context: Context,
    private val http: OkHttpClient,
    private val store: Store,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cacheFile = File(context.filesDir, "recommendations.json")

    suspend fun cached(): List<BookRecommendation> = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString<RecommendationCache>(cacheFile.readText()).books
        }.getOrDefault(emptyList())
    }

    suspend fun recommendations(
        library: List<LibraryItem>,
        favorites: Set<String>,
        progress: Map<String, MediaProgress>,
        force: Boolean = false,
    ): List<BookRecommendation> = withContext(Dispatchers.IO) {
        val old = runCatching {
            json.decodeFromString<RecommendationCache>(cacheFile.readText())
        }.getOrNull()
        if (!force && old != null && System.currentTimeMillis() - old.fetchedAt < CACHE_MS) {
            return@withContext old.books
        }

        val profile = PreferenceProfile.build(library, favorites, progress)
        if (profile.authors.isEmpty() && profile.topics.isEmpty()) return@withContext old?.books.orEmpty()

        val candidates = mutableListOf<Candidate>()
        profile.authors.keys.take(2).forEach { candidates += openLibrary("author", it) }
        profile.topics.keys.take(2).forEach { candidates += openLibrary("subject", it) }

        val googleKey = store.googleBooksKey().trim()
        if (googleKey.isNotEmpty()) {
            profile.authors.keys.take(2).forEach { candidates += googleBooks("inauthor", it, googleKey) }
            profile.topics.keys.take(2).forEach { candidates += googleBooks("subject", it, googleKey) }
        }

        val owned = library.map { bookKey(it.media.metadata.title.orEmpty(), authorsOf(it).firstOrNull()) }.toSet()
        val ownedTitles = library.map { normalized(it.media.metadata.title.orEmpty()) }.filter(String::isNotEmpty).toSet()
        val ranked = candidates
            .filter { normalized(it.title) !in ownedTitles && bookKey(it.title, it.authors.firstOrNull()) !in owned }
            .groupBy { bookKey(it.title, it.authors.firstOrNull()) }
            .mapNotNull { (_, sameBook) -> merge(sameBook, profile) }
            .sortedByDescending { it.score }
            .take(24)

        if (ranked.isNotEmpty()) {
            runCatching {
                cacheFile.writeText(json.encodeToString(RecommendationCache(System.currentTimeMillis(), ranked)))
            }
            ranked
        } else old?.books.orEmpty()
    }

    private fun openLibrary(field: String, value: String): List<Candidate> {
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter(field, value)
            .addQueryParameter("fields", "key,title,author_name,cover_i,subject,first_publish_year,isbn,ratings_average,ratings_count")
            .addQueryParameter("limit", "12")
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Fukuro Android/${BuildConfig.VERSION_NAME}")
            .get().build()
        return runCatching {
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                json.decodeFromString<OlSearch>(response.body?.string().orEmpty()).docs.mapNotNull { doc ->
                    if (doc.title.isBlank() || doc.key.isBlank()) null else Candidate(
                        id = doc.key,
                        title = doc.title,
                        authors = doc.authors,
                        subjects = doc.subjects.take(20),
                        coverUrl = doc.coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
                        detailUrl = "https://openlibrary.org${doc.key}",
                        isbn = doc.isbn.firstOrNull(),
                        publishedYear = doc.firstPublishYear,
                        provider = "Open Library",
                        rating = doc.rating,
                        ratingsCount = doc.ratingsCount,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun googleBooks(field: String, value: String, apiKey: String): List<Candidate> {
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$field:$value")
            .addQueryParameter("printType", "books")
            .addQueryParameter("maxResults", "12")
            .addQueryParameter("orderBy", "relevance")
            .addQueryParameter("key", apiKey)
            .build()
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                json.decodeFromString<GoogleSearch>(response.body?.string().orEmpty()).items.mapNotNull { item ->
                    val info = item.info
                    if (info.title.isBlank() || item.id.isBlank()) null else Candidate(
                        id = item.id,
                        title = info.title,
                        authors = info.authors,
                        subjects = info.categories,
                        description = info.description,
                        coverUrl = info.images?.thumbnail?.replace("http://", "https://"),
                        detailUrl = info.infoLink ?: "https://books.google.com/books?id=${item.id}",
                        isbn = info.identifiers.firstOrNull { it.type == "ISBN_13" }?.identifier
                            ?: info.identifiers.firstOrNull()?.identifier,
                        publishedYear = info.publishedDate?.take(4)?.toIntOrNull(),
                        provider = "Google Books",
                        rating = info.rating,
                        ratingsCount = info.ratingsCount,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun merge(candidates: List<Candidate>, profile: PreferenceProfile): BookRecommendation? {
        val best = candidates.maxByOrNull { candidateScore(it, profile) } ?: return null
        val google = candidates.firstOrNull { it.provider == "Google Books" }
        val openLibrary = candidates.firstOrNull { it.provider == "Open Library" }
        val authors = candidates.firstOrNull { it.authors.isNotEmpty() }?.authors.orEmpty()
        val subjects = candidates.flatMap { it.subjects }.distinctBy(::normalized).take(20)
        val authorMatch = authors.firstNotNullOfOrNull { author ->
            profile.authors.keys.firstOrNull { normalized(it) == normalized(author) }
        }
        val topicMatch = subjects.firstNotNullOfOrNull { subject ->
            profile.topics.keys.firstOrNull { topic -> topicMatches(topic, subject) }
        }
        val reason = when {
            authorMatch != null -> "Because you listen to $authorMatch"
            topicMatch != null -> "Matches your $topicMatch books"
            else -> "Similar to books in your library"
        }
        val providers = candidates.map { it.provider }.distinct().joinToString(" + ")
        return BookRecommendation(
            id = best.id,
            title = best.title,
            authors = authors,
            subjects = subjects,
            description = google?.description ?: best.description,
            coverUrl = google?.coverUrl ?: openLibrary?.coverUrl ?: best.coverUrl,
            detailUrl = google?.detailUrl ?: best.detailUrl,
            isbn = google?.isbn ?: openLibrary?.isbn ?: best.isbn,
            publishedYear = google?.publishedYear ?: openLibrary?.publishedYear ?: best.publishedYear,
            provider = providers,
            reason = reason,
            score = candidates.maxOf { candidateScore(it, profile) },
        )
    }

    private fun candidateScore(candidate: Candidate, profile: PreferenceProfile): Double {
        val author = candidate.authors.maxOfOrNull { found ->
            profile.authors.entries.maxOfOrNull { (wanted, weight) ->
                if (normalized(found) == normalized(wanted)) weight else 0.0
            } ?: 0.0
        } ?: 0.0
        val topics = candidate.subjects.sumOf { subject ->
            profile.topics.entries.maxOfOrNull { (wanted, weight) ->
                if (topicMatches(wanted, subject)) weight else 0.0
            } ?: 0.0
        }.coerceAtMost(16.0)
        val rating = (candidate.rating ?: 0.0) * 0.35
        val popularity = ln(1.0 + (candidate.ratingsCount ?: 0)) * 0.15
        return author * 2.5 + topics + rating + popularity
    }

    private data class Candidate(
        val id: String,
        val title: String,
        val authors: List<String>,
        val subjects: List<String>,
        val description: String? = null,
        val coverUrl: String? = null,
        val detailUrl: String,
        val isbn: String? = null,
        val publishedYear: Int? = null,
        val provider: String,
        val rating: Double? = null,
        val ratingsCount: Int? = null,
    )

    private data class PreferenceProfile(
        val authors: Map<String, Double>,
        val topics: Map<String, Double>,
    ) {
        companion object {
            fun build(
                library: List<LibraryItem>,
                favorites: Set<String>,
                progress: Map<String, MediaProgress>,
            ): PreferenceProfile {
                val authors = mutableMapOf<String, Double>()
                val topics = mutableMapOf<String, Double>()
                library.forEach { book ->
                    val listeningWeight = when {
                        book.id in favorites -> 5.0
                        progress[book.id]?.isFinished == true -> 4.0
                        (progress[book.id]?.progress ?: 0.0) > 0.05 -> 2.5
                        else -> 1.0
                    }
                    authorsOf(book).forEach { authors[it] = (authors[it] ?: 0.0) + listeningWeight }
                    (book.tags + book.media.metadata.genres).filter(String::isNotBlank).forEach {
                        topics[it] = (topics[it] ?: 0.0) + listeningWeight
                    }
                }
                return PreferenceProfile(
                    authors.entries.sortedByDescending { it.value }.associate { it.toPair() },
                    topics.entries.sortedByDescending { it.value }.associate { it.toPair() },
                )
            }
        }
    }

    @Serializable private data class OlSearch(val docs: List<OlDoc> = emptyList())
    @Serializable private data class OlDoc(
        val key: String = "",
        val title: String = "",
        @SerialName("author_name") val authors: List<String> = emptyList(),
        @SerialName("cover_i") val coverId: Long? = null,
        @SerialName("subject") val subjects: List<String> = emptyList(),
        @SerialName("first_publish_year") val firstPublishYear: Int? = null,
        val isbn: List<String> = emptyList(),
        @SerialName("ratings_average") val rating: Double? = null,
        @SerialName("ratings_count") val ratingsCount: Int? = null,
    )

    @Serializable private data class GoogleSearch(val items: List<GoogleItem> = emptyList())
    @Serializable private data class GoogleItem(
        val id: String = "",
        @SerialName("volumeInfo") val info: GoogleInfo = GoogleInfo(),
    )
    @Serializable private data class GoogleInfo(
        val title: String = "",
        val authors: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val description: String? = null,
        @SerialName("imageLinks") val images: GoogleImages? = null,
        val infoLink: String? = null,
        @SerialName("industryIdentifiers") val identifiers: List<GoogleIdentifier> = emptyList(),
        val publishedDate: String? = null,
        @SerialName("averageRating") val rating: Double? = null,
        @SerialName("ratingsCount") val ratingsCount: Int? = null,
    )
    @Serializable private data class GoogleImages(val thumbnail: String? = null)
    @Serializable private data class GoogleIdentifier(val type: String = "", val identifier: String = "")

    companion object {
        private const val CACHE_MS = 24 * 60 * 60 * 1000L

        private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        private fun bookKey(title: String, author: String?) =
            normalized(title).replace(Regex("^(the|a|an) "), "") + "|" + normalized(author.orEmpty())

        private fun topicMatches(left: String, right: String): Boolean {
            val a = normalized(left)
            val b = normalized(right)
            return a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a)
        }
    }
}
