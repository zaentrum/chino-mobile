package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.ChinoApi
import cloud.nalet.chino.mobile.data.api.PlayInfo
import cloud.nalet.chino.mobile.data.auth.withoutStreamToken
import cloud.nalet.chino.mobile.ui.player.AUTO_QUALITY
import cloud.nalet.chino.mobile.ui.player.buildMasterUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** What a title's download would be, as its control shows it before the
 *  download starts. */
sealed interface DownloadOffer {
    /** It can be downloaded; [bytes] is what it takes at the chosen
     *  quality, null where the server's playlists do not say. */
    data class Available(val bytes: Long?) : DownloadOffer

    /** It plays only as a stream for now. */
    data object StreamOnly : DownloadOffer

    /** The server could not be asked: [reason]. */
    data class Unknown(val reason: DownloadFailure) : DownloadOffer
}

/** A download worked out ([DownloadPlanner.plan]), or why it cannot start. */
sealed interface PlanResult {
    data class Ready(val plan: DownloadPlan) : PlanResult
    data class NotPossible(val reason: DownloadFailure) : PlanResult
}

/**
 * Works out a download before it starts, from the server: whether the title
 * can come along ([downloadability]), the variant the download quality
 * picks, the audio and subtitles the viewer's languages pick, and what it
 * comes to on the phone, from the server's master playlist. What it read of
 * a title - its play info and master - is kept for [CACHE_MS], so the
 * control's size and the download that follows ask once.
 *
 * [caps] is the master the download asks for: the device's decoders, the
 * stereo audio group (a phone plays its download on its own speaker).
 */
