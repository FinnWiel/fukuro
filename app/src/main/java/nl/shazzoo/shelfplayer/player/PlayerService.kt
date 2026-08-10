package nl.shazzoo.shelfplayer.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.shazzoo.shelfplayer.ShelfApp
import nl.shazzoo.shelfplayer.data.AbsApi
import nl.shazzoo.shelfplayer.data.DownloadRepo
import nl.shazzoo.shelfplayer.data.LibraryItem
import nl.shazzoo.shelfplayer.data.Store

@UnstableApi
class PlayerService : MediaLibraryService() {

    companion object {
        const val CMD_SLEEP_TIMER = "nl.shazzoo.shelfplayer.SLEEP_TIMER" // arg: minutes (0 = cancel)
        const val CMD_SLEEP_REMAINING = "nl.shazzoo.shelfplayer.SLEEP_REMAINING"
        const val ROOT_ID = "root"
        const val CONTINUE_ID = "continue"
        const val LIBRARY_ID = "library"
        const val DOWNLOADED_ID = "downloaded"
        const val BOOK_PREFIX = "book_"
    }

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api: AbsApi get() = ShelfApp.from(application).api
    private val downloads: DownloadRepo get() = ShelfApp.from(application).downloads
    private val store: Store get() = ShelfApp.from(application).store

    // sleep timer state
    private var sleepJob: Job? = null
    private var sleepEndsAt: Long = 0L

    // progress sync state
    private var currentItemId: String? = null
    private var currentItemDuration: Double = 0.0
    private var trackOffsets: List<Double> = emptyList() // cumulative start offset of each track

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()

