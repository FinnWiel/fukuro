package fukuro

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(val user: AbsUser)

@Serializable
data class AbsUser(val id: String, val username: String, val token: String)

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

@Serializable
data class MeResponse(val id: String = "", val username: String = "", val mediaProgress: List<MediaProgress> = emptyList())
