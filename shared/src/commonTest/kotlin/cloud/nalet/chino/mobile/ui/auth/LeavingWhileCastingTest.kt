package cloud.nalet.chino.mobile.ui.auth

import cloud.nalet.chino.mobile.data.downloads.DownloadsSummary
import cloud.nalet.chino.mobile.ui.onboarding.asksBeforeChangingServer
import cloud.nalet.chino.mobile.ui.onboarding.changeServerLeavesCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every way the account signed in changes while it casts writes its last
 * position and stops casting (docs/chino casting, "Changing accounts"):
 * the paths that had none - "Add account" in the picker, and Change server
 * to the server connected now.
 */
class LeavingWhileCastingTest {
    @Test
    fun addingAnAccountInThePickerIsASwitchTheAccountBeforeLeaves() {
        assertEquals("anna", accountLeavingFor(activeBefore = "anna", signedIn = "ben"))
        // Signing in again as the same account: nobody leaves.
        assertNull(accountLeavingFor(activeBefore = "anna", signedIn = "anna"))
        // Nobody signed in before: nobody leaves.
        assertNull(accountLeavingFor(activeBefore = null, signedIn = "ben"))
    }

    @Test
    fun changeServerToTheSameServerStillStopsCastingAndAsksFirst() {
        // The same server again: no downloads go, but every account does -
        // casting stops, and the question says so.
        assertTrue(asksBeforeChangingServer(clearsAccounts = true, downloads = null, casting = true))
        assertTrue(changeServerLeavesCast(clearsAccounts = true, dropsDownloads = false))
        // Nothing casting and nothing to delete: no question.
        assertFalse(asksBeforeChangingServer(clearsAccounts = true, downloads = null, casting = false))
        // Another server with downloads: asked, as before.
        assertTrue(asksBeforeChangingServer(clearsAccounts = true, downloads = DownloadsSummary(2, 1), casting = false))
        assertTrue(changeServerLeavesCast(clearsAccounts = true, dropsDownloads = true))
        // The first connect, nobody signed in yet: nothing leaves.
        assertFalse(changeServerLeavesCast(clearsAccounts = false, dropsDownloads = false))
        assertFalse(asksBeforeChangingServer(clearsAccounts = false, downloads = null, casting = true))
    }
}
