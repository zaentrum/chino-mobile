package cloud.nalet.chino.mobile.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import cloud.nalet.chino.mobile.currentTimeMillis
import cloud.nalet.chino.mobile.data.AppContainer
import cloud.nalet.chino.mobile.data.api.ApiStatusException
import cloud.nalet.chino.mobile.data.api.artworkUrl
import cloud.nalet.chino.mobile.data.downloads.AvailabilityChange
import cloud.nalet.chino.mobile.data.downloads.AvailabilityCheck
import cloud.nalet.chino.mobile.data.downloads.BookChange
import cloud.nalet.chino.mobile.data.downloads.DeletedNotice
import cloud.nalet.chino.mobile.data.downloads.DownloadEntry
import cloud.nalet.chino.mobile.data.downloads.DownloadFailure
import cloud.nalet.chino.mobile.data.downloads.DownloadPlan
import cloud.nalet.chino.mobile.data.downloads.DownloadPreferences
import cloud.nalet.chino.mobile.data.downloads.DownloadedSubtitle
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.Downloads
import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import cloud.nalet.chino.mobile.data.downloads.ManagerState
import cloud.nalet.chino.mobile.data.downloads.Owner
import cloud.nalet.chino.mobile.data.downloads.PlanResult
import cloud.nalet.chino.mobile.data.downloads.Reach
import cloud.nalet.chino.mobile.data.downloads.SIDECAR_TRIES
import cloud.nalet.chino.mobile.data.downloads.SidecarMiss
import cloud.nalet.chino.mobile.data.downloads.sidecarMiss
import cloud.nalet.chino.mobile.data.downloads.STOP_NONE
import cloud.nalet.chino.mobile.data.downloads.StorageUse
import cloud.nalet.chino.mobile.data.downloads.applyAvailability
import cloud.nalet.chino.mobile.data.downloads.askAvailable
import cloud.nalet.chino.mobile.data.downloads.availabilityAsked
import cloud.nalet.chino.mobile.data.downloads.checkDue
import cloud.nalet.chino.mobile.data.downloads.claim
import cloud.nalet.chino.mobile.data.downloads.downloadKey
import cloud.nalet.chino.mobile.data.downloads.downloadStatus
import cloud.nalet.chino.mobile.data.downloads.dropServer
import cloud.nalet.chino.mobile.data.downloads.failureOf
import cloud.nalet.chino.mobile.data.downloads.notificationTitle
import cloud.nalet.chino.mobile.data.downloads.qualityName
import cloud.nalet.chino.mobile.data.downloads.resolveUrl
import cloud.nalet.chino.mobile.data.downloads.samePiece
import cloud.nalet.chino.mobile.data.downloads.stopReasonFor
import cloud.nalet.chino.mobile.data.downloads.titleOf
import cloud.nalet.chino.mobile.data.downloads.unclaim
import cloud.nalet.chino.mobile.data.downloads.unclaimAll
import cloud.nalet.chino.mobile.data.downloads.visibleTo
import cloud.nalet.chino.mobile.data.model.episodeCode
import cloud.nalet.chino.mobile.feedback.CurrentActivity
import cloud.nalet.chino.mobile.ui.player.sidecarForced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * [Downloads] on Android, over the [DownloadEngine], for the account signed
 * in now on the connected server (the active owner). A title is one Media3
 * download per server, whoever claims it (DownloadBook): a second account
 * that downloads it adds its claim, and its media goes with the last one.
 * Only the active owner's downloads run - the others wait, stopped, and
 * fetch nothing - and only its titles show, play, fetch their subtitles and
 * artwork and are checked with the server. The server decides what each
 * account may still watch (POST /me/available): what it no longer may is
 * deleted, and said once. A plan becomes a Media3 download request of the
 * variant and audio renditions it picked (their HlsMultivariantPlaylist
 * stream keys); the title's folder gets its metadata at once, and its
 * sidecar subtitles and artwork once the media starts coming.
 */
