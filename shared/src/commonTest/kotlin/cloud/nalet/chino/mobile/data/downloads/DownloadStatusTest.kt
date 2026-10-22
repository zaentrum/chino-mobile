package cloud.nalet.chino.mobile.data.downloads

import cloud.nalet.chino.mobile.data.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("download · 1.4\u00A0GB", offerLabel(1_400_000_000))
        assertEquals("download", offerLabel(null))
        assertEquals("queued", statusLabel(DownloadStatus.Queued))
        assertEquals("waiting for Wi-Fi", statusLabel(DownloadStatus.WaitingForWifi))
        assertEquals("waiting for a connection", statusLabel(DownloadStatus.WaitingForConnection))
        assertEquals("downloading 42%", statusLabel(DownloadStatus.Downloading(42)))
        assertEquals("downloaded · 1.4\u00A0GB", statusLabel(DownloadStatus.Downloaded(1_400_000_000)))
        // "check needed" stays together where the line wraps.
        assertEquals("downloaded · check\u00A0needed", statusLabel(DownloadStatus.CheckNeeded(1_400_000_000)))
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
    fun aRowSaysItsStatusAndSizeInOneText() {
        // On its way: what it will take.
        assertEquals("queued · 347\u00A0MB", rowLabel(DownloadStatus.Queued, 347_000_000))
        assertEquals("waiting for Wi-Fi · 281\u00A0MB", rowLabel(DownloadStatus.WaitingForWifi, 281_000_000))
        assertEquals("waiting for a connection · 281\u00A0MB", rowLabel(DownloadStatus.WaitingForConnection, 281_000_000))
        assertEquals("downloading 15% · 281\u00A0MB", rowLabel(DownloadStatus.Downloading(15), 281_000_000))
        // There: what it takes, once.
        assertEquals("downloaded · 22\u00A0MB", rowLabel(DownloadStatus.Downloaded(22_000_000), 22_000_000))
        // Failed: nothing kept, no size - but what it keeps where its media
        // came in whole and the rest of it did not.
        assertEquals("couldn't download", rowLabel(DownloadStatus.Failed(DownloadFailure.NoSpace), null))
        assertEquals("couldn't download · 49\u00A0MB", rowLabel(DownloadStatus.Failed(DownloadFailure.ServerProblem), 49_000_000))
        assertEquals("queued", rowLabel(DownloadStatus.Queued, null))
    }

    @Test
    fun tryAgainIsOfferedOnlyWhereItCanHelp() {
        assertEquals(
            setOf(DownloadFailure.NoSpace, DownloadFailure.NoAnswer, DownloadFailure.ServerProblem, DownloadFailure.Refused, DownloadFailure.Stopped),
            DownloadFailure.entries.filter { it.canTryAgain }.toSet(),
        )
    }

    @Test
    fun aSeasonOffersWhatItsActionDownloads() {
        // All six can come, none handled: the season.
        assertEquals("download season 1 · 6 episodes · 1.9\u00A0GB", seasonOfferLabel(1, 6, 1_900_000_000, noneHandled = true, allDownloadable = true))
        // Two only stream: the four that come, not "season 1 · 4 episodes".
        assertEquals("download 4 episodes · 1.3\u00A0GB", seasonOfferLabel(1, 4, 1_300_000_000, noneHandled = true, allDownloadable = false))
        assertEquals("download 1 episode", seasonOfferLabel(1, 1, null, noneHandled = true, allDownloadable = false))
        // Some handled: the rest.
        assertEquals("download 2 more episodes · 724\u00A0MB", seasonOfferLabel(1, 2, 724_000_000, noneHandled = false))
        assertEquals("download 1 more episode", seasonOfferLabel(1, 1, null, noneHandled = false))
    }

    @Test
    fun thePageFromThePhoneSaysOnlyWhatIsOnThePhone() {
        assertEquals("1 episode on this phone", episodesOnThePhoneLabel(1, DeviceKind.Phone))
        assertEquals("2 episodes on this phone", episodesOnThePhoneLabel(2, DeviceKind.Phone))
        assertEquals("1 episode on this tablet", episodesOnThePhoneLabel(1, DeviceKind.Tablet))
    }

    @Test
    fun castingSaysWhereItStops() {
        assertEquals("Casting on Living Room TV stops.", castingStopsSentence("Living Room TV"))
        assertEquals("Stopped casting on Living Room TV.", stoppedCastingToast("Living Room TV"))
        assertEquals("Playing on this phone: the server can't be reached.", playingHereToast(DeviceKind.Phone))
        assertEquals("Playing on this tablet: the server can't be reached.", playingHereToast(DeviceKind.Tablet))
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
        assertEquals("download season 1 · 8 episodes · 9.6\u00A0GB", seasonOfferLabel(1, 8, 9_600_000_000, noneHandled = true))
        assertEquals("download season 2 · 1 episode", seasonOfferLabel(2, 1, null, noneHandled = true))
        // Part of a six-episode season handled: the rest, not "season 1 · 2 episodes".
        assertEquals("download 2 more episodes · 724\u00A0MB", seasonOfferLabel(1, 2, 724_000_000, noneHandled = false))
        assertEquals("download 1 more episode", seasonOfferLabel(1, 1, null, noneHandled = false))
        assertEquals("3 of 8 downloaded", partLabel(3, 8))
        assertEquals(
            "12.4\u00A0GB used by downloads · 18\u00A0GB free on this phone",
            storageLine(StorageUse(usedBytes = 12_400_000_000, freeBytes = 18_000_000_000), DeviceKind.Phone),
        )
        assertEquals(
            "820\u00A0MB used by downloads · 1\u00A0GB free on this tablet",
            storageLine(StorageUse(usedBytes = 820_000_000, freeBytes = 999_600_000), DeviceKind.Tablet),
        )
        // Nothing on the phone: what is free, alone - not "0\u00A0MB used".
        assertEquals("3.9\u00A0GB free on this phone", storageLine(StorageUse(usedBytes = 0, freeBytes = 3_900_000_000), DeviceKind.Phone))
        assertEquals(
            "3.9\u00A0GB free on this phone",
            storageLine(StorageUse(usedBytes = 12_000, freeBytes = 3_900_000_000), DeviceKind.Phone, anythingOnThePhone = false),
        )
        // Named as the player's quality menu names it: a name and its height.
        assertEquals("Medium (720p) · about 932\u00A0MB an hour", qualityLine(DownloadQuality(720, 932_000_000)))
        assertEquals("Low (480p) · about 450\u00A0MB an hour", qualityLine(DownloadQuality(480, 450_000_000)))
        assertEquals("High (1080p)", qualityLine(DownloadQuality(1080, null)))
    }

    @Test
    fun theQuestionsBeforeDeletingNameWhatGoes() {
        assertEquals("delete all downloads", DELETE_ALL_TITLE)
        assertEquals("This removes 23 downloads, 12.4\u00A0GB, from this phone.", deleteAllSentence(23, 12_400_000_000, DeviceKind.Phone))
        assertEquals("This removes 1 download, 820\u00A0MB, from this tablet.", deleteAllSentence(1, 820_000_000, DeviceKind.Tablet))
        assertEquals("sign out", SIGN_OUT_TITLE)
        assertEquals("remove account", REMOVE_ACCOUNT_TITLE)
        assertEquals("change server", CHANGE_SERVER_TITLE)
        assertEquals(
            "Your 23 downloads, 12.4\u00A0GB, are deleted from this phone.",
            yourDownloadsGoSentence(DownloadsSummary(23, 12_400_000_000), DeviceKind.Phone),
        )
        assertEquals(
            "Your download, 281\u00A0MB, is deleted from this phone.",
            yourDownloadsGoSentence(DownloadsSummary(1, 281_000_000), DeviceKind.Phone),
        )
        assertEquals(
            "The 23 downloads of Anna, 12.4\u00A0GB, are deleted from this tablet.",
            accountDownloadsGoSentence(DownloadsSummary(23, 12_400_000_000), "Anna", DeviceKind.Tablet),
        )
        // One download: no "1".
        assertEquals(
            "The download of Anna, 281\u00A0MB, is deleted from this phone.",
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

/** "delete all", the storage line and the questions before a sign-out
 *  count the same titles as their size: those with some of their media on
 *  the phone. */
class OnThePhoneTest {
    private fun entry(id: String, status: DownloadStatus, mediaBytes: Long, folderBytes: Long = 200_000): DownloadEntry {
        val claim = OwnerClaim("anna", addedAtMs = 1, checkedAtMs = 1)
        return DownloadEntry(
            title = DownloadedTitle(
                itemId = id,
                item = Item(id = id, title = id, kind = "episode", parentId = "series"),
                quality = "Medium (720p)",
                estimatedBytes = 450_000_000,
                server = "s",
                owners = listOf(claim),
            ),
            status = status,
            onPhoneBytes = if (status is DownloadStatus.Failed) 0 else mediaBytes + folderBytes,
            claim = claim,
            mediaBytes = mediaBytes,
        )
    }

    @Test
    fun aQueuedTitleWithOnlyItsArtworkIsNotCountedAPartlyDownloadedOneIs() {
        val entries = listOf(
            entry("e1", DownloadStatus.Downloaded(450_000_000), mediaBytes = 449_800_000),
            entry("e2", DownloadStatus.Downloading(20), mediaBytes = 68_800_000),
            // Queued: their posters only.
            entry("e3", DownloadStatus.Queued, mediaBytes = 0),
            entry("e6", DownloadStatus.WaitingForWifi, mediaBytes = 0),
            entry("e7", DownloadStatus.Failed(DownloadFailure.NoSpace), mediaBytes = 0),
        )
        val there = onThePhone(entries)
        assertEquals(DownloadsSummary(2, 519_000_000), there)
        assertEquals("This removes 2 downloads, 519 MB, from this phone.", deleteAllSentence(there.titles, there.bytes, DeviceKind.Phone))
        assertEquals("Your 2 downloads, 519 MB, are deleted from this phone.", yourDownloadsGoSentence(there, DeviceKind.Phone))
        assertEquals(
            "519 MB used by downloads · 18 GB free on this phone",
            storageLine(StorageUse(there.bytes, 18_000_000_000), DeviceKind.Phone, anythingOnThePhone = !there.isEmpty),
        )
    }

    @Test
    fun onlyQueuedOrFailedTitlesLeftClearTheList() {
        val entries = listOf(
            entry("e3", DownloadStatus.Queued, mediaBytes = 0),
            entry("e6", DownloadStatus.WaitingForConnection, mediaBytes = 0),
            entry("e7", DownloadStatus.Failed(DownloadFailure.Stopped), mediaBytes = 0),
        )
        val there = onThePhone(entries)
        assertTrue(there.isEmpty)
        assertEquals("This clears the list of downloads.", deleteAllSentence(there.titles, there.bytes, DeviceKind.Phone))
        assertEquals("18 GB free on this phone", storageLine(StorageUse(there.bytes, 18_000_000_000), DeviceKind.Phone, anythingOnThePhone = !there.isEmpty))
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
            onPhoneBytes = 1,
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
