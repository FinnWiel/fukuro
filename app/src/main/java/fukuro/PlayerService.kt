package fukuro

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
import androidx.media3.session.CommandButton
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

@UnstableApi
class PlayerService : MediaLibraryService() {

    companion object {
        const val CMD_SLEEP_TIMER = "SLEEP_TIMER" // arg: minutes (0 = cancel)
        const val CMD_SLEEP_REMAINING = "SLEEP_REMAINING"
        const val CMD_SEEK_BACK_10 = "SEEK_BACK_10"
        const val CMD_SEEK_FWD_30 = "SEEK_FWD_30"
        const val CMD_BOOK_POSITION = "BOOK_POSITION"
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

    // short-lived cache so pressing play doesn't refetch item metadata
    private val itemCache = HashMap<String, Pair<Long, LibraryItem>>()

    private suspend fun fetchItem(itemId: String): LibraryItem {
        // books from the on-device folder never touch the network
        if (LocalLibrary.isLocal(itemId)) {
            ShelfApp.from(application).local.items().firstOrNull { it.id == itemId }?.let { return it }
        }
        if (downloads.isDownloaded(itemId)) downloads.localItem(itemId)?.let { return it } // local first: instant
        itemCache[itemId]?.let { (t, cached) ->
            if (System.currentTimeMillis() - t < 60_000) return cached
        }
        val item = api.item(itemId)
        itemCache[itemId] = System.currentTimeMillis() to item
        return item
    }

    override fun onCreate() {
        super.onCreate()
        // start playback as soon as ~0.5s is buffered instead of the 2.5s default
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 500, 1_000)
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
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

        // Replace prev/next in the notification, lock screen and Android Auto
        // with -10s / +30s buttons (slots BACK and FORWARD).
        val back10 = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Back 10 seconds")
            .setSessionCommand(SessionCommand(CMD_SEEK_BACK_10, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val fwd30 = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setSessionCommand(SessionCommand(CMD_SEEK_FWD_30, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        session?.setMediaButtonPreferences(ImmutableList.of(back10, fwd30))

        // restore last playback speed
        scope.launch {
            val speed = store.playbackSpeed()
            if (speed > 0f) player.setPlaybackSpeed(speed)
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
                scope.launch { store.setPlaybackSpeed(params.speed) }
            }
        })

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
        if (LocalLibrary.isLocal(id)) return // on-device book: nothing to sync
        try {
            api.updateProgress(id, pos, currentItemDuration)
        } catch (_: Exception) { /* offline: ignore, next sync retries */ }
    }

    /** Load a book: build the multi-track playlist. Prefers downloaded local files. */
    private suspend fun buildPlaylist(itemId: String, startAtSec: Double?): List<MediaItem> {
        // local copy first (instant), then cache, then network; offline fallback to local
        val item = try { fetchItem(itemId) } catch (e: Exception) {
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
        val isLocalBook = LocalLibrary.isLocal(itemId)
        val localCover = if (isLocalBook) ShelfApp.from(application).local.coverFile(itemId)
        else downloads.localCover(itemId)
        val artUri = localCover?.let { Uri.fromFile(it) }
            ?: if (isLocalBook) null else Uri.parse(api.coverUrl(itemId))
        return files.map { f ->
            val downloaded = downloads.localAudioFile(itemId, f.ino)
            val uri = when {
                // on-device book: the ino is the document uri itself
                isLocalBook -> Uri.parse(f.ino)
                downloaded != null -> Uri.fromFile(downloaded)
                else -> Uri.parse(api.fileUrl(itemId, f.ino))
            }
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
                .add(SessionCommand(CMD_SEEK_BACK_10, Bundle.EMPTY))
                .add(SessionCommand(CMD_SEEK_FWD_30, Bundle.EMPTY))
                .add(SessionCommand(CMD_BOOK_POSITION, Bundle.EMPTY))
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
                CMD_BOOK_POSITION -> {
                    // live position across the WHOLE book, for the mini player
                    val out = Bundle().apply {
                        putDouble("posSec", bookPositionSec())
                        putDouble("durSec", currentItemDuration)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, out))
                }
                CMD_SEEK_BACK_10 -> {
                    val ms = store.skipBackBlocking() * 1000L
                    player.seekTo((player.currentPosition - ms).coerceAtLeast(0))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SEEK_FWD_30 -> {
                    val ms = store.skipForwardBlocking() * 1000L
                    player.seekTo(player.currentPosition + ms)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
                // resume position: prefer the position handed over by the app (no network),
                // else ask the server (Android Auto path), else the local cache
                val fromExtras = first?.mediaMetadata?.extras?.getDouble("startTimeSec", -1.0) ?: -1.0
                val saved = when {
                    fromExtras >= 0 -> fromExtras
                    LocalLibrary.isLocal(itemId) -> store.localProgress()[itemId]
                    else -> try {
                        api.me().mediaProgress.firstOrNull { it.libraryItemId == itemId && !it.isFinished }?.currentTime
                    } catch (_: Exception) { store.localProgress()[itemId] }
                }
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

    /**
     * App swiped away from recents = stop playback completely (user preference).
     * Backgrounding (home button / screen off) never triggers this, so playback continues there.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch { syncProgress() }
        player.pause()
        player.stop()
        stopSelf()
    }

    override fun onDestroy() {
        scope.launch { syncProgress() }
        session?.release()
        player.release()
        super.onDestroy()
    }
}