internal class AndroidDownloads(
    private val engine: DownloadEngine,
    private val container: AppContainer,
) : Downloads {
    private val context = engine.context
    private val extrasLock = Mutex()
    private val extrasRunning = HashSet<String>()

    /** The titles the server took off the phone, by owner ("server|subject"),
     *  until its account has seen them (notices.json). */
    private val notices = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val noticesFile = File(context.filesDir, "downloads/notices.json")
    private val noticesJson = Json { ignoreUnknownKeys = true }

    /** When the active owner's downloads were last checked with the server. */
    @Volatile
    private var checkedAtMs = 0L

    /** Whether this is the app's graph's downloads: after a change of server
     *  the graph is built anew, and the old one's - its scope cancelled with
     *  it - sets nothing of the manager's any more. */
    private val current: Boolean
        get() = runCatching { (context as? AppContainerHost)?.container }.getOrNull().let { it == null || it === container }

    init {
        engine.onLowSpace = { if (current) stopForSpace() }
        container.appScope.launch(Dispatchers.IO) { loadNotices() }
        container.appScope.launch {
            engine.titles.awaitReady()
            engine.indexRead.first { it }
            reconcile()
            // Which downloads may run: the active owner's, while the server
            // offers downloads - again whenever any of it changes.
            combine(container.activeOwner, container.serverReach.features, engine.titles.titles, engine.downloads) { _, _, _, _ -> }
                .collect { applyStopReasons() }
        }
        container.appScope.launch {
            engine.titles.awaitReady()
            engine.indexRead.first { it }
            // The server offers downloads again: the ones its switch stopped
            // start over, for the account signed in - each one's own retry.
            combine(container.serverReach.features.map { it?.downloads == true }.distinctUntilChanged(), container.activeOwner) { on, owner -> on to owner }
                .collect { (on, owner) ->
                    if (!on || owner == null || !current) return@collect
                    visibleTo(engine.titles.titles.value, owner)
                        .filter { it.failure == DownloadFailure.ServerOff }
                        .forEach { retry(it.itemId) }
                }
        }
        container.appScope.launch {
            engine.titles.awaitReady()
            engine.indexRead.first { it }
            // Titles whose media came in while their extras could not - the
            // process ended first, or the server did not answer: for the
            // owner signed in now, whenever the server answers.
            combine(container.serverReach.state, container.activeOwner) { reach, owner -> reach to owner }
                .distinctUntilChanged()
                .collect { (reach, owner) -> if (reach != Reach.Unreachable && owner != null) catchUpExtras(owner) }
        }
        container.appScope.launch {
            engine.titles.awaitReady()
            // As the server answers - at the start, back after it did not -
            // and for each account signed in: what it may still watch.
            combine(container.serverReach.state, container.activeOwner) { reach, owner -> reach to owner }
                .distinctUntilChanged()
                .collect { (reach, owner) -> if (reach == Reach.Reachable && owner != null) checkQuietly(force = true) }
        }
    }

    /** A download changed ([DownloadEngine]'s listener): its subtitles and
     *  artwork once its media starts coming. One that failed keeps nothing:
     *  what it fetched goes, and "try again" works it out anew. One the
     *  server answered 404 or 410 for asks whether the title is still the
     *  account's (checkAvailability): it goes if not, and is a download that
     *  stopped before it was done once the server says it is. */
    internal fun changed(d: Download) {
        if (!current) return
        if (d.state == Download.STATE_DOWNLOADING || d.state == Download.STATE_COMPLETED) fetchExtrasLater(d.request.id)
        if (d.state == Download.STATE_FAILED) {
            val key = d.request.id
            removeMedia(listOf(key))
            val owner = container.activeOwner.value ?: return
            val title = engine.titles[key]?.takeIf { it.ownedBy(owner) } ?: return
            if (title.failure != DownloadFailure.Gone) return
            container.appScope.launch { checkAvailability(listOf(title.itemId)) }
        }
    }

    private data class Inputs(
        val notMet: Int,
        val prefs: DownloadPreferences,
        val owner: Owner?,
        val reach: Reach,
    )

    override val entries: StateFlow<List<DownloadEntry>> = combine(
        engine.titles.titles,
        engine.downloads,
        engine.onPhone,
        engine.starting,
        combine(engine.notMet, engine.preferences, container.activeOwner, container.serverReach.state) { n, p, o, r -> Inputs(n, p, o, r) },
    ) { titles, downloads, onPhone, starting, inputs ->
        val owner = inputs.owner ?: return@combine emptyList()
        val waits = inputs.notMet and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0
        val now = currentTimeMillis()
        visibleTo(titles, owner)
            .mapNotNull { title ->
                val claim = title.claimOf(owner.subject) ?: return@mapNotNull null
                val d = downloads[title.key]
                // The engine's one figure: what the line, "delete all" and
                // the questions sum, from the bytes.
                val bytes = onPhone[title.key] ?: 0L
                DownloadEntry(
                    title = title,
                    status = downloadStatus(
                        state = d?.let { managerState(it.state) },
                        percent = d?.percent ?: 0f,
                        // A title's own size, as its offer said it - the
                        // server's, the same number before and after
                        // (docs/chino downloads); measured where it said none.
                        bytes = title.estimatedBytes?.takeIf { it > 0 } ?: bytes,
                        waitsForNetwork = waits,
                        wifiOnly = inputs.prefs.wifiOnly,
                        extrasDone = title.extrasDone,
                        failure = title.failure,
                        starting = title.key in starting,
                        stopReason = d?.stopReason ?: STOP_NONE,
                        // Only while the server cannot be reached: with it,
                        // the check runs by itself.
                        checkNeeded = inputs.reach == Reach.Unreachable && checkDue(claim, now),
                    ),
                    onPhoneBytes = bytes,
                    claim = claim,
                    mediaBytes = mediaBytesOf(d),
                )
            }
            .sortedByDescending { it.addedAtMs }
    }.stateIn(container.appScope, SharingStarted.Eagerly, emptyList())

    override val preferences: StateFlow<DownloadPreferences> get() = engine.preferences

    override val notice: StateFlow<DeletedNotice?> = combine(notices, container.activeOwner) { all, owner ->
        owner?.let { all[noticeKey(it)] }?.takeIf { it.isNotEmpty() }?.let { DeletedNotice(it) }
    }.stateIn(container.appScope, SharingStarted.Eagerly, null)

    override suspend fun setQuality(sizeClass: Int) = engine.setQuality(sizeClass)

    override suspend fun setWifiOnly(wifiOnly: Boolean) = engine.setWifiOnly(wifiOnly)

    override suspend fun start(plan: DownloadPlan) = withContext(Dispatchers.IO) {
        val owner = container.activeOwner.value ?: return@withContext
        if (plan.server != owner.server) return@withContext
        engine.titles.awaitReady()
        val key = downloadKey(plan.server, plan.item.id)
        val now = currentTimeMillis()
        val fresh = DownloadedTitle(
            itemId = plan.item.id,
            item = plan.item,
            seriesTitle = plan.seriesTitle,
            info = plan.info,
            segments = plan.segments,
            quality = qualityName(plan.quality),
            estimatedBytes = plan.estimatedBytes,
            audioLanguages = plan.audios.map { it.language },
            subtitles = subtitleFiles(plan),
            server = plan.server,
        )
        // Another account's copy, there or on its way: this account's too
        // now, and on its way for it (the stop reasons let it run).
        val existing = engine.downloads.value[key]
        if (engine.titles[key] != null && existing != null && existing.state != Download.STATE_FAILED && existing.state != Download.STATE_REMOVING) {
            engine.titles.change { claim(it, fresh, owner, now, plan.savedPositionSec) }
            applyStopReasons()
            return@withContext
        }
        // Not with less room than it takes - with what the downloads queued
        // before it still take - and a margin: a season that does not fit
        // says so for the episodes that do not, at once, not halfway.
        if (engine.freeBytes() < (plan.estimatedBytes ?: 0L) + pendingBytes(except = key) + SPACE_MARGIN_BYTES) {
            engine.titles.change { claim(it, fresh.copy(failure = DownloadFailure.NoSpace), owner, now, plan.savedPositionSec, replace = true) }
            fetchArtwork(key)
            return@withContext
        }
        val keys = streamKeysFor(plan)
        if (keys == null) {
            engine.titles.change { claim(it, fresh.copy(failure = DownloadFailure.Stopped), owner, now, plan.savedPositionSec, replace = true) }
            fetchArtwork(key)
            return@withContext
        }
        engine.titles.change { claim(it, fresh, owner, now, plan.savedPositionSec, replace = true) }
        engine.starting.value = engine.starting.value + key
        val request = DownloadRequest.Builder(key, Uri.parse(plan.masterUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setStreamKeys(keys)
            // For the notification: a film by its title, an episode with its
            // series and code.
            .setData(
                notificationTitle(
                    plan.item.title,
                    plan.seriesTitle.takeIf { fresh.isEpisode },
                    episodeCode(plan.item.seasonNumber, plan.item.episodeNumber, plan.item.episodeEnd).takeIf { fresh.isEpisode },
                ).encodeToByteArray(),
            )
            .build()
        if (!hand(request)) {
            engine.starting.value = engine.starting.value - key
            engine.titles.update(key) { it.copy(failure = DownloadFailure.Stopped) }
        }
        // Its poster and backdrop now, while the page that asked shows them:
        // the downloads screen has its picture before the media comes.
        fetchArtwork(key)
    }

    /** The plan's sidecar subtitles as files of the title's folder: each
     *  its own file name, however alike their ids. */
    private fun subtitleFiles(plan: DownloadPlan): List<DownloadedSubtitle> {
        val names = HashSet<String>()
        return plan.subtitles.map { s ->
            val base = s.id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "sub" }
            var name = "$base.vtt"
            var n = 2
            while (!names.add(name)) name = "$base-${n++}.vtt"
            DownloadedSubtitle(
                id = s.id,
                lang = s.lang,
                label = s.label,
                format = s.format,
                forced = sidecarForced(s),
                file = name,
                source = s.url.ifBlank { "/api/v1/play/subs/${s.id}.vtt" },
            )
        }
    }

    /**
     * Hands [request] to the manager. While the downloads wait for a
     * connection it takes it directly - no service in the foreground for a
     * download that waits. Else the download service takes it; where the
     * service cannot be started in the foreground now (the app went to the
     * background while the download was worked out, Android 12 and later)
     * the manager does, and the service picks it up as the app comes to the
     * front. False when neither took it.
     */
    private fun hand(request: DownloadRequest): Boolean {
        if (engine.waitingForConnection) return engine.addDirectly(request, STOP_NONE)
        val sent = runCatching { DownloadService.sendAddDownload(context, ChinoDownloadService::class.java, request, true) }
        return sent.isSuccess || engine.addDirectly(request, STOP_NONE)
    }

    /** What the downloads queued or on their way still take, as the
     *  server's playlists said, but [except]'s. */
    private fun pendingBytes(except: String): Long {
        val downloads = engine.downloads.value
        val starting = engine.starting.value
        return engine.titles.titles.value.values
            .filter { it.key != except && it.failure == null }
            .sumOf { t ->
                val d = downloads[t.key]
                val coming = if (d == null) {
                    t.key in starting
                } else {
                    d.state != Download.STATE_COMPLETED && d.state != Download.STATE_FAILED && d.state != Download.STATE_REMOVING
                }
                if (coming) ((t.estimatedBytes ?: 0L) - (d?.bytes ?: 0L)).coerceAtLeast(0L) else 0L
            }
    }

    override fun cancel(itemId: String) = delete(itemId)

    override fun delete(itemId: String) {
        val owner = container.activeOwner.value ?: return
        val key = downloadKey(owner.server, itemId)
        engine.starting.value = engine.starting.value - key
        removeMedia(engine.titles.change { unclaim(it, key, owner.subject) }.removed)
    }

    override fun deleteAll() {
        val owner = container.activeOwner.value ?: return
        removeMedia(engine.titles.change { unclaimAll(it, owner) }.removed)
    }

    override suspend fun dropOwner(owner: Owner) {
        engine.titles.awaitReady()
        removeMedia(engine.titles.change { unclaimAll(it, owner) }.removed)
        notices.value = notices.value - noticeKey(owner)
        withContext(Dispatchers.IO) { saveNotices() }
    }

    override suspend fun dropServer(server: String) {
        engine.titles.awaitReady()
        removeMedia(engine.titles.change { dropServer(it, server) }.removed)
        notices.value = notices.value.filterKeys { !it.startsWith("$server|") }
        withContext(Dispatchers.IO) { saveNotices() }
    }

    /** The media of [keys] goes - the manager deletes it, no service for it. */
    private fun removeMedia(keys: List<String>) {
        if (keys.isEmpty()) return
        engine.starting.value = engine.starting.value - keys.toSet()
        engine.onMain { for (key in keys) runCatching { engine.manager.removeDownload(key) } }
    }

    override fun retry(itemId: String) {
        val owner = container.activeOwner.value ?: return
        val key = downloadKey(owner.server, itemId)
        val title = engine.titles[key]?.takeIf { it.ownedBy(owner) } ?: return
        // The server's switch holds: no download while it says none.
        if (container.serverReach.features.value?.downloads == false) {
            engine.titles.update(key) { it.copy(failure = DownloadFailure.ServerOff) }
            return
        }
        val d = engine.downloads.value[key]
        when {
            // Its media is in, the rest of it did not come: that, again.
            d != null && d.state == Download.STATE_COMPLETED -> {
                engine.titles.update(key) { it.copy(failure = null) }
                fetchExtrasLater(key)
            }
            // Stopped, its media kept (one stopped for room before a failed
            // download freed what it had): it goes on where there is room now.
            d != null && d.state != Download.STATE_FAILED && d.state != Download.STATE_REMOVING -> {
                val left = ((title.estimatedBytes ?: 0L) - d.bytes).coerceAtLeast(0L)
                if (title.failure != DownloadFailure.NoSpace || engine.freeBytes() >= left + SPACE_MARGIN_BYTES) {
                    engine.titles.update(key) { it.copy(failure = null) }
                    applyStopReasons()
                    engine.resumeIfPending()
                }
            }
            else -> {
                // Nothing of it kept - it failed, it never reached the
                // manager (no room, a master it could not read): worked out
                // again, as it was asked for - queued meanwhile, not failed.
                engine.starting.value = engine.starting.value + key
                engine.titles.update(key) { it.copy(failure = null) }
                container.appScope.launch {
                    val failure = try {
                        val settings = container.settings.flow.first()
                        val r = container.downloadPlanner.plan(itemId, engine.preferences.value.quality, settings.preferredAudioLang)
                        when {
                            // Deleted while it was worked out: it stays deleted.
                            engine.titles[key] == null -> null
                            else -> when (r) {
                                // start() hands it to the manager, or records why not.
                                is PlanResult.Ready -> {
                                    start(r.plan)
                                    null
                                }
                                is PlanResult.NotPossible -> r.reason
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failureOf(e)
                    }
                    engine.starting.value = engine.starting.value - key
                    if (failure != null) engine.titles.update(key) { it.copy(failure = failure) }
                }
            }
        }
    }

    override suspend fun storage(): StorageUse = withContext(Dispatchers.IO) {
        engine.titles.awaitReady()
        // The account's titles as the engine counts them: the sum "delete
        // all" makes, of the same titles.
        val used = summaryOfTitles(visibleTo(engine.titles.titles.value, container.activeOwner.value)).bytes
        StorageUse(usedBytes = used, freeBytes = engine.freeBytes())
    }

    override fun isOnPhone(owner: Owner, itemId: String): Boolean = titleOf(engine.titles.titles.value, owner, itemId) != null

    override fun summaryOf(owner: Owner): DownloadsSummary = summaryOfTitles(visibleTo(engine.titles.titles.value, owner))

    override fun summaryOfServer(server: String): DownloadsSummary =
        summaryOfTitles(engine.titles.titles.value.values.filter { it.server == server })

    /** What of [titles] is on the phone, as the downloads screen counts it
     *  ([onThePhone]): the titles with some of their media there - a queued
     *  one with only its artwork doesn't count - and, of the same titles,
     *  what they take on the phone now: the engine's one figure
     *  ([DownloadEngine.onPhone]), never the sizes the server said. */
    private fun summaryOfTitles(titles: List<DownloadedTitle>): DownloadsSummary {
        val onPhone = engine.onPhone.value
        val downloads = engine.downloads.value
        val there = titles.filter { countsAsOnThePhone(downloads[it.key]) }
        return DownloadsSummary(there.size, there.sumOf { onPhone[it.key] ?: 0L })
    }

    override fun noticeSeen(titles: List<String>) {
        val owner = container.activeOwner.value ?: return
        val key = noticeKey(owner)
        val before = notices.value[key] ?: return
        // Only what was shown: a title deleted while the banner was up is
        // still to be told.
        val left = before - titles.toSet()
        notices.value = if (left.isEmpty()) notices.value - key else notices.value + (key to left)
        container.appScope.launch(Dispatchers.IO) { saveNotices() }
    }

    /**
     * Asks the server about the downloads of the account signed in now:
     * [itemIds] of them, or all of them ([availabilityAsked]) - the
     * play-time check and a subtitle the server no longer has ask about
     * their own title alone, one quick request even on a server asked title
     * by title, inside the seconds the play-time check waits; the background
     * check asks about all. A title goes after two answers
     * that it is not the account's ([applyAvailability]): an hour apart where
     * the answer is the library's word - it still answers for others of the
     * account's, or says the library holds titles for it - else a day apart
     * (a library imported anew or reset leaves out everything for a while),
     * so an account's only download, or all of them, goes too. What the
     * answer took or marked is in the result; a title it answered for again
     * that had stopped as gone (404, 410) is a download that stopped before
     * it was done, which "try again" works out anew.
     */
    override suspend fun checkAvailability(itemIds: List<String>?): AvailabilityCheck {
        val owner = container.activeOwner.value ?: return AvailabilityCheck.NotAnswered
        if (owner.server != container.serverBase) return AvailabilityCheck.NotAnswered
        engine.titles.awaitReady()
        val ids = availabilityAsked(visibleTo(engine.titles.titles.value, owner), itemIds)
        if (ids.isEmpty()) return AvailabilityCheck.Done(emptyList())
        // With the owner's own bearer, and only while it is signed in.
        val bearer = container.tokenManager.validAccessToken()
            ?.takeIf { container.accountStore.snapshotBlocking().activeAccount?.id == owner.subject }
            ?: return AvailabilityCheck.NotAnswered
        // No answer, a refusal of the request, a server without the route:
        // none of them says a title is not the account's.
        val answer = withTimeoutOrNull(CHECK_WAIT_MS) { askAvailable(container.chinoApi, ids, bearer) }
            ?: return AvailabilityCheck.NotAnswered
        // The account changed meanwhile: the answer is not the one signed in now's.
        if (container.activeOwner.value != owner) return AvailabilityCheck.NotAnswered
        var result: AvailabilityChange? = null
        val change = engine.titles.change { titles ->
            val c = applyAvailability(titles, owner, ids, answer.ids, currentTimeMillis(), libraryTitles = answer.libraryTitles)
            result = c
            c.change
        }
        val c = result ?: return AvailabilityCheck.NotAnswered
        removeMedia(change.removed)
        // Answered for again: one that stopped as gone did not go.
        for (id in answer.ids) {
            val key = downloadKey(owner.server, id)
            engine.titles.update(key) { if (it.failure == DownloadFailure.Gone) it.copy(failure = DownloadFailure.Stopped) else it }
        }
        if (c.deleted.isNotEmpty()) container.downloadsTaken.tryEmit(c.deleted.map { it.itemId })
        checkedAtMs = currentTimeMillis()
        if (c.deleted.isNotEmpty()) Log.i(TAG, "${c.deleted.size} downloads are no longer the account's, deleted")
        return AvailabilityCheck.Done(c.deleted, c.marked)
    }

    /** The check in the background - at the start, back at the front, the
     *  server back: what it deleted is named once on the downloads screen.
     *  At most every [RECHECK_MS] unless [force]d. */
    internal suspend fun checkQuietly(force: Boolean = false) {
        if (!force && currentTimeMillis() - checkedAtMs < RECHECK_MS) return
        val owner = container.activeOwner.value ?: return
        val result = checkAvailability() as? AvailabilityCheck.Done ?: return
        if (result.deleted.isEmpty()) return
        val names = result.deleted.map { t ->
            notificationTitle(
                t.item.title,
                t.seriesTitle.takeIf { t.isEpisode },
                episodeCode(t.item.seasonNumber, t.item.episodeNumber, t.item.episodeEnd).takeIf { t.isEpisode },
            )
        }
        val key = noticeKey(owner)
        notices.value = notices.value + (key to (notices.value[key].orEmpty() + names).distinct())
        withContext(Dispatchers.IO) { saveNotices() }
    }

    override fun askToNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || asked) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val activity = CurrentActivity.get() ?: return
        asked = true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
    }

    @Volatile
    private var asked = false

    override fun fileUrl(itemId: String, file: String?): String? {
        val owner = container.activeOwner.value ?: return null
        val f = File(engine.titles.dirOf(downloadKey(owner.server, itemId)), file ?: return null)
        return if (f.isFile) Uri.fromFile(f).toString() else null
    }

    /**
     * Which downloads may run now ([stopReasonFor]): the active owner's,
     * while the server offers downloads, and not one stopped for room. On
     * the manager's thread; only a reason that changed is set.
     */
    private fun applyStopReasons() = engine.onMain {
        if (!engine.indexRead.value || !current) return@onMain
        val owner = container.activeOwner.value
        val offers = container.serverReach.features.value?.downloads != false
        val titles = engine.titles.titles.value
        val stopped = ArrayList<String>()
        for ((key, d) in engine.downloads.value) {
            if (d.state == Download.STATE_COMPLETED || d.state == Download.STATE_FAILED || d.state == Download.STATE_REMOVING) continue
            // The server turned downloads off: the connected server's on
            // their way stop, and what they fetched goes - they start over
            // once it offers them again.
            val title = titles[key]
            if (!offers && title != null && title.server == container.serverBase) {
                stopped += key
                continue
            }
            val want = stopReasonFor(title, owner, offers)
            if (d.stopReason != want) runCatching { engine.manager.setStopReason(key, want) }
        }
        for (key in stopped) engine.titles.update(key) { it.copy(failure = DownloadFailure.ServerOff) }
        removeMedia(stopped)
    }

    /** The phone is about to run out of room while downloads come: every
     *  one on its way stops, "Not enough space", before the disk is full -
     *  and what each fetched goes, room again for what is there. */
    private fun stopForSpace() {
        val stopped = engine.downloads.value.filterValues {
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING
        }.keys.toList()
        for (key in stopped) engine.titles.update(key) { it.copy(failure = DownloadFailure.NoSpace) }
        removeMedia(stopped)
    }

    /**
     * The manager's index and the title folders, made to agree as the
     * process starts: a download whose title went (the process ended between
     * the two) goes; a title no account claims goes; a title that does not
     * read any more goes with its media - nothing could show or play it; a
     * title whose download never reached the manager, and that did not say
     * why, stopped.
     */
    private fun reconcile() {
        val titles = engine.titles.titles.value
        val downloads = engine.downloads.value
        val starting = engine.starting.value
        val orphanMedia = downloads.keys.filter { titles[it] == null && it !in starting }
        val unclaimed = titles.values.filter { it.owners.isEmpty() }.map { it.key }
        if (unclaimed.isNotEmpty()) engine.titles.change { BookChange(it - unclaimed.toSet(), unclaimed) }
        val unreadable = engine.titles.unreadable
        for (key in unreadable) engine.titles.removeUnreadable(key)
        removeMedia((orphanMedia + unclaimed + unreadable).distinct())
        for (t in titles.values) {
            if (t.owners.isNotEmpty() && downloads[t.key] == null && t.failure == null && t.key !in starting) {
                engine.titles.update(t.key) { it.copy(failure = DownloadFailure.Stopped) }
            }
        }
        engine.titles.retryFailed()
    }

    private fun catchUpExtras(owner: Owner) {
        val downloads = engine.downloads.value
        visibleTo(engine.titles.titles.value, owner)
            .filter { !it.extrasDone && downloads[it.key]?.state == Download.STATE_COMPLETED }
            .forEach {
                // The server answers again: a few tries more, should it not.
                noAnswerRetries.remove(it.key)
                fetchExtrasLater(it.key)
            }
    }

    private fun fetchExtrasLater(key: String, afterMs: Long = 0L) {
        container.appScope.launch(Dispatchers.IO) {
            if (afterMs > 0) delay(afterMs)
            fetchExtras(key)
        }
    }

    /** How often a title's extras were tried again without an answer, since
     *  they last came: a few times while the server seems there, else when
     *  it answers again ([catchUpExtras]). */
    private val noAnswerRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** A sidecar the server answered for, and not with the file: tried
     *  again [tries] times so far. */
    private class SidecarRefused(val tries: Int, cause: Exception) : Exception(cause)

    /**
     * The title's sidecar subtitles and artwork, beside its media - for its
     * owner signed in now on its server only: no other account's token goes
     * to it, and no token to another server; it waits for its owner. The
     * subtitles must come: a download without them is not on the phone yet
     * - "downloading 99 %" once its media is in, never a failed title that
     * keeps its media (docs/chino downloads: a failed download frees what it
     * had). A subtitle the server answers for and does not serve (a 5xx, a
     * refusal) is asked again a little later, and left out after
     * [SIDECAR_TRIES] such answers: one text track the server cannot give
     * never keeps the whole title off the phone, nor makes anyone download
     * it again for nothing. No answer at all counts nothing: the subtitles
     * come once the server answers again. A subtitle the server no longer
     * has is left out once the server says the title is still the account's;
     * the title goes where the server says it is not - and while it has said
     * so once only (the title marked), nothing is decided: the subtitles wait
     * for its next answer. Artwork is best effort: a title without a poster
     * shows its initials, as everywhere in the app. A failure recorded
     * meanwhile - out of room, the server's switch - is never undone here.
     */
    private suspend fun fetchExtras(key: String) {
        extrasLock.withLock {
            if (!extrasRunning.add(key)) return
        }
        try {
            val owner = container.activeOwner.value ?: return
            val title = engine.titles[key] ?: return
            if (!title.ownedBy(owner) || title.server != container.serverBase || title.extrasDone) return
            val token = container.streamTokenManager.valid()
            engine.titles.awaitWrites()
            val subsDir = engine.titles.subtitlesDir(key).apply { mkdirs() }
            val gone = mutableSetOf<String>()
            for (s in title.subtitles) {
                val file = File(subsDir, s.file)
                if (file.isFile && file.length() > 0) continue
                val url = artworkUrl(title.server, s.source ?: "/api/v1/play/subs/${s.id}.vtt", token)
                    ?: throw IOException("no subtitle url")
                try {
                    fetchTo(url, file)
                } catch (e: ApiStatusException) {
                    when (val miss = sidecarMiss(e.status, s.tries)) {
                        // The server answered, and not with the file: one
                        // try of a few, then the title goes on without it.
                        SidecarMiss.LeaveOut -> {
                            Log.i(TAG, "a subtitle the server answered ${e.status} for $SIDECAR_TRIES times is left out")
                            gone += s.id
                            continue
                        }
                        is SidecarMiss.TryAgain -> {
                            engine.titles.update(key) { t ->
                                t.copy(subtitles = t.subtitles.map { if (it.id == s.id) it.copy(tries = miss.tries) else it })
                            }
                            throw SidecarRefused(miss.tries, e)
                        }
                        else -> Unit
                    }
                    when (val c = checkAvailability(listOf(title.itemId))) {
                        is AvailabilityCheck.Done -> when {
                            // Not the account's any more: deleted, said where it shows.
                            c.deleted.any { it.key == key } -> return
                            // Said once: no answer yet, for the subtitle as for the title.
                            c.marked.any { it.key == key } -> return
                            else -> gone += s.id
                        }
                        AvailabilityCheck.NotAnswered -> throw e
                    }
                }
            }
            fetchArtwork(key, token)
            noAnswerRetries.remove(key)
            engine.titles.update(key) { t ->
                t.copy(subtitles = t.subtitles.filterNot { it.id in gone }, extrasDone = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SidecarRefused) {
            // Again a little later, a little later each time.
            fetchExtrasLater(key, afterMs = EXTRAS_RETRY_MS * e.tries)
        } catch (e: Exception) {
            // No answer: nothing counts against the subtitles. Asked again
            // when the server answers again (catchUpExtras) - and, while it
            // seems to answer, a few times a little later.
            val n = (noAnswerRetries[key] ?: 0) + 1
            if (n <= NO_ANSWER_RETRIES && container.serverReach.state.value != Reach.Unreachable) {
                noAnswerRetries[key] = n
                fetchExtrasLater(key, afterMs = EXTRAS_RETRY_MS * n)
            }
        } finally {
            extrasLock.withLock { extrasRunning.remove(key) }
            // Its folder as it is now, subtitles and artwork in it.
            engine.titles.remeasure(key)
        }
    }

    /**
     * The title's poster (an episode's: its series') and its backdrop (an
     * episode's: its still), best effort, for its owner signed in now on its
     * server: a title without one shows its initials, as everywhere in the
     * app. Kept once there.
     */
    private suspend fun fetchArtwork(key: String, token: String? = null) = withContext(Dispatchers.IO) {
        val owner = container.activeOwner.value ?: return@withContext
        val title = engine.titles[key] ?: return@withContext
        if (!title.ownedBy(owner) || title.server != container.serverBase) return@withContext
        if (title.poster != null && title.backdrop != null) return@withContext
        val t = token ?: runCatching { container.streamTokenManager.valid() }.getOrNull() ?: return@withContext
        engine.titles.awaitWrites()
        val dir = engine.titles.dirOf(key).apply { mkdirs() }
        val posterId = title.item.parentId?.takeIf { title.isEpisode } ?: title.itemId
        val poster = title.poster ?: runCatching {
            fetchTo("${title.server}/v1/items/$posterId/poster?stream=$t", File(dir, POSTER))
            POSTER
        }.getOrNull()
        val backdrop = title.backdrop ?: runCatching {
            fetchTo("${title.server}/v1/items/${title.itemId}/backdrop?stream=$t", File(dir, BACKDROP))
            BACKDROP
        }.getOrNull()
        engine.titles.update(key) { it.copy(poster = poster ?: it.poster, backdrop = backdrop ?: it.backdrop) }
        engine.titles.remeasure(key)
    }

    /** Fetches [url] into [file], through a temporary file. A refusal is an
     *  [ApiStatusException] of its path - never of its token. */
    private fun fetchTo(url: String, file: File) {
        engine.extrasClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiStatusException(resp.code, resp.request.url.encodedPath)
            val body = resp.body ?: throw IOException("empty body")
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun noticeKey(owner: Owner) = "${owner.server}|${owner.subject}"

    private fun loadNotices() {
        val text = runCatching { noticesFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val map = runCatching { noticesJson.decodeFromString(NOTICES, text) }.getOrNull() ?: return
        notices.value = map + notices.value
    }

    private fun saveNotices() {
        runCatching {
            noticesFile.parentFile?.mkdirs()
            val tmp = File(noticesFile.parentFile, noticesFile.name + ".tmp")
            tmp.writeText(noticesJson.encodeToString(NOTICES, notices.value))
            if (!tmp.renameTo(noticesFile)) {
                noticesFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val TAG = "ChinoDownloads"

        /** Room kept free beside a download's size. */
        const val SPACE_MARGIN_BYTES = 200L * 1_000_000
        const val NOTIFY_REQUEST = 7302
        const val POSTER = "poster.jpg"
        const val BACKDROP = "backdrop.jpg"

        /** How long a check waits for the server. */
        const val CHECK_WAIT_MS = 10_000L

        /** How often the background check asks at most. */
        const val RECHECK_MS = 5L * 60 * 1000

        /** A title's subtitles asked again after this, times the tries so
         *  far: a server that answered for one and did not serve it, or did
         *  not answer while it seemed there. */
        const val EXTRAS_RETRY_MS = 30_000L

        /** How often, without an answer, before the extras wait for the
         *  server to answer again. */
        const val NO_ANSWER_RETRIES = 3

        val NOTICES = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}

/** Media3's download state as the shared mapping names it. */
internal fun managerState(state: Int): ManagerState = when (state) {
    Download.STATE_QUEUED -> ManagerState.Queued
    Download.STATE_STOPPED -> ManagerState.Stopped
    Download.STATE_DOWNLOADING -> ManagerState.Downloading
    Download.STATE_COMPLETED -> ManagerState.Completed
    Download.STATE_FAILED -> ManagerState.Failed
    Download.STATE_REMOVING -> ManagerState.Removing
    else -> ManagerState.Restarting
}

/**
 * The stream keys of what [plan] keeps, as Media3 numbers the master: its
 * own parse of the same text, each pick found by its URL, so an index is
 * Media3's whatever the master holds - and every subtitle rendition the
 * master lists, the package's text tracks, a few kilobytes each. Null when
 * the master cannot be read, or a pick is not in it: never another
 * rendition in its place.
 */
internal fun streamKeysFor(plan: DownloadPlan): List<StreamKey>? {
    val parsed = runCatching {
        HlsPlaylistParser().parse(Uri.parse(plan.masterUrl), plan.masterText.byteInputStream()) as? HlsMultivariantPlaylist
    }.getOrNull() ?: return null
    fun indexOf(urls: List<String>, uri: String): Int? {
        val wanted = resolveUrl(plan.masterUrl, uri)
        return urls.indexOfFirst { samePiece(it, wanted) }.takeIf { it >= 0 }
    }
    val variant = indexOf(parsed.variants.map { it.url.toString() }, plan.variant.uri) ?: return null
    val audioUrls = parsed.audios.map { it.url?.toString().orEmpty() }
    val audios = plan.audios.map { a -> indexOf(audioUrls, a.uri) ?: return null }
    val subtitles = parsed.subtitles.indices.filter { parsed.subtitles[it].url != null }
    return listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, variant)) +
        audios.distinct().map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, it) } +
        subtitles.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, it) }
}
