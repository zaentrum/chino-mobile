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
        waits: Waiting = Waiting(),
        wifiOnly: Boolean = true,
        extrasDone: Boolean = true,
        failure: DownloadFailure? = null,
        starting: Boolean = false,
    ) = downloadStatus(state, percent, bytes = 1_400_000_000, waits, wifiOnly, extrasDone, failure, starting)

    @Test
    fun aQueuedDownloadSaysWhatItWaitsFor() {
        assertEquals(DownloadStatus.Queued, status(ManagerState.Queued))
        assertEquals(DownloadStatus.WaitingForWifi, status(ManagerState.Queued, waits = Waiting(network = true)))
        assertEquals(DownloadStatus.WaitingForConnection, status(ManagerState.Queued, waits = Waiting(network = true), wifiOnly = false))
        assertEquals(DownloadStatus.WaitingForBattery, status(ManagerState.Queued, waits = Waiting(charging = true)))
        assertEquals(DownloadStatus.Queued, status(ManagerState.Restarting))
        assertEquals(DownloadStatus.Queued, status(null, starting = true))
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
    }

    @Test
    fun aFailureSaysWhy() {
        assertEquals(DownloadStatus.Failed(DownloadFailure.NoSpace), status(ManagerState.Failed, failure = DownloadFailure.NoSpace))
        assertEquals(DownloadStatus.Failed(DownloadFailure.Stopped), status(ManagerState.Failed))
        // It never reached the manager: why, as recorded.
        assertEquals(DownloadStatus.Failed(DownloadFailure.NoSpace), status(null, failure = DownloadFailure.NoSpace))
        assertEquals(DownloadStatus.Removing, status(ManagerState.Removing))
    }
}

class DownloadWordsTest {
    @Test
    fun theControlSaysEachStatusInTheDesignSystemsWords() {
        assertEquals("download · 1.4 GB", offerLabel(1_400_000_000))
        assertEquals("download", offerLabel(null))
        assertEquals("queued", statusLabel(DownloadStatus.Queued))
        assertEquals("waiting for Wi-Fi", statusLabel(DownloadStatus.WaitingForWifi))
        assertEquals("downloading 42%", statusLabel(DownloadStatus.Downloading(42)))
        assertEquals("downloaded · 1.4 GB", statusLabel(DownloadStatus.Downloaded(1_400_000_000)))
        assertEquals("couldn't download", statusLabel(DownloadStatus.Failed(DownloadFailure.NoSpace)))
        assertEquals("Not enough space on this phone.", statusSentence(DownloadStatus.Failed(DownloadFailure.NoSpace)))
        assertNull(statusSentence(DownloadStatus.Queued))
        assertEquals("can't be downloaded yet", CANT_DOWNLOAD_LABEL)
        assertEquals("This title can only be streamed for now.", CANT_DOWNLOAD_SENTENCE)
    }

    @Test
    fun seasonsSeriesStorageAndSettings() {
        assertEquals("download season 1 · 8 episodes · 9.6 GB", seasonOfferLabel(1, 8, 9_600_000_000))
        assertEquals("download season 2 · 1 episode", seasonOfferLabel(2, 1, null))
        assertEquals("3 of 8 downloaded", partLabel(3, 8))
        assertEquals(
            "12.4 GB used by downloads · 18.0 GB free on this phone",
            storageLine(StorageUse(usedBytes = 12_400_000_000, freeBytes = 18_000_000_000)),
        )
        assertEquals("720p · about 1.1 GB an hour", qualityLine(DownloadQuality(720, 1_100_000_000)))
        assertEquals("480p · about 450 MB an hour", qualityLine(DownloadQuality(480, 450_000_000)))
        assertEquals("1080p", qualityLine(DownloadQuality(1080, null)))
        assertEquals("This removes 23 titles, 12.4 GB, from this phone.", deleteAllSentence(23, 12_400_000_000))
        assertEquals("This removes 1 title, 820 MB, from this phone.", deleteAllSentence(1, 820_000_000))
    }

    @Test
    fun noWordForTheAppsInsides() {
        // "downloads" and "on this phone"; never the app's own words.
        val all = listOf(
            UNREACHABLE_BANNER, EMPTY_TITLE, EMPTY_SENTENCE, DELETE_ALL_TITLE, CANT_DOWNLOAD_SENTENCE,
        ) + DownloadFailure.entries.map { it.sentence }
        for (text in all) {
            for (word in listOf("offline mode", "sync", "cache")) {
                assertEquals(false, text.contains(word, ignoreCase = true), "\"$text\" says $word")
            }
        }
    }
}

class DownloadGroupsTest {
    private fun entry(id: String, at: Long, series: String? = null, seriesTitle: String? = null, season: Int? = null, episode: Int? = null) =
        DownloadEntry(
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
                addedAtMs = at,
                server = "s",
            ),
            status = DownloadStatus.Downloaded(1),
            bytes = 1,
        )

    @Test
    fun filmsApartEachSeriesBySeasonNewestGroupFirst() {
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
