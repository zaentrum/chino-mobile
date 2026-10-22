package cloud.nalet.chino.mobile.ui.player

/*
 * The platform's names of languages and regions, in any locale - what
 * chino-web reads from Intl.DisplayNames. The labels themselves come from
 * Languages.kt's English tables, so every target names a track the same
 * way; these names only tell what a track's own title says: "Allemand" on
 * a German track is the language again, "Latinoamérica" on a Spanish one
 * its region.
 */

/** What the platform calls [tag] in [inLocale] (both BCP 47), or null where
 *  it has no name for it. */
internal expect fun platformLanguageName(tag: String, inLocale: String): String?

/** What the platform calls the region [code] ("ES", "419") in [inLocale],
 *  or null where it has no name for it. */
internal expect fun platformRegionName(code: String, inLocale: String): String?

/** [text] in Unicode's canonical decomposition (NFD): an accented letter as
 *  its letter and its accent. */
internal expect fun decomposed(text: String): String
