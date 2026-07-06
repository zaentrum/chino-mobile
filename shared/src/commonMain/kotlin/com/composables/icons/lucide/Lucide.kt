// AUTO-GENERATED from Lucide's MIT-licensed SVG source (lucide.dev).
// Self-contained replacement for the icons-lucide-cmp library: its iOS klib is
// built with Kotlin 2.2.x (ABI 2.2.0) and is unreadable by this project's
// Kotlin 2.1.0 / Kotlin-Native compiler. Same package + accessor names, so no
// call site changes. Stroke style matches Lucide (width 2, round cap/join);
// color is overridden by Icon(tint=...) at the call site.
package com.composables.icons.lucide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

public object Lucide

private fun lucide(name: String, paths: List<String>): ImageVector =
    ImageVector.Builder(
        name = "Lucide.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (d in paths) {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private var _arrowLeft: ImageVector? = null
public val Lucide.ArrowLeft: ImageVector
    get() = _arrowLeft ?: lucide("ArrowLeft", listOf("m12 19-7-7 7-7", "M19 12H5")).also { _arrowLeft = it }

private var _bell: ImageVector? = null
public val Lucide.Bell: ImageVector
    get() = _bell ?: lucide("Bell", listOf("M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326")).also { _bell = it }

private var _bookmark: ImageVector? = null
public val Lucide.Bookmark: ImageVector
    get() = _bookmark ?: lucide("Bookmark", listOf("M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z")).also { _bookmark = it }

private var _bookmarkCheck: ImageVector? = null
public val Lucide.BookmarkCheck: ImageVector
    get() = _bookmarkCheck ?: lucide("BookmarkCheck", listOf("M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z", "m9 10 2 2 4-4")).also { _bookmarkCheck = it }

private var _bug: ImageVector? = null
public val Lucide.Bug: ImageVector
    get() = _bug ?: lucide("Bug", listOf("M12 20v-9", "M14 7a4 4 0 0 1 4 4v3a6 6 0 0 1-12 0v-3a4 4 0 0 1 4-4z", "M14.12 3.88 16 2", "M21 21a4 4 0 0 0-3.81-4", "M21 5a4 4 0 0 1-3.55 3.97", "M22 13h-4", "M3 21a4 4 0 0 1 3.81-4", "M3 5a4 4 0 0 0 3.55 3.97", "M6 13H2", "m8 2 1.88 1.88", "M9 7.13V6a3 3 0 1 1 6 0v1.13")).also { _bug = it }

private var _check: ImageVector? = null
public val Lucide.Check: ImageVector
    get() = _check ?: lucide("Check", listOf("M20 6 9 17l-5-5")).also { _check = it }

private var _chevronDown: ImageVector? = null
public val Lucide.ChevronDown: ImageVector
    get() = _chevronDown ?: lucide("ChevronDown", listOf("m6 9 6 6 6-6")).also { _chevronDown = it }

private var _chevronRight: ImageVector? = null
public val Lucide.ChevronRight: ImageVector
    get() = _chevronRight ?: lucide("ChevronRight", listOf("m9 18 6-6-6-6")).also { _chevronRight = it }

private var _ellipsisVertical: ImageVector? = null
public val Lucide.EllipsisVertical: ImageVector
    get() = _ellipsisVertical ?: lucide("EllipsisVertical", listOf("M11,12 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0", "M11,5 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0", "M11,19 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0")).also { _ellipsisVertical = it }

private var _eye: ImageVector? = null
public val Lucide.Eye: ImageVector
    get() = _eye ?: lucide("Eye", listOf("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9,12 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0")).also { _eye = it }

private var _eyeOff: ImageVector? = null
public val Lucide.EyeOff: ImageVector
    get() = _eyeOff ?: lucide("EyeOff", listOf("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20")).also { _eyeOff = it }

private var _film: ImageVector? = null
public val Lucide.Film: ImageVector
    get() = _film ?: lucide("Film", listOf("M5,3 h14 a2,2 0 0 1 2,2 v14 a2,2 0 0 1 -2,2 h-14 a2,2 0 0 1 -2,-2 v-14 a2,2 0 0 1 2,-2 Z", "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4")).also { _film = it }

private var _heart: ImageVector? = null
public val Lucide.Heart: ImageVector
    get() = _heart ?: lucide("Heart", listOf("M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5")).also { _heart = it }

private var _house: ImageVector? = null
public val Lucide.House: ImageVector
    get() = _house ?: lucide("House", listOf("M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")).also { _house = it }

private var _info: ImageVector? = null
public val Lucide.Info: ImageVector
    get() = _info ?: lucide("Info", listOf("M2,12 a10,10 0 1,0 20,0 a10,10 0 1,0 -20,0", "M12 16v-4", "M12 8h.01")).also { _info = it }

private var _logOut: ImageVector? = null
public val Lucide.LogOut: ImageVector
    get() = _logOut ?: lucide("LogOut", listOf("m16 17 5-5-5-5", "M21 12H9", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4")).also { _logOut = it }

private var _maximize: ImageVector? = null
public val Lucide.Maximize: ImageVector
    get() = _maximize ?: lucide("Maximize", listOf("M8 3H5a2 2 0 0 0-2 2v3", "M21 8V5a2 2 0 0 0-2-2h-3", "M3 16v3a2 2 0 0 0 2 2h3", "M16 21h3a2 2 0 0 0 2-2v-3")).also { _maximize = it }

private var _pencil: ImageVector? = null
public val Lucide.Pencil: ImageVector
    get() = _pencil ?: lucide("Pencil", listOf("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")).also { _pencil = it }

private var _play: ImageVector? = null
public val Lucide.Play: ImageVector
    get() = _play ?: lucide("Play", listOf("M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")).also { _play = it }

private var _plus: ImageVector? = null
public val Lucide.Plus: ImageVector
    get() = _plus ?: lucide("Plus", listOf("M5 12h14", "M12 5v14")).also { _plus = it }

private var _search: ImageVector? = null
public val Lucide.Search: ImageVector
    get() = _search ?: lucide("Search", listOf("m21 21-4.34-4.34", "M3,11 a8,8 0 1,0 16,0 a8,8 0 1,0 -16,0")).also { _search = it }

private var _settings: ImageVector? = null
public val Lucide.Settings: ImageVector
    get() = _settings ?: lucide("Settings", listOf("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", "M9,12 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0")).also { _settings = it }

private var _star: ImageVector? = null
public val Lucide.Star: ImageVector
    get() = _star ?: lucide("Star", listOf("M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z")).also { _star = it }

private var _trash2: ImageVector? = null
public val Lucide.Trash2: ImageVector
    get() = _trash2 ?: lucide("Trash2", listOf("M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")).also { _trash2 = it }

private var _tv: ImageVector? = null
public val Lucide.Tv: ImageVector
    get() = _tv ?: lucide("Tv", listOf("m17 2-5 5-5-5", "M4,7 h16 a2,2 0 0 1 2,2 v11 a2,2 0 0 1 -2,2 h-16 a2,2 0 0 1 -2,-2 v-11 a2,2 0 0 1 2,-2 Z")).also { _tv = it }

private var _user: ImageVector? = null
public val Lucide.User: ImageVector
    get() = _user ?: lucide("User", listOf("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8,7 a4,4 0 1,0 8,0 a4,4 0 1,0 -8,0")).also { _user = it }

private var _volume2: ImageVector? = null
public val Lucide.Volume2: ImageVector
    get() = _volume2 ?: lucide("Volume2", listOf("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728")).also { _volume2 = it }

private var _volumeX: ImageVector? = null
public val Lucide.VolumeX: ImageVector
    get() = _volumeX ?: lucide("VolumeX", listOf("M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M22,9 L16,15", "M16,9 L22,15")).also { _volumeX = it }

private var _x: ImageVector? = null
public val Lucide.X: ImageVector
    get() = _x ?: lucide("X", listOf("M18 6 6 18", "m6 6 12 12")).also { _x = it }

private var _zap: ImageVector? = null
public val Lucide.Zap: ImageVector
    get() = _zap ?: lucide("Zap", listOf("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")).also { _zap = it }

private var _youtube: ImageVector? = null
public val Lucide.Youtube: ImageVector
    get() = _youtube ?: lucide("Youtube", listOf("M2.5 17a24.12 24.12 0 0 1 0-10 2 2 0 0 1 1.4-1.4 49.56 49.56 0 0 1 16.2 0A2 2 0 0 1 21.5 7a24.12 24.12 0 0 1 0 10 2 2 0 0 1-1.4 1.4 49.55 49.55 0 0 1-16.2 0A2 2 0 0 1 2.5 17", "m10 15 5-3-5-3z")).also { _youtube = it }

private var _captions: ImageVector? = null
public val Lucide.Captions: ImageVector
    get() = _captions ?: lucide("Captions", listOf("M5,5 h14 a2,2 0 0 1 2,2 v10 a2,2 0 0 1 -2,2 h-14 a2,2 0 0 1 -2,-2 v-10 a2,2 0 0 1 2,-2 Z", "M7 15h4M15 15h2M7 11h2M13 11h4")).also { _captions = it }

private var _chevronLeft: ImageVector? = null
public val Lucide.ChevronLeft: ImageVector
    get() = _chevronLeft ?: lucide("ChevronLeft", listOf("m15 18-6-6 6-6")).also { _chevronLeft = it }

private var _gauge: ImageVector? = null
public val Lucide.Gauge: ImageVector
    get() = _gauge ?: lucide("Gauge", listOf("m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0")).also { _gauge = it }

private var _minimize: ImageVector? = null
public val Lucide.Minimize: ImageVector
    get() = _minimize ?: lucide("Minimize", listOf("M8 3v3a2 2 0 0 1-2 2H3", "M21 8h-3a2 2 0 0 1-2-2V3", "M3 16h3a2 2 0 0 1 2 2v3", "M16 21v-3a2 2 0 0 1 2-2h3")).also { _minimize = it }

private var _pause: ImageVector? = null
public val Lucide.Pause: ImageVector
    get() = _pause ?: lucide("Pause", listOf("M15,3 h3 a1,1 0 0 1 1,1 v16 a1,1 0 0 1 -1,1 h-3 a1,1 0 0 1 -1,-1 v-16 a1,1 0 0 1 1,-1 Z", "M6,3 h3 a1,1 0 0 1 1,1 v16 a1,1 0 0 1 -1,1 h-3 a1,1 0 0 1 -1,-1 v-16 a1,1 0 0 1 1,-1 Z")).also { _pause = it }

private var _rotateCcw: ImageVector? = null
public val Lucide.RotateCcw: ImageVector
    get() = _rotateCcw ?: lucide("RotateCcw", listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")).also { _rotateCcw = it }

private var _rotateCw: ImageVector? = null
public val Lucide.RotateCw: ImageVector
    get() = _rotateCw ?: lucide("RotateCw", listOf("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5")).also { _rotateCw = it }

private var _settings2: ImageVector? = null
public val Lucide.Settings2: ImageVector
    get() = _settings2 ?: lucide("Settings2", listOf("M14 17H5", "M19 7h-9", "M14,17 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0", "M4,7 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0")).also { _settings2 = it }

private var _skipForward: ImageVector? = null
public val Lucide.SkipForward: ImageVector
    get() = _skipForward ?: lucide("SkipForward", listOf("M21 4v16", "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z")).also { _skipForward = it }
