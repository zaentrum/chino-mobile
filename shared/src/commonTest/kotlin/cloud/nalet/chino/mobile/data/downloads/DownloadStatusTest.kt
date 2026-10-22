package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One status per title, from the download manager's state, in the words the
 *  control shows. */
class DownloadStatusTest {
    private fun status(
        state: ManagerState?,
        percent: Float = 0f,
        waitsForNetwork: Boolean = false,
        wifiOnly: Boolean = true,
        extrasDone: Boolean = true,
        failure: DownloadFailure? = null,
        starting: Boolean = false,
        stopReason: Int = STOP_NONE,
        checkNeeded: Boolean = false,
    ) = downloadStatus(state, percent, bytes = 1_400_000_000, waitsForNetwork, wifiOnly, extrasDone, failure, starting, stopReason, checkNeeded)

    @Test
    fun aQueuedDownloadSaysWhatItWaitsFor() {
        assertEquals(DownloadStatus.Queued, status(ManagerState.Queued))
        assertEquals(DownloadStatus.WaitingForWifi, status(ManagerState.Queued, waitsForNetwork = true))
        assertEquals(DownloadStatus.WaitingForConnection, status(ManagerState.Queued, waitsForNetwork = true, wifiOnly = false))
        assertEquals(DownloadStatus.Queued, status(ManagerState.Restarting))
        assertEquals(DownloadStatus.Queued, status(null, starting = true))
        // Another account's, waiting for its owner: queued, were it shown.
        assertEquals(DownloadStatus.Queued, status(ManagerState.Stopped, stopReason = STOP_OWNER_AWAY))
    }

    @Test
    fun onItsWayInWholePercentNeverAHundredBeforeItIsDone() {
        assertEquals(DownloadStatus.Downloading(42), status(ManagerState.Downloading, percent = 42.9f))
        assertEquals(DownloadStatus.Downloading(0), status(ManagerState.Downloading, percent = -1f))
        assertEquals(DownloadStatus.Downloading(99), status(ManagerState.Downloading, percent = 100f))
    }

    @Test
    fun onThePhoneOnceItsSubtitlesAreThereToo() {
        assertEquals(DownloadStatus.Downloaded(1_400_000_000), status(ManagerState.Completed))
        assertEquals(DownloadStatus.Downloading(99), status(ManagerState.Completed, extrasDone = false))
        assertEquals(
            DownloadStatus.Failed(DownloadFailure.NoAnswer),
            status(ManagerState.Completed, extrasDone = false, failure = DownloadFailure.NoAnswer),
        )
        // Not checked for 30 days, the server out of reach: it waits for it.
        assertEquals(DownloadStatus.CheckNeeded(1_400_000_000), status(ManagerState.Completed, checkNeeded = true))
    }

    @Test
    fun aFailureSaysWhy() {
        assertEquals(DownloadStatus.Failed(DownloadFailure.NoSpace), status(ManagerState.Failed, failure = DownloadFailure.NoSpace))
        assertEquals(DownloadStatus.Failed(DownloadFailure.Stopped), status(ManagerState.Failed))
        // It never reached the manager: why, as recorded.
        assertEquals(DownloadStatus.Failed(DownloadFailure.NoSpace), status(null, failure = DownloadFailure.NoSpace))
        // Stopped before the disk was full, and by the server's switch.
        assertEquals(DownloadStatus.Failed(DownloadFailure.NoSpace), status(ManagerState.Stopped, stopReason = STOP_NO_SPACE))
        assertEquals(DownloadStatus.Failed(DownloadFailure.ServerOff), status(ManagerState.Stopped, stopReason = STOP_SERVER_OFF))
    }
}

class DownloadWordsTest {
    @Test
    fun theControlSaysEachStatusInTheDesignSystemsWords() {
        assertEquals("download · 1.4 GB", offerLabel(1_400_000_000))
        assertEquals("download", offerLabel(null))
        assertEquals("queued", statusLabel(DownloadStatus.Queued))
        assertEquals("waiting for Wi-Fi", statusLabel(DownloadStatus.WaitingForWifi))
        assertEquals("waiting for a connection", statusLabel(DownloadStatus.WaitingForConnection))
        assertEquals("downloading 42%", statusLabel(DownloadStatus.Downloading(42)))
        assertEquals("downloaded · 1.4 GB", statusLabel(DownloadStatus.Downloaded(1_400_000_000)))
        assertEquals("downloaded · check needed", statusLabel(DownloadStatus.CheckNeeded(1_400_000_000)))
        assertEquals("couldn't download", statusLabel(DownloadStatus.Failed(DownloadFailure.NoSpace)))
        assertEquals("Not enough space on this phone.", statusSentence(DownloadStatus.Failed(DownloadFailure.NoSpace), DeviceKind.Phone))
        assertEquals("Not enough space on this tablet.", statusSentence(DownloadStatus.Failed(DownloadFailure.NoSpace), DeviceKind.Tablet))
        assertEquals(
            "Downloads are checked with the server every 30 days. This one plays again once the server can be reached.",
            statusSentence(DownloadStatus.CheckNeeded(1), DeviceKind.Phone),
        )
        assertNull(statusSentence(DownloadStatus.Queued, DeviceKind.Phone))
        assertEquals("can't be downloaded", CANT_DOWNLOAD_LABEL)
        assertEquals("This title can only be streamed.", CANT_DOWNLOAD_SENTENCE)
    }

