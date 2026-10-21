package cloud.nalet.chino.mobile.data.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** What POST progress sends: the player's writes as before, and Continue
 *  Watching's remove as a dismiss, which chino-api leaves unwatched. */
class ProgressBodyTest {
    @Test
    fun thePlayersWriteIsThePositionAndTheLength() {
        assertEquals(
            """{"position_sec":600,"duration_sec":2700}""",
            ChinoJson.encodeToString(ProgressBody.serializer(), ProgressBody(positionSec = 600, durationSec = 2700)),
        )
    }

    @Test
    fun aRemoveIsADismiss() {
        assertEquals(
            """{"position_sec":2700,"duration_sec":2700,"dismiss":true}""",
            ChinoJson.encodeToString(
                ProgressBody.serializer(),
                ProgressBody(positionSec = 2700, durationSec = 2700, dismiss = true),
            ),
        )
    }
}
