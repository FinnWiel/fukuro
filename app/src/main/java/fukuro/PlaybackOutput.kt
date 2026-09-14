package fukuro

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter

/**
 * The currently selected media route, kept live for as long as it is composed.
 * Both the plain icon button and the labelled row read from this.
 */
@Composable
private fun rememberSelectedRoute(): MediaRouter.RouteInfo {
    val context = LocalContext.current
    val router = remember(context) { MediaRouter.getInstance(context) }
    var route by remember(router) { mutableStateOf(router.selectedRoute) }

    DisposableEffect(router) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteSelected(router: MediaRouter, selected: MediaRouter.RouteInfo, reason: Int) {
                route = selected
            }

            override fun onRouteUnselected(router: MediaRouter, selected: MediaRouter.RouteInfo, reason: Int) {
                route = router.selectedRoute
            }

            override fun onRouteChanged(router: MediaRouter, changed: MediaRouter.RouteInfo) {
                if (changed.isSelected) route = changed
            }
        }
        router.addCallback(
            MediaRouteSelector.EMPTY,
            callback,
            MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS,
        )
        route = router.selectedRoute
        onDispose { router.removeCallback(callback) }
    }
    return route
}

private fun openOutputPicker(context: android.content.Context) {
    if (!SystemOutputSwitcherDialogController.showDialog(context)) {
        // Android 8-10 do not expose the unified output panel to apps.
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
}

private fun routeIsRemote(route: MediaRouter.RouteInfo): Boolean =
    !route.isDefault && route.deviceType != MediaRouter.RouteInfo.DEVICE_TYPE_SMARTPHONE

// Bluetooth devices often arrive as generic A2DP routes, so their advertised name is the
// only device-type signal available to the app. Keep these lists broad, but conservative enough
// that common headphone and speaker model names do not get confused with one another.
private val CAR_ROUTE_KEYWORDS = listOf(
    "car", "radio", "stereo", "automotive", "vehicle", "head unit", "infotainment",
    "carplay", "android auto", "receiver", "bt-radio",
)
private val SPEAKER_ROUTE_KEYWORDS = listOf(
    "speaker", "soundbar", "homepod", "sonos", "nest audio", "google home", "home mini",
    "echo", "alexa", "soundlink", "flip", "charge", "ue boom", "megaboom",
    "roam", "move", "revolve", "beosound",
)
private val HEADPHONE_ROUTE_KEYWORDS = listOf(
    "headphone", "headset", "earbud", "earphone", "airpods", "buds", "freebuds", "linkbuds",
    "quietcomfort", "qc35", "qc45", "qc ultra", "momentum", "wh-", "wf-",
)

/** Opens Android's media-output picker, equivalent to Spotify's "Listening on" action. */
@Composable
fun PlaybackOutputButton(
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    val context = LocalContext.current
    val route = rememberSelectedRoute()

    IconButton(
        onClick = { openOutputPicker(context) },
        modifier = modifier,
    ) {
        Icon(
            playbackOutputIcon(route),
            contentDescription = "Listening on ${route.name}",
            modifier = Modifier.size(iconSize),
            tint = if (routeIsRemote(route)) Fukuro.colors.accent else tint,
        )
    }
}

/**
 * The same action with the output named next to it: the icon followed by the device
 * audio is playing on. Used under the transport, where there is room for the name
 * the bare icon has to leave implicit.
 */
@Composable
fun PlaybackOutputLabel(
    tint: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
) {
    val context = LocalContext.current
    val route = rememberSelectedRoute()
    val remote = routeIsRemote(route)
    val color = if (remote) Fukuro.colors.accent else labelColor

    Row(
        modifier
            .clickable { openOutputPicker(context) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            playbackOutputIcon(route),
            // the label names the device; the icon carries the action
            contentDescription = "Listening on ${route.name}. Change output",
            modifier = Modifier.size(iconSize),
            tint = if (remote) Fukuro.colors.accent else tint,
        )
        Text(
            route.name.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun playbackOutputIcon(route: MediaRouter.RouteInfo): ImageVector {
    if (route.isDefault || route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_SMARTPHONE) {
        return Icons.Rounded.Smartphone
    }

    return when (route.deviceType) {
        MediaRouter.RouteInfo.DEVICE_TYPE_CAR -> Icons.Rounded.DirectionsCar

        MediaRouter.RouteInfo.DEVICE_TYPE_WIRED_HEADPHONES,
        MediaRouter.RouteInfo.DEVICE_TYPE_WIRED_HEADSET,
        MediaRouter.RouteInfo.DEVICE_TYPE_USB_HEADSET,
        MediaRouter.RouteInfo.DEVICE_TYPE_BLE_HEADSET -> Icons.Rounded.Headphones

        MediaRouter.RouteInfo.DEVICE_TYPE_TV -> Icons.Rounded.Tv
        MediaRouter.RouteInfo.DEVICE_TYPE_COMPUTER -> Icons.Rounded.Computer
        MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> Icons.Rounded.Cast
        MediaRouter.RouteInfo.DEVICE_TYPE_USB_ACCESSORY,
        MediaRouter.RouteInfo.DEVICE_TYPE_USB_DEVICE -> Icons.Rounded.Usb
        MediaRouter.RouteInfo.DEVICE_TYPE_HEARING_AID -> Icons.Rounded.Hearing

        MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER,
        MediaRouter.RouteInfo.DEVICE_TYPE_BUILTIN_SPEAKER,
        MediaRouter.RouteInfo.DEVICE_TYPE_AUDIO_VIDEO_RECEIVER -> Icons.Rounded.Speaker

        MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH_A2DP -> {
            val name = listOf(route.name, route.description)
                .joinToString(" ") { it?.toString().orEmpty() }
                .lowercase()
            val normalizedName = name.filter { it.isLetterOrDigit() }
            when {
                // Some car stereos expose only generic A2DP. These words are a fallback for
                // that case; an explicit DEVICE_TYPE_CAR above always takes precedence.
                CAR_ROUTE_KEYWORDS.any(name::contains) ->
                    Icons.Rounded.DirectionsCar
                SPEAKER_ROUTE_KEYWORDS.any(name::contains) ->
                    Icons.Rounded.Speaker
                // Many Bluetooth headphones (including Sony WH/WF models) are reported only
                // as generic A2DP routes, so use their advertised name as a fallback signal.
                HEADPHONE_ROUTE_KEYWORDS.any(name::contains) ||
                    listOf("wh1000", "wf1000", "xm5", "qc35", "qc45").any(normalizedName::contains) ->
                    Icons.Rounded.Headphones
                else -> Icons.Rounded.Bluetooth
            }
        }

        else -> Icons.Rounded.Bluetooth
    }
}
