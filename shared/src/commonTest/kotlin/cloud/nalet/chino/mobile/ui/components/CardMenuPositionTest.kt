package cloud.nalet.chino.mobile.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A card's menu opens from the card's leading edge and stays inside the
 * page's 16 dp gutter - never flush with the screen's edge at x 0, as a
 * menu wider than the room right of a Continue watching card was.
 */
class CardMenuPositionTest {
    // A 1080 x 2400 phone at 2.625: the 16 dp gutter is 42 px, a 280 dp
    // menu 735 px, a 128 dp card 336 px.
    private val window = IntSize(1080, 2400)
    private val gutter = 42
    private val menu = IntSize(735, 400)
    private val position = CardMenuPosition(gutter = gutter, drop = 16)

    private fun card(left: Int, top: Int = 600) = IntRect(left, top, left + 336, top + 504)

    private fun at(card: IntRect, direction: LayoutDirection = LayoutDirection.Ltr): IntOffset =
        position.calculatePosition(card, window, direction, menu)

    private fun assertInsideTheGutter(at: IntOffset) {
        assertTrue(at.x >= gutter, "$at starts in the gutter")
        assertTrue(at.x + menu.width <= window.width - gutter, "$at ends in the gutter")
        assertTrue(at.y >= gutter && at.y + menu.height <= window.height - gutter, "$at within the window")
    }

    @Test
    fun theFirstCardInTheRowItsMenuFromTheCardsEdgeInTheGutterNeverAtXZero() {
        val at = at(card(left = gutter))
        assertEquals(IntOffset(gutter, 600 + 16), at)
        assertInsideTheGutter(at)
    }

    @Test
    fun aCardNearTheRightEdgeItsMenuMovesInToTheGutter() {
        val at = at(card(left = 760))
        assertEquals(window.width - gutter - menu.width, at.x)
        assertInsideTheGutter(at)
    }

    @Test
    fun aCardScrolledPartlyOffTheScreenItsMenuInTheGutterAllTheSame() {
        assertInsideTheGutter(at(card(left = -200)))
        assertInsideTheGutter(at(card(left = 900)))
    }

    @Test
    fun aCardLowOnTheScreenItsMenuMovesUpAboveTheGutter() {
        val at = at(card(left = gutter, top = 2200))
        assertEquals(window.height - gutter - menu.height, at.y)
        assertInsideTheGutter(at)
    }

    @Test
    fun rightToLeftFromTheCardsRightEdge() {
        val c = card(left = 600)
        assertEquals(c.right - menu.width, at(c, LayoutDirection.Rtl).x)
        assertInsideTheGutter(at(card(left = window.width - gutter - 336), LayoutDirection.Rtl))
        assertInsideTheGutter(at(card(left = gutter), LayoutDirection.Rtl))
    }
}
