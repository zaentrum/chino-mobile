package cloud.nalet.chino.mobile.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cloud.nalet.chino.mobile.data.AppContainer

// No Google Cast on iOS: AirPlay is AVPlayer's own (the iOS player's route
// button). Nothing here draws or starts anything.

@Composable
actual fun CastButton(modifier: Modifier) = Unit

@Composable
actual fun CastBar(padNavigationBar: Boolean, modifier: Modifier) = Unit

@Composable
actual fun CastHost(container: AppContainer) = Unit
