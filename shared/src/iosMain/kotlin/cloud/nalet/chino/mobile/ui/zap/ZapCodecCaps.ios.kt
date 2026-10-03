package cloud.nalet.chino.mobile.ui.zap

import cloud.nalet.chino.mobile.ui.player.CodecCaps

/** iOS caps come from what VideoToolbox and AVFoundation play natively
 *  (the iOS [CodecCaps]); the stub preview never fetches yet, but Zap's
 *  prewarm already names the codecs the device takes. */
actual fun zapCodecCaps(): String = CodecCaps.queryParam