    @Test
    fun aFailuresSentenceIsAReasonNotAnInstruction() {
        assertEquals("Can't reach the server.", DownloadFailure.NoAnswer.sentence(DeviceKind.Phone))
        assertEquals("The server ran into a problem.", DownloadFailure.ServerProblem.sentence(DeviceKind.Phone))
        assertEquals("The download stopped before it was done.", DownloadFailure.Stopped.sentence(DeviceKind.Phone))
        for (f in DownloadFailure.entries) {
            val text = f.sentence(DeviceKind.Phone)
            assertEquals(false, text.contains("try again", ignoreCase = true), "\"$text\" tells to try again")
            assertEquals(false, text.contains("check ", ignoreCase = true), "\"$text\" gives an instruction")
        }
    }

    @Test
    fun seasonsSeriesStorageAndSettings() {
        assertEquals("download season 1 · 8 episodes · 9.6 GB", seasonOfferLabel(1, 8, 9_600_000_000, noneHandled = true))
        assertEquals("download season 2 · 1 episode", seasonOfferLabel(2, 1, null, noneHandled = true))
        // Part of a six-episode season handled: the rest, not "season 1 · 2 episodes".
        assertEquals("download 2 more episodes · 724 MB", seasonOfferLabel(1, 2, 724_000_000, noneHandled = false))
        assertEquals("download 1 more episode", seasonOfferLabel(1, 1, null, noneHandled = false))
        assertEquals("3 of 8 downloaded", partLabel(3, 8))
        assertEquals(
            "12.4 GB used by downloads · 18 GB free on this phone",
            storageLine(StorageUse(usedBytes = 12_400_000_000, freeBytes = 18_000_000_000), DeviceKind.Phone),
        )
        assertEquals(
            "820 MB used by downloads · 1 GB free on this tablet",
            storageLine(StorageUse(usedBytes = 820_000_000, freeBytes = 999_600_000), DeviceKind.Tablet),
        )
        // Nothing on the phone: what is free, alone - not "0 MB used".
        assertEquals("3.9 GB free on this phone", storageLine(StorageUse(usedBytes = 0, freeBytes = 3_900_000_000), DeviceKind.Phone))
        assertEquals(
            "3.9 GB free on this phone",
            storageLine(StorageUse(usedBytes = 12_000, freeBytes = 3_900_000_000), DeviceKind.Phone, anythingOnThePhone = false),
        )
        assertEquals("720p · about 1.1 GB an hour", qualityLine(DownloadQuality(720, 1_100_000_000)))
        assertEquals("480p · about 450 MB an hour", qualityLine(DownloadQuality(480, 450_000_000)))
        assertEquals("1080p", qualityLine(DownloadQuality(1080, null)))
    }

    @Test
    fun theQuestionsBeforeDeletingNameWhatGoes() {
        assertEquals("delete all downloads", DELETE_ALL_TITLE)
        assertEquals("This removes 6 titles, 474 MB, from this phone.", deleteAllSentence(6, 474_000_000, DeviceKind.Phone))
        assertEquals("This removes 1 title, 820 MB, from this tablet.", deleteAllSentence(1, 820_000_000, DeviceKind.Tablet))
        assertEquals("sign out", SIGN_OUT_TITLE)
        assertEquals("remove account", REMOVE_ACCOUNT_TITLE)
        assertEquals("change server", CHANGE_SERVER_TITLE)
        assertEquals(
            "Your 23 downloads, 12.4 GB, are deleted from this phone.",
            yourDownloadsGoSentence(DownloadsSummary(23, 12_400_000_000), DeviceKind.Phone),
        )
        assertEquals(
            "Your download, 281 MB, is deleted from this phone.",
            yourDownloadsGoSentence(DownloadsSummary(1, 281_000_000), DeviceKind.Phone),
        )
        assertEquals(
            "The 23 downloads of Anna, 12.4 GB, are deleted from this tablet.",
            accountDownloadsGoSentence(DownloadsSummary(23, 12_400_000_000), "Anna", DeviceKind.Tablet),
        )
        // One download: no "1".
        assertEquals(
            "The download of Anna, 281 MB, is deleted from this phone.",
            accountDownloadsGoSentence(DownloadsSummary(1, 281_000_000), "Anna", DeviceKind.Phone),
        )
    }

