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

    /** It plays only as a stream. */
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
 * picks, the audio the viewer's language picks, every text subtitle, and
 * what it comes to on the phone, from the server's master playlist - the
 * size its offer says before ([offer]), the same as the plan's. What it read of
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

    /** What [itemId]'s control offers at [quality] for the viewer's
     *  [audioPref]: the size of exactly what the download would keep - the
     *  variant and the audio renditions it picks, as [plan] picks them - or
     *  why it cannot come along. */
    suspend fun offer(itemId: String, quality: Int, durationMs: Long?, audioPref: String? = null): DownloadOffer = try {
        val probe = probe(itemId)
        when (downloadability(probe.info)) {
            is Downloadability.Yes -> {
                val master = probe.masterText?.let(::parseHlsMaster)
                val variant = master?.let { pickVariant(it.variants, quality) }
                val bytes = variant?.let {
                    val audios = pickAudios(master.audiosOf(it), audioPref)
                    estimateBytes(it, audioCount = audios.size.coerceAtLeast(1), durationMs = probe.info.durationMs ?: durationMs)
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
     *  viewer's [audioPref]: the variant, the audio, every text subtitle. */
    suspend fun plan(itemId: String, quality: Int, audioPref: String?): PlanResult = try {
        // Whether it can come along first: a title that only streams asks
        // for nothing more. Then its master as the server serves it now -
        // not the one the control's size was read from, up to half an hour
        // old: the download picks its renditions by their place in it, and a
        // ladder changed since would fetch another one than the size said.
        val probe = probe(itemId)
        val fresh = if (downloadability(probe.info) is Downloadability.Yes) freshMaster(itemId) else null
        val masterText = fresh?.first
        val masterUrl = fresh?.second
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
                // An episode's series by its title: the downloads screen's
                // heading for it, read now - the phone has no other source.
                val seriesTitle = item.parentId?.let { api.getItem(it).title }
                val subtitles = pickSubtitles(subsAsk.await())
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

    /** What an hour of each quality takes, from up to [SAMPLE] of the
     *  server's packaged titles ([qualityFigures]), tallest first. Empty
     *  where none answered. */
    suspend fun qualities(audioPref: String?): List<DownloadQuality> {
        val ids = runCatching { api.packagedIds().ids }.getOrDefault(emptyList()).take(SAMPLE)
        val masters = coroutineScope {
            ids.map { id -> async { runCatching { probe(id).masterText }.getOrNull() } }
                .mapNotNull { it.await()?.let(::parseHlsMaster) }
        }
        return qualityFigures(masters, audioPref)
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

    /** [itemId]'s master as the server serves this device now, at Auto:
     *  its text, and its URL without the stream token. */
    private suspend fun freshMaster(itemId: String): Pair<String, String> = gate.withPermit {
        val caps = caps()
        val signed = buildMasterUrl(apiBase(), itemId, streamToken(), AUTO_QUALITY, caps)
        api.hlsMaster(signed) to withoutStreamToken(signed)
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
        const val SAMPLE = 12

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
