package cloud.nalet.chino.mobile.ui.downloads

import cloud.nalet.chino.mobile.data.downloads.DownloadEntry
import cloud.nalet.chino.mobile.data.downloads.DownloadFailure
import cloud.nalet.chino.mobile.data.downloads.DownloadStatus
import cloud.nalet.chino.mobile.data.downloads.DownloadedTitle
import cloud.nalet.chino.mobile.data.downloads.OwnerClaim
import cloud.nalet.chino.mobile.data.model.Item
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The title page's and the episode row's control say a download's status
 * and its size as one text, as its row on the downloads screen does
 * (docs/chino downloads, "The control says").
 */
class DownloadControlTest {
    private fun onPhone(status: DownloadStatus, estimated: Long? = 1_400_000_000, onPhoneBytes: Long = 300_000_000): ControlState {
        val claim = OwnerClaim("anna", addedAtMs = 1, checkedAtMs = 1)
        return ControlState.OnPhone(
            DownloadEntry(
                title = DownloadedTitle(
                    itemId = "film",
                    item = Item(id = "film", title = "A Film", kind = "movie"),
                    quality = "Medium (720p)",
                    estimatedBytes = estimated,
                    server = "https://media.example.com/api",
                    owners = listOf(claim),
                ),
                status = status,
                onPhoneBytes = onPhoneBytes,
                claim = claim,
            ),
        )
    }

    @Test
    fun everyStateOnItsWayCarriesTheSizeItsOfferSaid() {
        assertEquals("download · 1.4 GB", controlLabel(ControlState.Offer(1_400_000_000)))
        // Asked for and worked out now: queued, with the offer's size.
        assertEquals("queued · 1.4 GB", controlLabel(ControlState.Starting(1_400_000_000)))
        assertEquals("queued · 1.4 GB", controlLabel(onPhone(DownloadStatus.Queued)))
        assertEquals("waiting for Wi-Fi · 1.4 GB", controlLabel(onPhone(DownloadStatus.WaitingForWifi)))
        assertEquals("waiting for a connection · 1.4 GB", controlLabel(onPhone(DownloadStatus.WaitingForConnection)))
        assertEquals("downloading 42% · 1.4 GB", controlLabel(onPhone(DownloadStatus.Downloading(42))))
        // There: the same number.
        assertEquals("downloaded · 1.4 GB", controlLabel(onPhone(DownloadStatus.Downloaded(1_400_000_000))))
        assertEquals("downloaded · check needed", controlLabel(onPhone(DownloadStatus.CheckNeeded(1_400_000_000))))
    }

    @Test
    fun aSizeNotKnownIsLeftOutNotGuessed() {
        assertEquals("download", controlLabel(ControlState.Offer(null)))
        assertEquals("queued", controlLabel(ControlState.Starting(null)))
    }

    @Test
    fun aFailureAndATitleThatOnlyStreamsCarryNoSize() {
        assertEquals("couldn't download", controlLabel(ControlState.StartFailed(DownloadFailure.NoSpace)))
        assertEquals("couldn't download", controlLabel(onPhone(DownloadStatus.Failed(DownloadFailure.ServerProblem), onPhoneBytes = 0)))
        assertEquals("can't be downloaded", controlLabel(ControlState.CantDownload))
    }
}