class DownloadPlanner(
    private val api: ChinoApi,
    private val streamToken: suspend () -> String,
    private val apiBase: () -> String,
    private val caps: () -> String,
    private val now: () -> Long = { currentTimeMillis() },
) {
    private class Probe(val info: PlayInfo, val masterText: String?, val masterUrl: String?, val at: Long)

    private val probes = HashMap<String, Probe>()
    private val lock = Mutex()

    /** The reads under way, one per title: a season's control and its rows
     *  ask for the same episodes at once, and share each answer. */
    private val inFlight = HashMap<String, Deferred<Probe>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Three titles' requests at a time: a season's controls ask for all of
     *  theirs at once. */
    private val gate = Semaphore(3)

    /** What [itemId]'s control offers at [quality]: its size, or why it
     *  cannot come along. */
    suspend fun offer(itemId: String, quality: Int, durationMs: Long?): DownloadOffer = try {
        val probe = probe(itemId)
        when (downloadability(probe.info)) {
            is Downloadability.Yes -> {
                val master = probe.masterText?.let(::parseHlsMaster)
                val variant = master?.let { pickVariant(it.variants, quality) }
                val bytes = variant?.let {
                    estimateBytes(it, audioCount = 1, durationMs = probe.info.durationMs ?: durationMs)
                }
                DownloadOffer.Available(bytes)
            }
            Downloadability.StreamOnly -> DownloadOffer.StreamOnly
            Downloadability.Unknown -> DownloadOffer.Unknown(DownloadFailure.NoAnswer)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DownloadOffer.Unknown(failureOf(e))
    }

    /** Everything a download of [itemId] at [quality] keeps, for the
     *  viewer's [audioPref] and [subtitlePref]. */
    suspend fun plan(itemId: String, quality: Int, audioPref: String?, subtitlePref: String?): PlanResult = try {
        // Whether it can come along first: a title that only streams asks
        // for nothing more. What the control's size was read from serves
        // (the download reads the master again itself).
        val probe = probe(itemId)
        val masterText = probe.masterText
        val masterUrl = probe.masterUrl
        when {
            downloadability(probe.info) !is Downloadability.Yes -> PlanResult.NotPossible(DownloadFailure.StreamOnly)
            masterText == null || masterUrl == null -> PlanResult.NotPossible(DownloadFailure.Stopped)
            else -> coroutineScope {
                val itemAsk = async { api.getItem(itemId) }
                val subsAsk = async { runCatching { api.itemSubtitles(itemId).subtitles }.getOrDefault(emptyList()) }
                val segsAsk = async { runCatching { api.itemSegments(itemId).segments }.getOrDefault(emptyList()) }
                val savedAsk = async { runCatching { api.getProgress(itemId).positionSec }.getOrNull() }
                val master = parseHlsMaster(masterText)
                val variant = pickVariant(master.variants, quality)
                    ?: return@coroutineScope PlanResult.NotPossible(DownloadFailure.Stopped).also {
                        itemAsk.cancel()
                        subsAsk.cancel()
                        segsAsk.cancel()
                        savedAsk.cancel()
                    }
                val audios = pickAudios(master.audiosOf(variant), audioPref)
                val item = itemAsk.await()
                val seriesTitle = item.parentId?.let { runCatching { api.getItem(it).title }.getOrNull() }
                val subtitles = pickSubtitles(subsAsk.await(), audios.map { it.language }, audioPref, subtitlePref)
                PlanResult.Ready(
                    DownloadPlan(
                        item = item,
                        seriesTitle = seriesTitle,
                        info = probe.info,
                        segments = segsAsk.await(),
                        masterUrl = masterUrl,
                        masterText = masterText,
                        master = master,
                        variant = variant,
                        audios = audios,
                        subtitles = subtitles,
                        quality = quality,
                        estimatedBytes = estimateBytes(variant, audios.size, probe.info.durationMs ?: item.durationMs),
                        server = apiBase(),
                        savedPositionSec = savedAsk.await(),
                    ),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlanResult.NotPossible(failureOf(e))
    }

    /** The bit rate an hour of each quality takes, from up to [SAMPLE] of
     *  the server's packaged titles - the middle one of what their masters
     *  say for each size class - tallest first. Empty where none answered. */
    suspend fun qualities(): List<DownloadQuality> {
        val ids = runCatching { api.packagedIds().ids }.getOrDefault(emptyList()).take(SAMPLE)
        val byClass = HashMap<Int, MutableList<Long>>()
        coroutineScope {
            ids.map { id -> async { runCatching { probe(id).masterText }.getOrNull() } }
                .forEach { ask ->
                    val text = ask.await() ?: return@forEach
                    for (v in parseHlsMaster(text).variants) {
                        val c = sizeClass(v.width, v.height) ?: continue
                        if (v.sizeBitrate > 0) byClass.getOrPut(c) { ArrayList() } += v.sizeBitrate
                    }
                }
        }
        return byClass.entries
            .sortedByDescending { it.key }
            .map { (c, rates) -> DownloadQuality(c, bytesPerHour(rates.sorted()[rates.size / 2])) }
    }

    /** Forgets what was read of [itemId]: it is asked for again. */
    suspend fun forget(itemId: String) {
        lock.withLock { probes.remove(itemId) }
    }

    /** The title's play info and - for one that can come along - its master,
     *  as this device is served them; kept for [CACHE_MS], or read again
     *  when [fresh]. */
    private suspend fun probe(itemId: String, fresh: Boolean = false): Probe {
        val ask = lock.withLock {
            if (!fresh) probes[itemId]?.takeIf { now() - it.at < CACHE_MS }?.let { return it }
            inFlight[itemId]?.takeIf { it.isActive } ?: scope.async { read(itemId) }.also { inFlight[itemId] = it }
        }
        return try {
            ask.await()
        } finally {
            lock.withLock { if (inFlight[itemId] === ask && !ask.isActive) inFlight.remove(itemId) }
        }
    }

    private suspend fun read(itemId: String): Probe {
        return gate.withPermit {
            val caps = caps()
            // Not the player's budget: a server whose storage is slow can
            // take many seconds over a title's play info, and nothing on the
            // screen waits for it but the size.
            val info = api.playInfo(itemId, caps = caps.ifEmpty { null }, timeoutMs = PLAY_INFO_WAIT_MS)
            var text: String? = null
            var url: String? = null
            if (downloadability(info) is Downloadability.Yes) {
                // Auto: the whole ladder the server serves this device, not
                // one rung of it - the download picks its rendition itself.
                val signed = buildMasterUrl(apiBase(), itemId, streamToken(), AUTO_QUALITY, caps)
                text = api.hlsMaster(signed)
                url = withoutStreamToken(signed)
            }
            Probe(info, text, url, now()).also { p -> lock.withLock { probes[itemId] = p } }
        }
    }

    companion object {
        /** How long what was read of a title is kept. */
        const val CACHE_MS = 30L * 60 * 1000

        /** How many packaged titles' masters Settings reads for its sizes. */
        const val SAMPLE = 6

        /** How long a title's play info is waited for. */
        const val PLAY_INFO_WAIT_MS = 30_000L
    }
}

/** Why a request for a download failed, as the control says it: a refusal
 *  by its status, anything else as no answer. */
fun failureOf(e: Throwable): DownloadFailure = when (e) {
    is ApiStatusException -> when (e.status) {
        404, 410 -> DownloadFailure.Gone
        401, 403 -> DownloadFailure.Refused
        in 500..599 -> DownloadFailure.ServerProblem
        else -> DownloadFailure.Refused
    }
    else -> DownloadFailure.NoAnswer
}
