package cloud.nalet.chino.mobile.ui.zap

/**
 * Comma-separated decoder caps (`avc:1080,hvc:1080,av1,aac,mp3,…` — see
 * [cloud.nalet.chino.mobile.ui.player.CodecCapsQuery]) to advertise on the
 * Zap preview's master.m3u8 so chino-stream serves codecs the device can
 * decode — the same negotiation the full player does. Lives behind an
 * expect/actual so the ScreenModel can build the per-card URL in commonMain.
 *
 * Android delegates to the existing player [CodecCaps]; iOS returns empty
 * until AVPlayer caps detection lands (the stub preview never fetches).
 */
expect fun zapCodecCaps(): String
