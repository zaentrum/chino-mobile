package cloud.nalet.chino.mobile.cast

import android.content.Context
import cloud.nalet.chino.mobile.data.AppContainer

/**
 * Starts casting's sender for [container]'s server and account - from the
 * app's Application, as the process starts, and again for each container the
 * app builds (a change of server). With the process, not with a screen: a
 * session the app resumes, the positions the TV plays and stop casting from
 * the notification work with no screen open.
 */
fun attachCasting(context: Context, container: AppContainer) = ChinoCast.attach(context, container)