    @Test
    fun whatTheServersDecisionTookOffThePhone() {
        assertEquals(
            "This title isn't available to this account anymore, so it was deleted from this phone.",
            deletedOneSentence(DeviceKind.Phone),
        )
        assertEquals(
            "3 downloads were deleted from this phone: they aren't available to this account anymore.",
            deletedManySentence(3, DeviceKind.Phone),
        )
        assertEquals("Can't reach the server. Your downloads still play.", UNREACHABLE_BANNER)
        assertEquals("Can't reach the server.", UNREACHABLE_BANNER_NOTHING)
    }

    @Test
    fun anEpisodesNotificationNamesItsSeriesAndCode() {
        assertEquals("Pioneer One · S01E01 · Earthfall", notificationTitle("Earthfall", "Pioneer One", "S01E01"))
        assertEquals("Spring", notificationTitle("Spring", null, null))
    }

    @Test
    fun noWordForTheAppsInsides() {
        // "downloads" and "on this phone"; never the app's own words.
        val all = listOf(
            UNREACHABLE_BANNER, UNREACHABLE_BANNER_NOTHING, EMPTY_TITLE, EMPTY_SENTENCE, DELETE_ALL_TITLE,
            CANT_DOWNLOAD_LABEL, CANT_DOWNLOAD_SENTENCE, CHECK_NEEDED_LABEL, CHECK_NEEDED_SENTENCE,
            deletedOneSentence(DeviceKind.Phone), deletedManySentence(2, DeviceKind.Phone),
            yourDownloadsGoSentence(DownloadsSummary(2, 1), DeviceKind.Phone),
            accountDownloadsGoSentence(DownloadsSummary(2, 1), "Anna", DeviceKind.Phone),
            deleteAllSentence(2, 1, DeviceKind.Phone), deleteAllSentence(0, 0, DeviceKind.Phone),
            storageLine(StorageUse(1, 1), DeviceKind.Phone),
            seasonOfferLabel(1, 2, 1, noneHandled = false), seasonOfferLabel(1, 2, 1, noneHandled = true),
        ) + DownloadFailure.entries.map { it.sentence(DeviceKind.Phone) } +
            listOf(DownloadStatus.Queued, DownloadStatus.WaitingForWifi, DownloadStatus.WaitingForConnection, DownloadStatus.Downloading(1))
                .map(::statusLabel)
        for (text in all) {
            for (word in listOf("offline mode", "sync", "cache")) {
                assertEquals(false, Regex("\\b$word\\b", RegexOption.IGNORE_CASE).containsMatchIn(text), "\"$text\" says $word")
            }
        }
        // A title that only streams promises nothing to come.
        for (text in listOf(CANT_DOWNLOAD_LABEL, CANT_DOWNLOAD_SENTENCE, DownloadFailure.StreamOnly.sentence(DeviceKind.Phone))) {
            for (word in listOf("yet", "for now")) {
                assertEquals(false, Regex("\\b$word\\b", RegexOption.IGNORE_CASE).containsMatchIn(text), "\"$text\" says $word")
            }
        }
    }
}

class DownloadGroupsTest {
    private fun entry(id: String, at: Long, series: String? = null, seriesTitle: String? = null, season: Int? = null, episode: Int? = null): DownloadEntry {
        val claim = OwnerClaim("anna", addedAtMs = at, checkedAtMs = at)
        return DownloadEntry(
            title = DownloadedTitle(
                itemId = id,
                item = Item(
                    id = id,
                    title = id,
                    kind = if (series != null) "episode" else "movie",
                    parentId = series,
                    seasonNumber = season,
                    episodeNumber = episode,
                ),
                seriesTitle = seriesTitle,
                quality = "720p",
                server = "s",
                owners = listOf(claim),
            ),
            status = DownloadStatus.Downloaded(1),
            bytes = 1,
            claim = claim,
        )
    }

    @Test
    fun moviesApartEachSeriesBySeasonNewestGroupFirst() {
        val groups = downloadGroups(
            listOf(
                entry("film-old", at = 1),
                entry("p1e2", at = 5, series = "p", seriesTitle = "Pioneer One", season = 1, episode = 2),
                entry("film-new", at = 3),
                entry("p1e1", at = 4, series = "p", seriesTitle = "Pioneer One", season = 1, episode = 1),
                entry("p2e1", at = 2, series = "p", seriesTitle = "Pioneer One", season = 2, episode = 1),
            ),
        )
        assertEquals(listOf("Pioneer One", null), groups.map { it.seriesTitle })
        val series = groups[0]
        assertEquals(listOf(1, 2), series.seasons.map { it.season })
        assertEquals(listOf("p1e1", "p1e2"), series.seasons[0].entries.map { it.itemId })
        assertEquals(listOf("film-new", "film-old"), groups[1].entries.map { it.itemId })
    }
}
