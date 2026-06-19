package cloud.nalet.chino.mobile.ui.zap

/**
 * Comma-separated decoder caps (`avc,hvc,av1`) to advertise on the Zap
 * preview's master.m3u8 so chino-stream serves a codec the device can
 * hardware-decode — the same negotiation the full player does. Lives behind
 * an expect/actual so the ScreenModel can build the per-card URL in
 * commonMain.
 *
 * Android delegates to the existing player [CodecCaps]; iOS returns empty
 * until AVPlayer caps detection lands (the stub preview never fetches).
 */
expect fun zapCodecCaps(): String
