package fukuro

import kotlinx.serialization.Serializable

/** One user-selected card on the mixed Custom Home shelf. */
@Serializable
data class CustomShelfEntry(
    val type: String,
    val id: String,
    val title: String,
)

@Serializable
data class LoginResponse(val user: AbsUser)

@Serializable
data class AbsUser(
    val id: String = "",
    val username: String = "",
    val token: String = "",
    val type: String = "user",
    val permissions: AbsUserPermissions = AbsUserPermissions(),
)

@Serializable
data class AbsUserPermissions(
    val download: Boolean = true,
    val update: Boolean = true,
    val delete: Boolean = false,
    val upload: Boolean = false,
    val accessAllLibraries: Boolean = true,
    val accessAllTags: Boolean = true,
    val accessExplicitContent: Boolean = true,
)

@Serializable
data class UsersResponse(val users: List<AbsUser> = emptyList())

@Serializable
data class OnlineUsersResponse(
    val usersOnline: List<AbsOnlineUser> = emptyList(),
    val openSessions: List<AbsOpenSession> = emptyList(),
)

@Serializable
data class AbsOnlineUser(
    val id: String = "",
    val username: String = "",
    val type: String = "user",
    val lastSeen: Long? = null,
)

@Serializable
data class AbsOpenSession(
    val id: String = "",
    val userId: String = "",
    val libraryItemId: String = "",
    val displayTitle: String = "",
    val displayAuthor: String = "",
    val updatedAt: Long = 0L,
)

@Serializable
data class LibrariesResponse(val libraries: List<AbsLibrary>)

@Serializable
data class AbsLibrary(
    val id: String,
    val name: String,
    val mediaType: String = "book",
    val folders: List<LibFolder> = emptyList(),
)

@Serializable
data class LibFolder(val id: String = "", val fullPath: String = "")

@Serializable
data class SeriesListResponse(val results: List<AbsSeries> = emptyList())

@Serializable
data class AuthorsResponse(val authors: List<AbsAuthor> = emptyList())

@Serializable
data class AbsAuthor(
    val id: String = "",
    val name: String = "",
    val imagePath: String? = null,
    val numBooks: Int = 0,
)

@Serializable
data class AbsSeries(
    val id: String = "",
    val name: String = "",
    val books: List<LibraryItem> = emptyList(),
)

@Serializable
data class ItemsResponse(val results: List<LibraryItem>, val total: Int = 0)

@Serializable
data class LibraryItem(
    val id: String,
    val libraryId: String = "",
    val relPath: String = "",
    val addedAt: Long = 0,
    val media: Media = Media(),
)

@Serializable
data class Media(
    val metadata: Metadata = Metadata(),
    val duration: Double = 0.0,
    val numAudioFiles: Int = 0,
    val audioFiles: List<AudioFile> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)

@Serializable
data class Chapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

@Serializable
data class Metadata(
    val title: String? = null,
    val titleIgnorePrefix: String? = null,
    val authorName: String? = null,
    val seriesName: String? = null,
    val series: List<SeriesRef> = emptyList(),
    val narratorName: String? = null,
    val description: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class SeriesRef(val id: String = "", val name: String = "", val sequence: String? = null)

@Serializable
data class AudioFile(
    val index: Int = 0,
    val ino: String = "",
    val duration: Double = 0.0,
    val metadata: FileMeta = FileMeta(),
)

@Serializable
data class FileMeta(val filename: String = "", val size: Long = 0)

@Serializable
data class MediaProgress(
    val id: String = "",
    val libraryItemId: String = "",
    val duration: Double = 0.0,
    val progress: Double = 0.0, // 0..1
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0, // epoch ms; orders Continue Listening
)

/**
 * A position kept on the device. The device is the source that always answers — the
 * server only knows what it has been told, and offline it is told nothing — so every
 * position is written here first and pushed up when there is a connection. [updatedAt]
 * is what decides the winner against the server's own `lastUpdate`.
 */
@Serializable
data class LocalProgress(
    val pos: Double = 0.0,
    val updatedAt: Long = 0L,
    val finished: Boolean = false,
)

@Serializable
data class MeResponse(
    val id: String = "",
    val username: String = "",
    val type: String = "user",
    val permissions: AbsUserPermissions = AbsUserPermissions(),
    val mediaProgress: List<MediaProgress> = emptyList(),
)

/** Aggregate returned by Audiobookshelf's /api/me/listening-stats endpoint. Times are seconds. */
@Serializable
data class ListeningStats(
    val totalTime: Double = 0.0,
    val items: Map<String, ListeningItemStat> = emptyMap(),
    val days: Map<String, Double> = emptyMap(),
    val dayOfWeek: Map<String, Double> = emptyMap(),
    val today: Double = 0.0,
    val recentSessions: List<ListeningSession> = emptyList(),
)

@Serializable
data class ListeningItemStat(
    val id: String = "",
    val timeListening: Double = 0.0,
    val mediaMetadata: ListeningMetadata = ListeningMetadata(),
)

@Serializable
data class ListeningMetadata(
    val title: String? = null,
    val author: String? = null,
    val authorName: String? = null,
)

@Serializable
data class ListeningSession(
    val id: String = "",
    val libraryItemId: String = "",
    val displayTitle: String = "",
    val displayAuthor: String = "",
    val timeListening: Double = 0.0,
    val date: String = "",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class ListeningSessionsResponse(
    val total: Int = 0,
    val sessions: List<ListeningSession> = emptyList(),
)

/** A session measured by Fukuro itself; needed because raw-file playback does not create an ABS session. */
@Serializable
data class LocalListeningSession(
    val id: String,
    val itemId: String,
    val title: String,
    val author: String = "",
    val startedAt: Long,
    val updatedAt: Long,
    val timeListening: Double,
    val syncedTimeListening: Double = 0.0,
    val libraryId: String = "",
    val duration: Double = 0.0,
    val startTime: Double = 0.0,
    val currentTime: Double = 0.0,
)

@Serializable
data class SyncLocalSessionResult(
    val id: String = "",
    val success: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SyncLocalSessionsResponse(val results: List<SyncLocalSessionResult> = emptyList())
