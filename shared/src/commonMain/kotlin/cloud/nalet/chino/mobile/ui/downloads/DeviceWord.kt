package cloud.nalet.chino.mobile.ui.downloads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import cloud.nalet.chino.mobile.data.downloads.DeviceKind

/** What the app calls the device it runs on, in "on this phone": a tablet
 *  from 600 dp of the window's shorter side up ([ProvideDeviceKind]). */
val LocalDeviceKind = staticCompositionLocalOf { DeviceKind.Phone }

/** [content] with [LocalDeviceKind] for the window it is shown in. */
@Composable
fun ProvideDeviceKind(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val kind = if (minOf(maxWidth, maxHeight) >= 600.dp) DeviceKind.Tablet else DeviceKind.Phone
        CompositionLocalProvider(LocalDeviceKind provides kind) { content() }
    }
}
