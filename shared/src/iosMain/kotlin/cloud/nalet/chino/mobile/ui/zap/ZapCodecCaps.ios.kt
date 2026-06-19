package cloud.nalet.chino.mobile.ui.zap

/** iOS caps detection lands with the AVPlayer preview; until then the stub
 *  surface never fetches, so empty caps are fine (the server falls back to
 *  H.264). */
actual fun zapCodecCaps(): String = ""