        // periodic progress sync while playing
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) syncProgress()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) scope.launch { syncProgress() }
            }
        })
    }

    /** Absolute position within the whole book (all tracks), in seconds. */
    private fun bookPositionSec(): Double {
        val idx = player.currentMediaItemIndex
        val offset = trackOffsets.getOrElse(idx) { 0.0 }
        return offset + player.currentPosition / 1000.0
    }

    private suspend fun syncProgress() {
        val id = currentItemId ?: return
        val pos = bookPositionSec()
        store.setLocalProgress(id, pos) // always cache locally (offline resume)
        try {
            api.updateProgress(id, pos, currentItemDuration)
        } catch (_: Exception) { /* offline: ignore, next sync retries */ }
    }

    /** Load a book: build the multi-track playlist. Prefers downloaded local files. */
    private suspend fun buildPlaylist(itemId: String, startAtSec: Double?): List<MediaItem> {
        // online metadata first; fall back to the downloaded copy when offline
        val item = try { api.item(itemId) } catch (e: Exception) {
            downloads.localItem(itemId) ?: throw e
        }
        currentItemId = itemId
        currentItemDuration = item.media.duration
        val files = item.media.audioFiles.sortedBy { it.index }
        var acc = 0.0
        val offsets = ArrayList<Double>(files.size)
        for (f in files) { offsets.add(acc); acc += f.duration }
        trackOffsets = offsets
        val meta = item.media.metadata
        val localCover = downloads.localCover(itemId)
        val artUri = localCover?.let { Uri.fromFile(it) } ?: Uri.parse(api.coverUrl(itemId))
        return files.map { f ->
            val local = downloads.localAudioFile(itemId, f.ino)
            val uri = local?.let { Uri.fromFile(it) } ?: Uri.parse(api.fileUrl(itemId, f.ino))
            MediaItem.Builder()
                .setMediaId("$BOOK_PREFIX$itemId#${f.index}")
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(meta.title ?: item.relPath)
                        .setArtist(meta.authorName ?: "")
                        .setAlbumTitle(meta.seriesName ?: "")
                        .setArtworkUri(artUri)
                        .build()
                )
                .build()
        }
    }

    /** Given a book start position in seconds, find (trackIndex, positionInTrackMs). */
    private fun locate(sec: Double): Pair<Int, Long> {
        var idx = 0
        for (i in trackOffsets.indices) if (trackOffsets[i] <= sec) idx = i else break
        val within = sec - trackOffsets.getOrElse(idx) { 0.0 }
        return idx to (within * 1000).toLong()
    }

    inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession, controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val cmds = base.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP_REMAINING, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(cmds, base.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SLEEP_TIMER -> {
                    val minutes = args.getInt("minutes", 0)
                    sleepJob?.cancel()
                    if (minutes > 0) {
                        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
                        sleepJob = scope.launch {
                            delay(minutes * 60_000L - 10_000L)
                            // gentle 10s fade
                            val v0 = player.volume
                            for (step in 10 downTo 1) { player.volume = v0 * step / 10f; delay(1000) }
                            player.pause(); player.volume = v0; sleepEndsAt = 0L
                        }
                    } else sleepEndsAt = 0L
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SLEEP_REMAINING -> {
                    val remaining = ((sleepEndsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
                    val out = Bundle().apply { putLong("remainingSec", if (sleepEndsAt == 0L) 0 else remaining) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, out))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // ---------- Android Auto browse tree ----------

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(
                MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
            ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                when {
                    parentId == ROOT_ID -> LibraryResult.ofItemList(
                        ImmutableList.of(
                            folder(CONTINUE_ID, "Continue Listening"),
                            folder(DOWNLOADED_ID, "Downloaded"),
                            folder(LIBRARY_ID, "Library"),
                        ), params
                    )
                    parentId == DOWNLOADED_ID -> LibraryResult.ofItemList(
                        // works fully offline: metadata comes from the downloaded item.json
                        ImmutableList.copyOf(
                            downloads.downloadedIds().mapNotNull { id ->
                                downloads.localItem(id)?.let { bookItem(it) }
                            }
                        ), params
                    )
                    parentId == CONTINUE_ID -> {
                        val progress = api.me().mediaProgress
                            .filter { !it.isFinished && it.progress > 0.001 }
                            .sortedByDescending { it.progress }
                        val libId = api.libraries().firstOrNull()?.id
                        val items = if (libId != null) api.libraryItems(libId).associateBy { it.id } else emptyMap()
                        LibraryResult.ofItemList(
                            ImmutableList.copyOf(progress.mapNotNull { p -> items[p.libraryItemId]?.let { bookItem(it) } }),
                            params
                        )
                    }
                    parentId == LIBRARY_ID -> {
                        val libId = api.libraries().firstOrNull()?.id
                            ?: return@future LibraryResult.ofItemList(ImmutableList.of(), params)
                        LibraryResult.ofItemList(
                            ImmutableList.copyOf(api.libraryItems(libId).map { bookItem(it) }), params
                        )
                    }
                    else -> LibraryResult.ofItemList(ImmutableList.of(), params)
                }
            } catch (e: Exception) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
            }
        }

        /** Resolve a browsed/selected book into its real playable playlist (used by Auto + app). */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val first = mediaItems.firstOrNull()
            val id = first?.mediaId ?: ""
            if (id.startsWith(BOOK_PREFIX) && !id.contains('#')) {
                val itemId = id.removePrefix(BOOK_PREFIX)
                // resume position: server first, local cache when offline
                val saved = try {
                    api.me().mediaProgress.firstOrNull { it.libraryItemId == itemId && !it.isFinished }?.currentTime
                } catch (_: Exception) { store.localProgress()[itemId] }
                val playlist = buildPlaylist(itemId, saved)
                val (idx, posMs) = locate(saved ?: 0.0)
                MediaSession.MediaItemsWithStartPosition(playlist, idx, posMs)
            } else {
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
        }

        private fun folder(id: String, title: String): MediaItem =
            MediaItem.Builder().setMediaId(id).setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
            ).build()

        private fun bookItem(item: LibraryItem): MediaItem {
            val localCover = downloads.localCover(item.id)
            val art = localCover?.let { Uri.fromFile(it) } ?: Uri.parse(api.coverUrl(item.id))
            return MediaItem.Builder().setMediaId("$BOOK_PREFIX${item.id}").setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.media.metadata.title ?: item.relPath)
                    .setArtist(item.media.metadata.authorName ?: "")
                    .setArtworkUri(art)
                    .setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build()
            ).build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.launch { syncProgress() }
        session?.release()
        player.release()
        super.onDestroy()
    }
}
