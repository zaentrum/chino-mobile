package cloud.nalet.chino.mobile.ui.zap

import kotlin.math.abs
import kotlin.math.tanh

/** Item features fed into the preference vector. type = Item.kind; genres +
 *  castNames come from the per-card detail fetch (empty until it resolves). */
data class ZapFeatures(
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val castNames: List<String> = emptyList(),
)

/**
 * In-memory preference vector — faithful port of chino-androidtv's
 * ZapPreferences (itself a port of chino-web's useZapPreferences). Three
 * weight tables (genre / cast / type) with decay-first updates and a
 * tanh-squashed score. Session-scoped: the vector dies with the screen-model
 * (V2 persists it to Postgres). Not thread-safe — drive from the
 * ScreenModel's single coroutine scope.
 *
 * Verbatim constants: DECAY=0.92, MIN_WEIGHT=0.05, TYPE_WEIGHT=0.3 and the
 * hand-calibrated signal strengths / dwell thresholds.
 */
class ZapPreferences {
    private val genres = HashMap<String, Double>()
    private val cast = HashMap<String, Double>()
    private val type = HashMap<String, Double>()

    /** Decay every weight, then distribute [strength]: full to genres,
     *  half to (first 5) cast, 30 % to type. */
    fun update(features: ZapFeatures, strength: Double) {
        decay(genres); decay(cast); decay(type)

        val g = features.genres.filter { it.isNotBlank() }
        if (g.isNotEmpty()) {
            val per = strength / g.size
            g.forEach { genres[it] = (genres[it] ?: 0.0) + per }
        }
        val c = features.castNames.filter { it.isNotBlank() }.take(5)
        if (c.isNotEmpty()) {
            val per = (strength * 0.5) / c.size
            c.forEach { cast[it] = (cast[it] ?: 0.0) + per }
        }
        features.type?.takeIf { it.isNotBlank() }?.let {
            type[it] = (type[it] ?: 0.0) + strength * TYPE_WEIGHT
        }
    }

    /** Normalized score in (-1,1) — the ε-greedy exploit ranking signal. */
    fun score(features: ZapFeatures): Double {
        var raw = 0.0
        features.genres.forEach { raw += genres[it] ?: 0.0 }
        features.castNames.take(5).forEach { raw += cast[it] ?: 0.0 }
        features.type?.let { raw += type[it] ?: 0.0 }
        return tanh(raw)
    }

    private fun decay(m: HashMap<String, Double>) {
        val iter = m.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val v = e.value * DECAY
            if (abs(v) < MIN_WEIGHT) iter.remove() else e.setValue(v)
        }
    }

    companion object {
        private const val DECAY = 0.92
        private const val MIN_WEIGHT = 0.05
        private const val TYPE_WEIGHT = 0.3

        // Signal strengths (hand-calibrated; V2 should learn them).
        const val COMPLETE = 1.0
        const val DWELL_LONG = 0.5
        const val EXPAND = 1.5
        const val SAVE = 2.0
        const val SKIP_FAST = -1.0
        const val SKIP_NORMAL = -0.3

        // Dwell classification thresholds (ms).
        const val FAST_SKIP_MS = 2000L
        const val DWELL_MS = 8000L
        const val DWELL_NOISE_FLOOR_MS = 100L
    }
}
