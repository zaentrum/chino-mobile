package cloud.nalet.chino.mobile.ui.player

/*
 * The way the phone app's player stands on the screen, upright or sideways
 * (section 11.4.3 of the specification): pure, so the tests pin how it stands
 * after each thing that happens to it - a title opened, fullscreen and exit
 * fullscreen, the phone turned, the next episode, the player left, on a
 * phone and on a large screen, with the phone's own setting for turning on
 * and off - and the Android player only passes on what the stand asks of the
 * system.
 */

/** From this smallest width of the screen on, in dp, the app is on a large
 *  screen - a tablet, a phone unfolded to a tablet's size - and under it on
 *  a phone. */
const val LARGE_SCREEN_FROM_DP = 600

/** Whether a screen of [smallestWidthDp] is a phone's. Only a phone's player
 *  is turned by the app: a large screen the system does not let an app turn
 *  (Android 16 and later, for an app that targets it), so there the player
 *  stands as the device is held. */
fun isPhoneScreen(smallestWidthDp: Int): Boolean = smallestWidthDp < LARGE_SCREEN_FROM_DP

/** The way the player stands, and how it came to. */
enum class PlayerStand {
    /** As the phone is held: the player asks the system for nothing, and
     *  turns with the phone where the phone's own setting lets apps turn. */
    WithThePhone,

    /** Turned sideways by the player - a title opened, or fullscreen was
     *  pressed - however the phone is held and whatever its setting says.
     *  The phone has not been turned sideways since: the person turns it to
     *  follow. */
    Sideways,

    /** Sideways as [Sideways], and the phone has been turned sideways too:
     *  turned back upright, the player turns with it again. */
    SidewaysFollowed,

    /** Turned upright by exit fullscreen. The phone has not been held
     *  upright since. */
    Upright,

    /** Upright as [Upright], and the phone has been held upright too: turned
     *  sideways again, the player turns with it. */
    UprightFollowed,
}

/** What the player asks the system of the screen. */
enum class ScreenTurn {
    /** Nothing of its own: what the app asked before the player opened. */
    AsTheApp,

    /** Sideways, either way round as the phone is held, whatever the phone's
     *  own setting for turning says. */
    Sideways,

    /** Upright. */
    Upright,
}

/** What [stand] asks of the system; null, the player was left: the app as it
 *  was. */
fun turnAsked(stand: PlayerStand?): ScreenTurn = when (stand) {
    null, PlayerStand.WithThePhone -> ScreenTurn.AsTheApp
    PlayerStand.Sideways, PlayerStand.SidewaysFollowed -> ScreenTurn.Sideways
    PlayerStand.Upright, PlayerStand.UprightFollowed -> ScreenTurn.Upright
}

/**
 * The stand as a title opens. On a [phone] a title opens sideways and full
 * screen, however the phone is held and whatever the phone's own setting for
 * turning says. A title that starts from the player itself - the next
 * episode among them - keeps the way the player stands at that moment
 * ([keeps]). On a large screen a title opens as the device is held.
 */
fun playerStandAsOpened(phone: Boolean, keeps: PlayerStand? = null): PlayerStand = when {
    !phone -> PlayerStand.WithThePhone
    keeps != null -> keeps
    else -> PlayerStand.Sideways
}

/**
 * The stand after the fullscreen button was pressed. On a [phone] it goes by
 * how the player stands at that moment ([standsSideways]), however it got
 * there: upright, the button is fullscreen and turns the player sideways;
 * sideways, it is exit fullscreen and turns it upright. On a large screen the
 * button turns nothing.
 */
fun PlayerStand.afterFullscreenButton(standsSideways: Boolean, phone: Boolean): PlayerStand = when {
    !phone -> PlayerStand.WithThePhone
    standsSideways -> PlayerStand.Upright
    else -> PlayerStand.Sideways
}

/**
 * The stand as the phone is held [heldSideways] or upright. A player turned
 * sideways turns with the phone again once the phone has been sideways too
 * and is turned back upright; one turned upright, once the phone has been
 * upright and is turned sideways again. Only where the phone's own setting
 * lets apps turn ([phoneTurnsApps]): where it holds them upright, turning the
 * phone changes nothing, and the buttons are the way. On a large screen the
 * player stands as the device is held.
 */
fun PlayerStand.afterPhoneHeld(heldSideways: Boolean, phoneTurnsApps: Boolean, phone: Boolean): PlayerStand = when {
    !phone -> PlayerStand.WithThePhone
    this == PlayerStand.Sideways && heldSideways -> PlayerStand.SidewaysFollowed
    this == PlayerStand.SidewaysFollowed && !heldSideways && phoneTurnsApps -> PlayerStand.WithThePhone
    this == PlayerStand.Upright && !heldSideways -> PlayerStand.UprightFollowed
    this == PlayerStand.UprightFollowed && heldSideways && phoneTurnsApps -> PlayerStand.WithThePhone
    else -> this
}

/** The stand on a screen that is a [phone]'s or, a phone unfolded, a large
 *  one: there the player lets go of the screen. */
fun PlayerStand.onScreen(phone: Boolean): PlayerStand = if (phone) this else PlayerStand.WithThePhone

/**
 * Whether the fullscreen button is exit fullscreen. On a [phone]: whenever
 * the player stands sideways ([standsSideways]), however it got there - by
 * the button or by the phone turned by hand. On a large screen, as before:
 * while the button has hidden the system bars ([barsHidden]).
 */
fun playerOffersExitFullscreen(phone: Boolean, standsSideways: Boolean, barsHidden: Boolean): Boolean =
    if (phone) standsSideways else barsHidden

/** How far from upright or from sideways, in degrees, the phone still counts
 *  as held that way; between the two it is held as it was. */
private const val HELD_WITHIN_DEGREES = 30

/**
 * How the phone is held, read off the angle its top is turned away from
 * upright ([degrees], 0 to 359, as the platform's orientation sensor gives
 * it; negative where it can't tell - the phone lies flat): true sideways,
 * false upright, and [before] - how it was held - where the sensor can't
 * tell or the phone is at a slant between the two, so that it does not flip
 * back and forth there. [naturallySideways]: a device whose own position is
 * sideways, whose angle counts from there.
 */
fun phoneHeldSideways(degrees: Int, before: Boolean?, naturallySideways: Boolean = false): Boolean? {
    if (degrees < 0) return before
    val angle = degrees % 360
    val fromQuarter = minOf(angle % 90, 90 - angle % 90)
    if (fromQuarter > HELD_WITHIN_DEGREES) return before
    val quarter = (angle + 45) / 90 % 4
    return (quarter % 2 == 1) != naturallySideways
}
