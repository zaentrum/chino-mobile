package cloud.nalet.chino.mobile.ui.zap

import cloud.nalet.chino.mobile.ui.player.CodecCaps

/** Android caps come from the device decoder list (shared with the full
 *  player's [CodecCaps]). */
actual fun zapCodecCaps(): String = CodecCaps.queryParam
