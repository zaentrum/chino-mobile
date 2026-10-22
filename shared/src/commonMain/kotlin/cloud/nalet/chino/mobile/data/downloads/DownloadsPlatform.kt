package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.AppContainer

/**
 * What a platform brings for downloads and for playing without the server -
 * Android's download manager, its storage, its view of the network. A host
 * without it (iOS for now) passes none to [AppContainer]: no download
 * control, no downloads screen, and the app opens as it always did.
 */
interface DownloadsPlatform {
    /** The `?caps=` a download asks the server's master for: what the
     *  device decodes, with the stereo audio its own speaker plays. */
    fun downloadCaps(): String

    /** Whether the phone has a connection at all; null where it cannot
     *  tell. False makes the server unreachable without asking it. */
    fun networkUp(): Boolean?

    /** Where the positions played are kept across restarts. */
    val progressBookStore: ProgressBookStore

    /** The platform's downloads, built on [container]'s graph. */
    fun downloads(container: AppContainer): Downloads

    /** The first position waiting for the server was queued: send the
     *  queue once a connection is back, with the app open or not, and again
     *  while the server does not take it. In the place of a send already
     *  planned - one on its way may have counted the queue before this
     *  position came. */
    fun scheduleProgressFlush()
}
