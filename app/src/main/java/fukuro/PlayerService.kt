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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@UnstableApi
class PlayerService : MediaLibraryService() {

    companion object {
        const val CMD_SLEEP_TIMER = "SLEEP_TIMER" // arg: minutes (0 = cancel)
        const val CMD_SLEEP_REMAINING = "SLEEP_REMAINING"
        const val CMD_SEEK_BACK_10 = "SEEK_BACK_10"
        const val CMD_SEEK_FWD_30 = "SEEK_FWD_30"
        const val CMD_BOOK_POSITION = "BOOK_POSITION"
        const val CMD_SEEK_ABS = "SEEK_ABS" // arg: posSec within the whole book
        const val CMD_SKIP_CHAPTER = "SKIP_CHAPTER" // arg: dir (+1 next, -1 previous)
        /**
         * How long onPlaybackResumption may take. Android's foreground-service window is
         * ~5s from the moment it starts us; staying under that is what keeps a slow or
         * absent server from turning a resume into a process kill.
         */
        private const val RESUMPTION_BUDGET_MS = 3_000L
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
    private var currentChapters: List<Chapter> = emptyList()
    private var widgetCoverId: String? = null // which book's cover the widgets are holding
    private var finishedItemId: String? = null // last book we already marked finished

    private suspend fun fetchItem(itemId: String): LibraryItem {
        // books from the on-device folder never touch the network
        if (LocalLibrary.isLocal(itemId)) {
            ShelfApp.from(application).local.items().firstOrNull { it.id == itemId }?.let { return it }
        }
        if (downloads.isDownloaded(itemId)) downloads.localItem(itemId)?.let { return it } // local first: instant
        // Prefetched by the app for Continue Listening books.
        // An entry with no audio is worse than no entry: it builds an empty playlist, and
        // since this cache never expired it would keep doing so forever. That happens when
        // a book is fetched while the server still lists it without its files.
        val shared = ShelfApp.from(application).itemCache
        shared[itemId]?.takeIf { it.media.audioFiles.isNotEmpty() }?.let { return it }
        val item = api.item(itemId)
        if (item.media.audioFiles.isNotEmpty()) shared[itemId] = item else shared.remove(itemId)
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

        // the session gets the scoped view, everything inside this service keeps using
        // the raw player (real per-file positions)
        session = MediaLibrarySession.Builder(this, ScopedPlayer(player), LibraryCallback()).build()

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
                if (player.isPlaying) {
                    syncProgress()
                    publishNowPlaying() // moves the widget progress bar along
                }
            }
        }

