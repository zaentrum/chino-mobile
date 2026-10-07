package cloud.nalet.chino.mobile.ui.zap

import cloud.nalet.chino.mobile.ui.player.CodecCaps

/** Android caps come from the device decoder list (shared with the full
 *  player's [CodecCaps]), without the 5.1 companions' codecs: a card keeps
 *  to the stereo group it is prefetched for. */
actual fun zapCodecCaps(): String = CodecCaps.stereoQueryParam
