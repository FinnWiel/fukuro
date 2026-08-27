package fukuro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ln

private fun feedbackNormalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ").trim()

fun recommendationFeedbackKey(book: BookRecommendation): String = book.stableKey.ifBlank {
    feedbackNormalized(book.title).replace(Regex("^(the|a|an) "), "") + "|" +
        feedbackNormalized(book.authors.firstOrNull().orEmpty())
}

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
    val asin: String? = null,
    val publishedYear: Int? = null,
    val provider: String,
    val reason: String,
    val score: Double = 0.0,
    val openLibraryKey: String? = null,
    val googleBooksId: String? = null,
    val stableKey: String = "",
    val primaryTopic: String? = null,
)

@Serializable
data class RecommendationFeedback(
    val dismissed: Set<String> = emptySet(),
    val reducedAuthors: Set<String> = emptySet(),
    val reducedTopics: Set<String> = emptySet(),
    val boostedAuthors: Set<String> = emptySet(),
    val boostedTopics: Set<String> = emptySet(),
)

@Serializable
private data class RecommendationCache(
    val fetchedAt: Long = 0,
    val books: List<BookRecommendation> = emptyList(),
    val algorithmVersion: Int = 0,
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
    private val recommendationHttp = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val cacheFile = File(context.filesDir, "recommendations.json")
    private val detailsFile = File(context.filesDir, "recommendation_details.json")

    suspend fun cached(): List<BookRecommendation> = withContext(Dispatchers.IO) {
        val feedback = store.recommendationFeedback()
        runCatching {
            json.decodeFromString<RecommendationCache>(cacheFile.readText()).books
        }.getOrDefault(emptyList()).filterNot { recommendationKey(it) in feedback.dismissed }
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
        val owned = OwnedIndex.from(library)
        val feedback = store.recommendationFeedback()
        val oldBooks = old?.books.orEmpty().filterNot {
            owned.contains(it) || recommendationKey(it) in feedback.dismissed
        }
        if (!force && old != null && old.algorithmVersion == ALGORITHM_VERSION &&
            System.currentTimeMillis() - old.fetchedAt < CACHE_MS
        ) {
            return@withContext oldBooks
        }

        val profile = PreferenceProfile.build(library, favorites, progress).adjusted(feedback)
        if (profile.authors.isEmpty() && profile.topics.isEmpty()) return@withContext oldBooks

        val googleKey = store.googleBooksKey().trim()
        val openQueries = buildList {
            profile.authors.keys.take(3).forEach { add("author" to it) }
            profile.topics.keys.take(5).forEach { add("subject" to it) }
            profile.series.keys.take(3).forEach { add("q" to it) }
        }.distinctBy { (field, value) -> "$field:${normalized(value)}" }
        val googleQueries = buildList {
            profile.authors.keys.take(3).forEach { add("inauthor" to it) }
            profile.topics.keys.take(5).forEach { add("subject" to it) }
            profile.series.keys.take(3).forEach { add("q" to it) }
        }.distinctBy { (field, value) -> "$field:${normalized(value)}" }

        // Provider requests are independent. A small shared concurrency limit makes refreshes
        // substantially faster without flooding either public books API.
        val candidates = coroutineScope {
            val permits = Semaphore(4)
            val requests = openQueries.map { (field, value) ->
                async { permits.withPermit { openLibrary(field, value) } }
            } + if (googleKey.isNotEmpty()) {
                googleQueries.map { (field, value) ->
                    async { permits.withPermit { googleBooks(field, value, googleKey) } }
                }
            } else emptyList()
            requests.awaitAll().flatten()
        }

        val ranked = candidates
            .filterNot { owned.contains(it) }
            .groupBy { bookKey(it.title, it.authors.firstOrNull()) }
            .mapNotNull { (_, sameBook) -> merge(sameBook, profile, feedback) }
            .filterNot { recommendationKey(it) in feedback.dismissed }
            .sortedByDescending { it.score }
        val selected = diversify(ranked, profile)

        if (selected.isNotEmpty()) {
            runCatching {
                cacheFile.writeText(
                    json.encodeToString(
                        RecommendationCache(System.currentTimeMillis(), selected, ALGORITHM_VERSION)
                    )
                )
            }
            selected
        } else oldBooks
    }

    /** Adds the longer synopsis and complete subject list when a recommendation is opened. */
    suspend fun details(book: BookRecommendation): BookRecommendation = withContext(Dispatchers.IO) {
        val cache = runCatching {
            json.decodeFromString<DetailsCache>(detailsFile.readText()).books
        }.getOrDefault(emptyMap())
        val cacheKey = "${book.provider}:${book.id}"
        cache[cacheKey]?.let { return@withContext it }

        var enriched = book
        val openLibraryKey = book.openLibraryKey ?: book.id.takeIf { it.startsWith("/works/") }
        openLibraryKey?.let { key ->
            openLibraryWork(key)?.let { work ->
                enriched = enriched.copy(
                    description = enriched.description ?: descriptionText(work.description),
                    subjects = (enriched.subjects + work.subjects).distinctBy(::normalized).take(40),
                )
            }
        }
        if (enriched.description.isNullOrBlank()) {
            val googleKey = store.googleBooksKey().trim()
            val googleBooksId = book.googleBooksId
                ?: book.id.takeIf { "Google Books" in book.provider && !it.startsWith("/") }
            if (googleKey.isNotEmpty()) googleBooksId?.let { id ->
                googleVolume(id, googleKey)?.let { info ->
                    enriched = enriched.copy(
                        description = info.description,
                        subjects = (enriched.subjects + info.categories).distinctBy(::normalized).take(40),
                    )
                }
            }
        }
        if (enriched != book) {
            runCatching {
                detailsFile.writeText(json.encodeToString(DetailsCache(cache + (cacheKey to enriched))))
            }
        }
        enriched
    }

    private fun openLibrary(field: String, value: String): List<Candidate> {
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter(field, value)
            .addQueryParameter("fields", "key,title,author_name,cover_i,subject,first_publish_year,isbn,id_amazon,ratings_average,ratings_count")
            .addQueryParameter("limit", "12")
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Fukuro Android/${BuildConfig.VERSION_NAME}")
            .get().build()
        return runCatching {
            recommendationHttp.newCall(req).execute().use { response ->
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
                        asin = doc.amazonIds.firstOrNull(),
                        publishedYear = doc.firstPublishYear,
                        provider = "Open Library",
                        rating = doc.rating,
                        ratingsCount = doc.ratingsCount,
                        openLibraryKey = doc.key,
                        queryTopic = value.takeIf { field == "subject" },
                        querySeries = value.takeIf { field == "q" },
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun googleBooks(field: String, value: String, apiKey: String): List<Candidate> {
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", if (field == "q") value else "$field:$value")
            .addQueryParameter("printType", "books")
            .addQueryParameter("maxResults", "12")
            .addQueryParameter("orderBy", "relevance")
            .addQueryParameter("key", apiKey)
            .build()
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            recommendationHttp.newCall(req).execute().use { response ->
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
                            ?: info.identifiers.firstOrNull { it.type == "ISBN_10" }?.identifier,
                        asin = info.identifiers.firstOrNull {
                            it.type == "OTHER" && normalizedAsin(it.identifier) != null
                        }?.identifier,
                        publishedYear = info.publishedDate?.take(4)?.toIntOrNull(),
                        provider = "Google Books",
                        rating = info.rating,
                        ratingsCount = info.ratingsCount,
                        googleBooksId = item.id,
                        queryTopic = value.takeIf { field == "subject" },
                        querySeries = value.takeIf { field == "q" },
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun merge(
        candidates: List<Candidate>,
        profile: PreferenceProfile,
        feedback: RecommendationFeedback,
    ): BookRecommendation? {
        val best = candidates.maxByOrNull { candidateScore(it, profile, feedback) } ?: return null
        val google = candidates.firstOrNull { it.provider == "Google Books" }
        val openLibrary = candidates.firstOrNull { it.provider == "Open Library" }
        val authors = candidates.firstOrNull { it.authors.isNotEmpty() }?.authors.orEmpty()
        val subjects = candidates.flatMap { it.subjects }.distinctBy(::normalized).take(20)
        val authorMatch = authors.firstNotNullOfOrNull { author ->
            profile.authors.keys.firstOrNull { normalized(it) == normalized(author) }
        }
        val topicMatch = profile.topics.keys.firstOrNull { topic ->
            subjects.any { subject -> topicMatches(topic, subject) }
        } ?: candidates.mapNotNull { it.queryTopic }.firstOrNull()
        val similarSeed = profile.seeds.maxByOrNull { seed ->
            seedSimilarity(authors, subjects, seed) * seed.weight
        }?.takeIf { seedSimilarity(authors, subjects, it) >= 0.35 }
        val seriesMatch = candidates.mapNotNull { it.querySeries }.firstOrNull()
        val reason = when {
            seriesMatch != null -> "Related to your $seriesMatch books"
            similarSeed != null -> "Similar to ${similarSeed.title}"
            topicMatch != null -> "Matches your $topicMatch books"
            authorMatch != null -> "Because you listen to $authorMatch"
            else -> "A discovery outside your usual picks"
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
            asin = google?.asin ?: openLibrary?.asin ?: best.asin,
            publishedYear = google?.publishedYear ?: openLibrary?.publishedYear ?: best.publishedYear,
            provider = providers,
            reason = reason,
            score = candidates.maxOf { candidateScore(it, profile, feedback) },
            openLibraryKey = openLibrary?.openLibraryKey,
            googleBooksId = google?.googleBooksId,
            stableKey = bookKey(best.title, authors.firstOrNull()),
            primaryTopic = topicMatch,
        )
    }

    private fun openLibraryWork(key: String): OlWork? {
        val req = Request.Builder().url("https://openlibrary.org$key.json")
            .header("User-Agent", "Fukuro Android/${BuildConfig.VERSION_NAME}")
            .get().build()
        return runCatching {
            recommendationHttp.newCall(req).execute().use { response ->
                if (!response.isSuccessful) null
                else json.decodeFromString<OlWork>(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun googleVolume(id: String, apiKey: String): GoogleInfo? {
        val url = "https://www.googleapis.com/books/v1/volumes/$id".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey).build()
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            recommendationHttp.newCall(req).execute().use { response ->
                if (!response.isSuccessful) null
                else json.decodeFromString<GoogleItem>(response.body?.string().orEmpty()).info
            }
        }.getOrNull()
    }

    private fun descriptionText(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> (value["value"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private fun candidateScore(
        candidate: Candidate,
        profile: PreferenceProfile,
        feedback: RecommendationFeedback,
    ): Double {
        val author = candidate.authors.maxOfOrNull { found ->
            profile.authors.entries.maxOfOrNull { (wanted, weight) ->
                if (normalized(found) == normalized(wanted)) weight else 0.0
            } ?: 0.0
        } ?: 0.0
        // Each preference contributes once even when a provider returns several near-
        // duplicate subjects such as Fantasy, Epic Fantasy and Fantasy Fiction.
        val topics = profile.topics.entries
            .filter { (wanted, _) ->
                candidate.subjects.any { topicMatches(wanted, it) } ||
                    candidate.queryTopic?.let { topicMatches(wanted, it) } == true
            }
            .map { it.value }.sortedDescending().take(3).sum().coerceAtMost(18.0)
        val series = candidate.querySeries?.let { query ->
            profile.series.entries.firstOrNull { normalized(it.key) == normalized(query) }?.value
        } ?: 0.0
        val similarity = profile.seeds.maxOfOrNull { seed ->
            seedSimilarity(candidate.authors, candidate.subjects, seed) * seed.weight
        } ?: 0.0
        val rating = (candidate.rating ?: 0.0) * 0.35
        val popularity = ln(1.0 + (candidate.ratingsCount ?: 0)) * 0.15
        val reducedAuthor = candidate.authors.any { found ->
            feedback.reducedAuthors.any { normalized(it) == normalized(found) }
        }
        val reducedTopic = candidate.subjects.any { found ->
            feedback.reducedTopics.any { topicMatches(it, found) }
        }
        val penalty = (if (reducedAuthor) 12.0 else 0.0) + (if (reducedTopic) 7.0 else 0.0)
        return author * 1.6 + topics * 1.15 + similarity * 1.8 + series + rating + popularity - penalty
    }

    /**
     * Netflix-style second pass: relevance supplies the candidates, then proportional
     * topic slots and repeat penalties determine the final shelf.
     */
    private fun diversify(
        ranked: List<BookRecommendation>,
        profile: PreferenceProfile,
        limit: Int = 24,
    ): List<BookRecommendation> {
        if (ranked.isEmpty()) return emptyList()
        val selected = mutableListOf<BookRecommendation>()
        val selectedKeys = mutableSetOf<String>()
        val authorCounts = mutableMapOf<String, Int>()
        val topicCounts = mutableMapOf<String, Int>()

        fun key(book: BookRecommendation) = recommendationKey(book)
        fun author(book: BookRecommendation) = normalized(book.authors.firstOrNull().orEmpty())
        fun topic(book: BookRecommendation) = normalized(book.primaryTopic.orEmpty())
        fun add(book: BookRecommendation) {
            if (!selectedKeys.add(key(book))) return
            selected += book
            author(book).takeIf(String::isNotEmpty)?.let { authorCounts[it] = (authorCounts[it] ?: 0) + 1 }
            topic(book).takeIf(String::isNotEmpty)?.let { topicCounts[it] = (topicCounts[it] ?: 0) + 1 }
        }

        val activeTopics = profile.topics.entries.take(5).filter { (name, _) ->
            ranked.any { topicMatches(name, it.primaryTopic.orEmpty()) }
        }
        if (activeTopics.isNotEmpty()) {
            val topicSlots = minOf(20, limit)
            val minimum = if (topicSlots >= activeTopics.size * 2) 2 else 1
            val targets = activeTopics.associate { normalized(it.key) to minimum }.toMutableMap()
            var remaining = topicSlots - minimum * activeTopics.size
            // D'Hondt-style allocation preserves the preference ratio while the minimum
            // guarantees that a smaller but real interest does not vanish.
            while (remaining-- > 0) {
                val next = activeTopics.maxBy { (name, weight) ->
                    weight / ((targets[normalized(name)] ?: 0) + 1)
                }
                val id = normalized(next.key)
                targets[id] = (targets[id] ?: 0) + 1
            }

            while (selected.size < topicSlots) {
                val topicId = targets.keys
                    .filter { (topicCounts[it] ?: 0) < (targets[it] ?: 0) }
                    .maxByOrNull { id ->
                        ((targets[id] ?: 0) - (topicCounts[id] ?: 0)).toDouble() / (targets[id] ?: 1)
                    } ?: break
                val topicCandidates = ranked.filter {
                    key(it) !in selectedKeys && topicMatches(topicId, it.primaryTopic.orEmpty())
                }
                val candidate = topicCandidates
                    .filter { (authorCounts[author(it)] ?: 0) < 5 }
                    .ifEmpty { topicCandidates }
                    .maxByOrNull { it.score - (authorCounts[author(it)] ?: 0) * 5.0 }
                if (candidate == null) topicCounts[topicId] = targets[topicId] ?: 0 else add(candidate)
            }
        }

        // The final slots are discovery/author candidates. Penalize repetition and very
        // similar cards, while relaxing the author cap when the provider offers no alternative.
        while (selected.size < limit) {
            val remaining = ranked.filter { key(it) !in selectedKeys }
            if (remaining.isEmpty()) break
            val underAuthorCap = remaining.filter { (authorCounts[author(it)] ?: 0) < 5 }
                .ifEmpty { remaining }
            val next = underAuthorCap.maxByOrNull { book ->
                val repeatAuthor = (authorCounts[author(book)] ?: 0) * 5.0
                val repeatTopic = (topicCounts[topic(book)] ?: 0) * 1.25
                val overlap = selected.maxOfOrNull { chosen ->
                    topicOverlap(book.subjects, chosen.subjects)
                } ?: 0.0
                book.score - repeatAuthor - repeatTopic - overlap * 4.0
            } ?: break
            add(next)
        }
        return selected
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
        val asin: String? = null,
        val publishedYear: Int? = null,
        val provider: String,
        val rating: Double? = null,
        val ratingsCount: Int? = null,
        val openLibraryKey: String? = null,
        val googleBooksId: String? = null,
        val queryTopic: String? = null,
        val querySeries: String? = null,
    )

    private data class SeedBook(
        val title: String,
        val authors: Set<String>,
        val topics: Set<String>,
        val weight: Double,
    )

    private data class OwnedBook(
        val title: String,
        val authors: Set<String>,
        val isbn: String?,
        val asin: String?,
    )

    private class OwnedIndex(private val books: List<OwnedBook>) {
        fun contains(candidate: Candidate) =
            contains(candidate.title, candidate.authors, candidate.isbn, candidate.asin)
        fun contains(book: BookRecommendation) =
            contains(book.title, book.authors, book.isbn, book.asin)

        private fun contains(
            rawTitle: String,
            rawAuthors: List<String>,
            rawIsbn: String?,
            rawAsin: String?,
        ): Boolean {
            val title = comparableTitle(rawTitle)
            val authors = rawAuthors.map(::normalized).filter(String::isNotEmpty).toSet()
            val isbn = normalizedIsbn(rawIsbn)
            val asin = normalizedAsin(rawAsin)
            return books.any { owned ->
                if (isbn != null && owned.isbn != null && isbn == owned.isbn) return@any true
                if (asin != null && owned.asin != null && asin == owned.asin) return@any true
                if (title.isEmpty() || owned.title.isEmpty()) return@any false
                if (title == owned.title) return@any true

                // Accommodate punctuation, articles and tiny edition-title differences,
                // but only use fuzzy matching when at least one author also agrees.
                val sameAuthor = authors.isNotEmpty() && owned.authors.any { it in authors }
                sameAuthor && (
                    title.replace(" ", "") == owned.title.replace(" ", "") ||
                        titleSimilarity(title, owned.title) >= 0.82
                    )
            }
        }

        companion object {
            fun from(library: List<LibraryItem>) = OwnedIndex(library.map { book ->
                OwnedBook(
                    title = comparableTitle(book.media.metadata.title.orEmpty()),
                    authors = authorsOf(book).map(::normalized).filter(String::isNotEmpty).toSet(),
                    isbn = normalizedIsbn(book.media.metadata.isbn),
                    asin = normalizedAsin(
                        book.media.metadata.asin
                            ?: book.media.metadata.isbn?.takeIf { normalizedIsbn(it) == null }
                    ),
                )
            })
        }
    }

    private data class PreferenceProfile(
        val authors: Map<String, Double>,
        val topics: Map<String, Double>,
        val series: Map<String, Double>,
        val seeds: List<SeedBook>,
    ) {
        fun adjusted(feedback: RecommendationFeedback): PreferenceProfile = copy(
            authors = authors.mapValues { (author, weight) ->
                when {
                    feedback.reducedAuthors.any { normalized(it) == normalized(author) } -> weight * 0.2
                    feedback.boostedAuthors.any { normalized(it) == normalized(author) } -> weight * 1.4
                    else -> weight
                }
            }.entries.sortedByDescending { it.value }.associate { it.toPair() },
            topics = topics.mapValues { (topic, weight) ->
                when {
                    feedback.reducedTopics.any { topicMatches(it, topic) } -> weight * 0.2
                    feedback.boostedTopics.any { topicMatches(it, topic) } -> weight * 1.4
                    else -> weight
                }
            }.entries.sortedByDescending { it.value }.associate { it.toPair() },
        )

        companion object {
            fun build(
                library: List<LibraryItem>,
                favorites: Set<String>,
                progress: Map<String, MediaProgress>,
            ): PreferenceProfile {
                val authors = mutableMapOf<String, Double>()
                val topics = mutableMapOf<String, Double>()
                val series = mutableMapOf<String, Double>()
                val seeds = mutableListOf<SeedBook>()
                library.forEach { book ->
                    val baseWeight = when {
                        book.id in favorites -> 5.0
                        progress[book.id]?.isFinished == true -> 4.0
                        (progress[book.id]?.progress ?: 0.0) > 0.05 -> 2.5
                        else -> 0.65
                    }
                    val ageDays = progress[book.id]?.lastUpdate?.takeIf { it > 0L }?.let {
                        (System.currentTimeMillis() - it).coerceAtLeast(0L) / 86_400_000.0
                    }
                    val recency = when {
                        ageDays == null -> 1.0
                        ageDays <= 30 -> 1.25
                        ageDays <= 180 -> 1.1
                        else -> 1.0
                    }
                    val weight = baseWeight * recency
                    val bookAuthors = authorsOf(book).filter(String::isNotBlank)
                    val bookTopics = (book.tags + book.media.metadata.genres)
                        .filter(String::isNotBlank).distinctBy(::normalized)
                    val bookSeries = (book.media.metadata.series.map { it.name } +
                        listOfNotNull(book.media.metadata.seriesName))
                        .filter(String::isNotBlank).distinctBy(::normalized)
                    bookAuthors.forEach { addWeight(authors, it, weight) }
                    bookTopics.forEach { addWeight(topics, it, weight) }
                    bookSeries.forEach { addWeight(series, it, weight) }
                    if (weight > 0.65) {
                        seeds += SeedBook(
                            title = book.media.metadata.title.orEmpty(),
                            authors = bookAuthors.map(::normalized).toSet(),
                            topics = bookTopics.map(::normalized).toSet(),
                            weight = weight,
                        )
                    }
                }
                return PreferenceProfile(
                    authors.entries.sortedByDescending { it.value }.associate { it.toPair() },
                    topics.entries.sortedByDescending { it.value }.associate { it.toPair() },
                    series.entries.sortedByDescending { it.value }.associate { it.toPair() },
                    seeds.sortedByDescending { it.weight },
                )
            }

            private fun addWeight(map: MutableMap<String, Double>, label: String, weight: Double) {
                val existing = map.keys.firstOrNull { normalized(it) == normalized(label) } ?: label.trim()
                map[existing] = (map[existing] ?: 0.0) + weight
            }
        }
    }

    @Serializable private data class OlSearch(val docs: List<OlDoc> = emptyList())
    @Serializable private data class OlWork(
        val description: JsonElement? = null,
        val subjects: List<String> = emptyList(),
    )
    @Serializable private data class DetailsCache(
        val books: Map<String, BookRecommendation> = emptyMap(),
    )
    @Serializable private data class OlDoc(
        val key: String = "",
        val title: String = "",
        @SerialName("author_name") val authors: List<String> = emptyList(),
        @SerialName("cover_i") val coverId: Long? = null,
        @SerialName("subject") val subjects: List<String> = emptyList(),
        @SerialName("first_publish_year") val firstPublishYear: Int? = null,
        val isbn: List<String> = emptyList(),
        @SerialName("id_amazon") val amazonIds: List<String> = emptyList(),
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
        private const val ALGORITHM_VERSION = 2
        private const val CACHE_MS = 24 * 60 * 60 * 1000L

        private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        private fun bookKey(title: String, author: String?) =
            normalized(title).replace(Regex("^(the|a|an) "), "") + "|" + normalized(author.orEmpty())

        private fun recommendationKey(book: BookRecommendation): String = recommendationFeedbackKey(book)

        private fun seedSimilarity(
            authors: List<String>,
            subjects: List<String>,
            seed: SeedBook,
        ): Double {
            val candidateAuthors = authors.map(::normalized).toSet()
            val candidateTopics = subjects.map(::normalized).filter(String::isNotEmpty).toSet()
            val authorSimilarity = if (candidateAuthors.any { it in seed.authors }) 1.0 else 0.0
            val topicSimilarity = if (candidateTopics.isEmpty() || seed.topics.isEmpty()) 0.0 else {
                candidateTopics.intersect(seed.topics).size.toDouble() /
                    candidateTopics.union(seed.topics).size
            }
            return authorSimilarity * 0.35 + topicSimilarity * 0.65
        }

        private fun topicOverlap(left: List<String>, right: List<String>): Double {
            val a = left.map(::normalized).filter(String::isNotEmpty).toSet()
            val b = right.map(::normalized).filter(String::isNotEmpty).toSet()
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.intersect(b).size.toDouble() / a.union(b).size
        }

        private fun comparableTitle(value: String): String {
            var title = value.trim()
                .replace(
                    Regex(
                        "^(book|volume)\\s+[0-9]+(?:\\.[0-9]+)?\\s*[,.:–—-]?\\s*",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                .substringBefore(':')
                .substringBefore(" - ")
                .substringBefore(" — ")
                .substringBefore(" – ")
                .trim()
            // Providers frequently append series/sequence labels to otherwise identical
            // titles: "Soulsmith (Cradle) (Volume 2)". Peel every trailing label.
            val suffix = Regex("\\s*\\([^()]*\\)\\s*$")
            while (suffix.containsMatchIn(title)) title = title.replace(suffix, "").trim()
            return normalized(title).replace(Regex("^(the|a|an) "), "")
        }

        private fun normalizedIsbn(value: String?): String? {
            val clean = value?.filter(Char::isLetterOrDigit)?.uppercase(Locale.ROOT) ?: return null
            return clean.takeIf {
                (it.length == 13 && it.all(Char::isDigit)) ||
                    (it.length == 10 && it.take(9).all(Char::isDigit) &&
                        (it.last().isDigit() || it.last() == 'X'))
            }
        }

        private fun normalizedAsin(value: String?): String? = value
            ?.filter(Char::isLetterOrDigit)
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.length == 10 }

        private fun titleSimilarity(left: String, right: String): Double {
            val a = left.split(' ').filter(String::isNotBlank).toSet()
            val b = right.split(' ').filter(String::isNotBlank).toSet()
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.intersect(b).size.toDouble() / a.union(b).size
        }

        private fun topicMatches(left: String, right: String): Boolean {
            val a = normalized(left)
            val b = normalized(right)
            return a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a)
        }
    }
}