        // A chapter boundary is not an event as far as the player is concerned, so the
        // notification would happily count on past the end of the chapter. Touching the
        // metadata re-publishes position and duration to every controller.
        scope.launch {
            var lastKey = ""
            while (isActive) {
                delay(1000)
                if (currentChapters.isEmpty()) continue
                // Both dual-progress layouts keep the controller/notification scoped to
                // the chapter; their second bar is book progress drawn by the app.
                val perChapter = store.trackScopeBlocking() != "book"
                val idx = if (perChapter) {
                    currentChapters.indexOf(chapterAt(bookPositionSec()))
                } else -1
                val key = "$perChapter:$idx"
                if (key != lastKey) {
                    lastKey = key
                    pokeMetadata(idx)
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onBookFinished()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) scope.launch { syncProgress() }
                publishNowPlaying()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishNowPlaying(newBook = true)
            }
        })
    }

    /**
     * Keeps the home screen widgets in step. They read a snapshot off disk rather than
     * asking the player, because they are usually redrawn when this process is gone.
     */
    private fun publishNowPlaying(newBook: Boolean = false) {
        val id = currentItemId ?: return
        // Snapshot the player here rather than inside the coroutine: this is called from the
        // pause listener, which fires while the app is being torn down, and by the time a
        // coroutine ran the player could be stopped and cleared — leaving the widget showing
        // a blank title at position 0.
        val meta = player.currentMediaItem?.mediaMetadata
        val snapshot = NowPlaying(
            itemId = id,
            title = meta?.title?.toString().orEmpty(),
            author = meta?.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            positionSec = bookPositionSec(),
            durationSec = currentItemDuration,
        )
        if (snapshot.positionSec <= 0.0 && player.mediaItemCount == 0) return // nothing left to show
        scope.launch {
            WidgetData.write(this@PlayerService, snapshot)
            if (newBook) withContext(Dispatchers.IO) { cacheWidgetCover(id) }
            refreshWidgets(this@PlayerService)
        }
    }

    /** One cover on disk for the widgets; refetched only when the book changes. */
    private fun cacheWidgetCover(itemId: String) {
        if (widgetCoverId == itemId) return
        val onDisk = if (LocalLibrary.isLocal(itemId)) {
            ShelfApp.from(application).local.coverFile(itemId)
        } else {
            downloads.localCover(itemId)
        }
        if (onDisk != null && onDisk.exists()) {
            WidgetData.saveCover(this, onDisk)
            widgetCoverId = itemId
            return
        }
        // no local copy: fetch it once, so the widget still has art when the server goes
        runCatching {
            val req = okhttp3.Request.Builder().url(api.coverUrl(itemId)).build()
            api.http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes()?.let {
                    WidgetData.saveCover(this, it)
                    widgetCoverId = itemId
                }
            }
        }
    }

    /**
     * Reaching the end of the last track is the only moment we can be sure a book was
     * finished. Without this the server never learned, so the book stayed in Continue
     * Listening and reopening it started from nothing.
     */
    private fun onBookFinished() {
        val id = currentItemId ?: return
        if (id == finishedItemId) return // ENDED can fire more than once
        finishedItemId = id
        scope.launch {
            store.setLocalProgress(id, currentItemDuration)
            if (!LocalLibrary.isLocal(id)) {
                runCatching { api.updateProgress(id, currentItemDuration, currentItemDuration) }
                runCatching { api.markFinished(id, true) }
            }
            publishNowPlaying()
            // Opt-in only: rolling into the next book unasked is worse than stopping.
            if (store.autoNextBlocking()) {
                val next = nextInSeries(id)
                if (next != null) {
                    val playlist = buildPlaylist(next, 0.0)
                    player.setMediaItems(playlist, 0, 0L)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    /**
     * The following book by series sequence, or null when there isn't one. Series come
     * back from the server as "Name #3", so the number is parsed off the end.
     */
    private suspend fun nextInSeries(itemId: String): String? {
        val item = runCatching { fetchItem(itemId) }.getOrNull() ?: return null
        val raw = item.media.metadata.seriesName?.takeIf { it.isNotBlank() } ?: return null
        val name = raw.substringBeforeLast('#').trim()
        val seq = raw.substringAfterLast('#').trim().toDoubleOrNull() ?: return null
        val libId = runCatching { api.libraries().firstOrNull()?.id }.getOrNull() ?: return null
        val all = runCatching { api.libraryItems(libId) }.getOrNull() ?: return null
        return all.mapNotNull { other ->
            val s = other.media.metadata.seriesName ?: return@mapNotNull null
            if (s.substringBeforeLast('#').trim() != name) return@mapNotNull null
            val n = s.substringAfterLast('#').trim().toDoubleOrNull() ?: return@mapNotNull null
            if (n > seq) n to other.id else null
        }.minByOrNull { it.first }?.second
    }

    /** Absolute position within the whole book (all tracks), in seconds. */
    private fun bookPositionSec(): Double {
        val idx = player.currentMediaItemIndex
        val offset = trackOffsets.getOrElse(idx) { 0.0 }
        return offset + player.currentPosition / 1000.0
    }

    private fun chapterAt(sec: Double): Chapter? =
        currentChapters.firstOrNull { sec >= it.start && sec < it.end } ?: currentChapters.lastOrNull()

    /**
     * The stretch of the book the progress bar represents: the whole thing, or just the
     * current chapter when Settings says so. Returned as (startSec, lengthSec).
     */
    private fun scopeWindow(): Pair<Double, Double> {
        val bookLen = currentItemDuration
        if (store.trackScopeBlocking() == "book" || currentChapters.isEmpty()) return 0.0 to bookLen
        val ch = chapterAt(bookPositionSec()) ?: return 0.0 to bookLen
        return ch.start to (ch.end - ch.start)
    }

    /**
     * Swaps the current item for an identical one carrying the chapter number. Same uri, so
     * playback runs on untouched; the point is the metadata-changed event it fires.
     */
    private fun pokeMetadata(chapterIndex: Int) {
        val item = player.currentMediaItem ?: return
        val meta = item.mediaMetadata.buildUpon()
            .setTrackNumber(if (chapterIndex >= 0) chapterIndex + 1 else null)
            .setTotalTrackCount(if (chapterIndex >= 0) currentChapters.size else null)
            .build()
        try {
            player.replaceMediaItem(
                player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta).build()
            )
        } catch (_: Exception) { /* nothing loaded / racing a transition */ }
    }

    /**
     * Chapter to chapter. Going back lands at the start of the current chapter first,
     * the way every music player treats "previous"; only a second press leaves it.
     * With no chapter list a swipe runs to the ends of the book instead.
     */
    private fun skipChapter(dir: Int) {
        val pos = bookPositionSec()
        if (currentChapters.isEmpty()) {
            seekBookTo(if (dir > 0) currentItemDuration else 0.0)
            return
        }
        val idx = currentChapters.indexOf(chapterAt(pos)).coerceAtLeast(0)
        val target = if (dir > 0) {
            currentChapters.getOrNull(idx + 1)?.start ?: currentItemDuration
        } else {
            val cur = currentChapters[idx]
            if (pos - cur.start > 3.0) cur.start else currentChapters.getOrNull(idx - 1)?.start ?: 0.0
        }
        seekBookTo(target)
    }

    /** Seek by absolute book position, whatever track that lands in. */
    private fun seekBookTo(sec: Double) {
        val (idx, within) = locate(sec.coerceAtLeast(0.0))
        player.seekTo(idx, within)
    }

    /**
     * What the notification, lock screen and Android Auto see. ExoPlayer's timeline holds
     * one entry per audio file, which means nothing to a listener, so report the book (or
     * the current chapter) instead and translate seeks back to the real timeline.
     */
    private inner class ScopedPlayer(inner: Player) : androidx.media3.common.ForwardingPlayer(inner) {

        /** Nothing loaded yet, or a book with no duration: leave the real values alone. */
        private fun scoped(): Boolean = currentItemDuration > 0 && trackOffsets.isNotEmpty()

        override fun getDuration(): Long {
            if (!scoped()) return super.getDuration()
            val len = scopeWindow().second
            return if (len > 0) (len * 1000).toLong() else C.TIME_UNSET
        }

        override fun getContentDuration(): Long = duration

        override fun getCurrentPosition(): Long {
            if (!scoped()) return super.getCurrentPosition()
            val (start, len) = scopeWindow()
            val pos = ((bookPositionSec() - start) * 1000).toLong()
            return if (len > 0) pos.coerceIn(0L, (len * 1000).toLong()) else pos.coerceAtLeast(0L)
        }

        override fun getContentPosition(): Long = currentPosition

        override fun getBufferedPosition(): Long {
            if (!scoped()) return super.getBufferedPosition()
            val (start, len) = scopeWindow()
            val absBuffered =
                trackOffsets.getOrElse(super.getCurrentMediaItemIndex()) { 0.0 } +
                    super.getBufferedPosition() / 1000.0
            val rel = ((absBuffered - start) * 1000).toLong()
            val max = if (len > 0) (len * 1000).toLong() else Long.MAX_VALUE
            return rel.coerceIn(currentPosition, max)
        }

        override fun getContentBufferedPosition(): Long = bufferedPosition

        /** Scrubbing the notification bar hands us a position inside the shown window. */
        override fun seekTo(positionMs: Long) {
            if (!scoped()) { super.seekTo(positionMs); return }
            seekBookTo(scopeWindow().first + positionMs / 1000.0)
        }
    }

    private suspend fun syncProgress() {
        val id = currentItemId ?: return
        val pos = bookPositionSec()
        // This runs from coroutines — the 15s tick, the pause listener — and by the time one
        // of them gets its turn the player may already have been stopped and cleared, which
        // reads back as position 0. Saving that would wipe the listener's place in the book,
        // and a genuine 0 is nothing to resume from anyway.
        if (pos <= 0.0 || player.mediaItemCount == 0) return
        store.setLocalProgress(id, pos) // always cache locally (offline resume)
        if (LocalLibrary.isLocal(id)) return // on-device book: nothing to sync
        try {
            api.updateProgress(id, pos, currentItemDuration)
        } catch (_: Exception) { /* offline: ignore, the app pushes it on reconnect */ }
    }

    /**
     * Save the position for teardown — app swiped away, service destroyed.
     *
     * Both of those are about to stop and clear the player, and neither can wait on a
     * coroutine: a position read after `clearMediaItems()` is 0, and a coroutine launched
     * on the way out may never run at all. So the position is read here and now, and the
     * local write blocks until it is on disk. The server push is best effort — if the
     * process dies first, the app hands the position over on the next reconnect.
     */
    private fun saveProgressOnTeardown() {
        val id = currentItemId ?: return
        val pos = bookPositionSec()
        if (pos <= 0.0) return // nothing worth recording, and never overwrite with a zero
        store.setLocalProgressBlocking(id, pos)
        if (LocalLibrary.isLocal(id)) return
        val duration = currentItemDuration
        ShelfApp.from(application).appScope.launch {
            runCatching { api.updateProgress(id, pos, duration) }
        }
    }

    /** Load a book: build the multi-track playlist. Prefers downloaded local files. */
    private suspend fun buildPlaylist(itemId: String, startAtSec: Double?): List<MediaItem> {
        // local copy first (instant), then cache, then network; offline fallback to local
        val item = try { fetchItem(itemId) } catch (e: Exception) {
            downloads.localItem(itemId) ?: throw e
        }
        currentItemId = itemId
        if (finishedItemId != itemId) finishedItemId = null // a fresh load can finish again
        scope.launch { store.setLastItem(itemId) } // what onPlaybackResumption answers with
        currentItemDuration = item.media.duration
        currentChapters = item.media.chapters.filter { it.end > it.start }
        val files = item.media.audioFiles.sortedBy { it.index }
        // nothing to play: fail out loud instead of starting a player with no tracks
        if (files.isEmpty()) error("No audio files on the server for $itemId")
        var acc = 0.0
        val offsets = ArrayList<Double>(files.size)
        for (f in files) { offsets.add(acc); acc += f.duration }
        trackOffsets = offsets
        val meta = item.media.metadata
        val isLocalBook = LocalLibrary.isLocal(itemId)
        // always the provider: a file:// path or an http url is artwork Android Auto
        // will not load. CoverProvider sorts out where the image actually comes from.
        val artUri = CoverProvider.uriFor(this, itemId)
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
                .add(SessionCommand(CMD_SEEK_ABS, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_CHAPTER, Bundle.EMPTY))
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
                    // live position across the WHOLE book, plus the window the bars should
                    // show (book or current chapter) so the app doesn't have to work it out
                    val pos = bookPositionSec()
                    val (winStart, winLen) = scopeWindow()
                    val out = Bundle().apply {
                        putDouble("posSec", pos)
                        putDouble("durSec", currentItemDuration)
                        putDouble("winStartSec", winStart)
                        putDouble("winLenSec", winLen)
                        putDouble(
                            "frac",
                            if (winLen > 0) ((pos - winStart) / winLen).coerceIn(0.0, 1.0) else 0.0
                        )
                        // the mini player has no chapter list of its own; the service does
                        putString("chapter", chapterAt(pos)?.title?.takeIf { it.isNotBlank() } ?: "")
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, out))
                }
                CMD_SEEK_ABS -> {
                    seekBookTo(args.getDouble("posSec", 0.0))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SKIP_CHAPTER -> {
                    skipChapter(args.getInt("dir", 1))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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

        /**
         * The system asking us to resume the last book on its own — a car connecting, a
         * headset button, the media resumption chip in Quick Settings, a reboot.
         *
         * This has to answer, and answer quickly. Android starts the service in the
         * foreground before asking, and if nothing is playing ~5s later it kills the
         * process with ForegroundServiceDidNotStartInTimeException — which is exactly what
         * happened while this callback was missing: media3 had nothing to hand back, so no
         * notification was ever posted. Failing the future is a fine answer (media3 then
         * shuts the service down cleanly); hanging on a 20s read timeout is not, so the
         * whole lookup is time-boxed well inside the window.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            try {
                withTimeout(RESUMPTION_BUDGET_MS) {
                    val itemId = currentItemId ?: store.lastItem()
                        ?: throw IllegalStateException("no book to resume")
                    // the locally cached position, written every 15s while playing and again
                    // on pause; the server is not worth a round trip on this deadline
                    val saved = store.localProgress()[itemId]?.pos ?: 0.0
                    val playlist = buildPlaylist(itemId, saved)
                    if (playlist.isEmpty()) throw IllegalStateException("nothing playable in $itemId")
                    val (idx, posMs) = locate(saved)
                    MediaSession.MediaItemsWithStartPosition(playlist, idx, posMs)
                }
            } catch (e: TimeoutCancellationException) {
                // a failed future is an answer; a cancelled one is vaguer, and this future
                // is the only thing standing between us and the 5s kill
                throw IllegalStateException("resume lookup timed out", e)
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
                // else the newer of what the device remembers and what the server was last
                // told (the Android Auto path). Offline listening leaves the server behind,
                // so trusting it blindly here would rewind the book.
                val fromExtras = first?.mediaMetadata?.extras?.getDouble("startTimeSec", -1.0) ?: -1.0
                val local = store.localProgress()[itemId]
                val saved = when {
                    fromExtras >= 0 -> fromExtras
                    LocalLibrary.isLocal(itemId) -> local?.pos
                    else -> {
                        val server = try {
                            api.me().mediaProgress.firstOrNull { it.libraryItemId == itemId && !it.isFinished }
                        } catch (_: Exception) { null }
                        when {
                            server == null -> local?.pos
                            local != null && local.updatedAt > server.lastUpdate -> local.pos
                            else -> server.currentTime
                        }
                    }
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
            val art = CoverProvider.uriFor(this@PlayerService, item.id)
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
        saveProgressOnTeardown() // before the player is stopped and cleared out from under it
        player.pause()
        player.stop()
        player.clearMediaItems()
        // leave the foreground before stopping rather than while media3 is still promoting
        // us into it, which is the other way this service earns a
        // ForegroundServiceDidNotStartInTimeException
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        saveProgressOnTeardown() // the player is about to be released; read it while it lives
        session?.release()
        player.release()
        super.onDestroy()
    }
}
