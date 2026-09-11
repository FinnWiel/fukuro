package fukuro

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.SystemOutputSwitcherDialogController

/** Opens Android's media-output picker, equivalent to Spotify's "Listening on" action. */
@Composable
fun PlaybackOutputButton(
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            if (!SystemOutputSwitcherDialogController.showDialog(context)) {
                // Android 8–10 do not expose the unified output panel to apps.
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
        },
        modifier = modifier,
    ) {
        Icon(
            Icons.Rounded.Devices,
            contentDescription = "Listening on",
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
