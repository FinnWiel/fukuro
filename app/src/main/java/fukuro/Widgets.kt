package fukuro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity // the Intent overload
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/* A widget is drawn on the launcher, outside the app's theme, so its colours come from
   resources with a -night variant rather than from MaterialTheme. */
private val Accent = ColorProvider(R.color.widget_accent)
private val OnSurface = ColorProvider(R.color.widget_on_surface)
private val OnSurfaceDim = ColorProvider(R.color.widget_on_surface_dim)
private val TrackColor = ColorProvider(R.color.widget_track)

/* ---------------- the widgets themselves ---------------- */

/** One line: cover, what's playing, play/pause. */
class PlayerWidgetSmall : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val np = WidgetData.read(context)
        val cover = WidgetData.cover(context)
        provideContent { PlayerBody(np, cover, large = false) }
    }
}

/** Same, with room for the progress and the seek buttons. */
class PlayerWidgetLarge : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val np = WidgetData.read(context)
        val cover = WidgetData.cover(context)
        provideContent { PlayerBody(np, cover, large = true) }
    }
}

/** The last book played, whether or not the player still has it loaded. */
class RecentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val np = WidgetData.read(context)
        val cover = WidgetData.cover(context)
        provideContent { RecentBody(np, cover) }
    }
}

/**
 * One cell, cover only. No card, no title, no buttons — it sits among the app icons and
 * reads as one of them, and tapping it opens that book the way tapping an icon opens an
 * app. The 2x2 [RecentWidget] is the version that explains itself.
 */
class RecentIconWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val np = WidgetData.read(context)
        val cover = WidgetData.cover(context)
        provideContent { RecentIconBody(np, cover) }
    }
}

class PlayerWidgetSmallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidgetSmall()
}

class PlayerWidgetLargeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidgetLarge()
}

class RecentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentWidget()
}

class RecentIconWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentIconWidget()
}

/** Redraws every widget the user has placed. Safe to call when none are. */
suspend fun refreshWidgets(context: Context) {
    runCatching { PlayerWidgetSmall().updateAll(context) }
    runCatching { PlayerWidgetLarge().updateAll(context) }
    runCatching { RecentWidget().updateAll(context) }
    runCatching { RecentIconWidget().updateAll(context) }
}

/* ---------------- content ---------------- */

@Composable
private fun PlayerBody(np: NowPlaying?, cover: android.graphics.Bitmap?, large: Boolean) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg))
            .padding(if (large) 10.dp else 8.dp)
            .clickable(actionStartActivity(openAppIntent(context, np?.itemId))),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        // sized to fit the shortest cell the launcher will hand out, padding included
        Cover(cover, GlanceModifier.size(if (large) 84.dp else 44.dp))
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                np?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                style = TextStyle(color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = if (large) 2 else 1
            )
            if (!np?.author.isNullOrBlank()) {
                Text(
                    np.author,
                    style = TextStyle(color = OnSurfaceDim, fontSize = 12.sp),
                    maxLines = 1
                )
            }
            if (large && np != null) {
                Spacer(GlanceModifier.height(8.dp))
                LinearProgressIndicator(
                    progress = np.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                    color = Accent,
                    backgroundColor = TrackColor
                )
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    ControlButton(R.drawable.ic_widget_rewind, 30.dp, actionRunCallback<SeekBackAction>())
                    Spacer(GlanceModifier.width(6.dp))
                    PlayPause(np.isPlaying, np.itemId, 38.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    ControlButton(R.drawable.ic_widget_forward, 30.dp, actionRunCallback<SeekForwardAction>())
                }
            }
        }
        if (!large) {
            Spacer(GlanceModifier.width(8.dp))
            PlayPause(np?.isPlaying == true, np?.itemId, 40.dp)
        }
    }
}

@Composable
private fun RecentBody(np: NowPlaying?, cover: android.graphics.Bitmap?) {
    val context = LocalContext.current
    Column(
        GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_bg))
            .padding(10.dp)
            .clickable(actionStartActivity(openAppIntent(context, np?.itemId))),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.BottomEnd) {
            // fills whatever the cell allows rather than a fixed size that would be
            // cropped to its top-left corner on a small one
            Cover(cover, GlanceModifier.fillMaxSize())
            PlayPause(np?.isPlaying == true, np?.itemId, 34.dp)
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            np?.title?.takeIf { it.isNotBlank() } ?: "Nothing played yet",
            style = TextStyle(color = OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 2
        )
    }
}

@Composable
private fun RecentIconBody(np: NowPlaying?, cover: android.graphics.Bitmap?) {
    val context = LocalContext.current
    Box(
        GlanceModifier.fillMaxSize()
            .clickable(actionStartActivity(openAppIntent(context, np?.itemId))),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = np?.title,
                // roughly an app icon's corner: enough to sit next to real ones without
                // looking like a card
                modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            // nothing played yet: be the app's own icon rather than an empty square
            Image(
                provider = ImageProvider(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun Cover(cover: android.graphics.Bitmap?, sizing: GlanceModifier) {
    val mod = sizing.cornerRadius(8.dp)
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            modifier = mod,
            contentScale = ContentScale.Crop
        )
    } else {
        // nothing cached: the launcher icon stands in rather than an empty hole
        Image(
            provider = ImageProvider(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = mod,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun PlayPause(playing: Boolean, itemId: String?, size: androidx.compose.ui.unit.Dp) {
    // with nothing loaded this starts the last book instead of toggling silence
    val action = if (itemId.isNullOrBlank()) {
        actionRunCallback<TogglePlayAction>()
    } else {
        actionRunCallback<TogglePlayAction>(actionParametersOf(bookIdKey to itemId))
    }
    Image(
        provider = ImageProvider(
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        ),
        contentDescription = if (playing) "Pause" else "Play",
        modifier = GlanceModifier.size(size).cornerRadius(size / 2).clickable(action),
        colorFilter = androidx.glance.ColorFilter.tint(Accent)
    )
}

@Composable
private fun ControlButton(
    icon: Int,
    size: androidx.compose.ui.unit.Dp,
    action: androidx.glance.action.Action,
) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        modifier = GlanceModifier.size(size).clickable(action),
        colorFilter = androidx.glance.ColorFilter.tint(OnSurface)
    )
}

/* ---------------- actions ---------------- */

val bookIdKey = ActionParameters.Key<String>("bookId")

/** Deep link into the book when we know which one, otherwise just open the app. */
private fun openAppIntent(context: Context, itemId: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (!itemId.isNullOrBlank()) {
            action = Intent.ACTION_VIEW
            data = Uri.parse("fukuro://book/$itemId")
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/**
 * Widgets have no player of their own; they borrow the session for a moment. Connecting
 * also starts the service if it isn't running, which is what makes play work from a cold
 * home screen.
 */
private suspend fun withController(context: Context, block: (MediaController) -> Unit) {
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }
}

class TogglePlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val wanted = parameters[bookIdKey]
        runCatching {
            withController(context) { c ->
                val loaded = c.currentMediaItem?.mediaId
                    ?.removePrefix(PlayerService.BOOK_PREFIX)?.substringBefore('#')
                when {
                    c.isPlaying -> c.pause()
                    // same book already loaded, or nothing asked for: just resume
                    wanted == null || wanted == loaded -> c.play()
                    else -> {
                        c.setMediaItem(
                            MediaItem.Builder()
                                .setMediaId("${PlayerService.BOOK_PREFIX}$wanted").build()
                        )
                        c.prepare()
                        c.play()
                    }
                }
            }
        }
        refreshWidgets(context)
    }
}

class SeekBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val secs = ShelfApp.from(context.applicationContext as android.app.Application)
            .store.skipBackBlocking()
        runCatching {
            withController(context) { c ->
                c.seekTo((c.currentPosition - secs * 1000L).coerceAtLeast(0))
            }
        }
        refreshWidgets(context)
    }
}

class SeekForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val secs = ShelfApp.from(context.applicationContext as android.app.Application)
            .store.skipForwardBlocking()
        runCatching {
            withController(context) { c -> c.seekTo(c.currentPosition + secs * 1000L) }
        }
        refreshWidgets(context)
    }
}
