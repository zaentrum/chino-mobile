#!/usr/bin/env python3
# Generate a self-contained Compose ImageVector set for the Lucide icons the
# shared module uses, under package com.composables.icons.lucide (same API as
# the icons-lucide-cmp library) so no call sites change. Geometry comes straight
# from Lucide's MIT-licensed SVG source -> identical glyphs, no external klib.
import urllib.request, xml.etree.ElementTree as ET, sys

# PascalCase accessor -> Lucide kebab filename
ICONS = {
 "ArrowLeft":"arrow-left","Bell":"bell","Bookmark":"bookmark","BookmarkCheck":"bookmark-check",
 "Bug":"bug","Check":"check","ChevronDown":"chevron-down","ChevronRight":"chevron-right",
 "EllipsisVertical":"ellipsis-vertical","Eye":"eye","EyeOff":"eye-off","Film":"film","Heart":"heart",
 "House":"house","Info":"info","LogOut":"log-out","Maximize":"maximize","Pencil":"pencil","Play":"play",
 "Plus":"plus","Search":"search","Settings":"settings","Star":"star","Trash2":"trash-2","Tv":"tv",
 "User":"user","Volume2":"volume-2","VolumeX":"volume-x","X":"x","Youtube":"youtube","Zap":"zap",
 "Captions":"captions","ChevronLeft":"chevron-left","Gauge":"gauge","Minimize":"minimize",
 "Pause":"pause","RotateCcw":"rotate-ccw","RotateCw":"rotate-cw","Settings2":"settings-2",
 "SkipForward":"skip-forward",
}
BASE="https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/{}.svg"

def num(s):
    f=float(s); return repr(int(f)) if f==int(f) else repr(f)

def el_to_d(tag, a):
    if tag=="path": return a["d"]
    if tag=="line": return f"M{a['x1']},{a['y1']} L{a['x2']},{a['y2']}"
    if tag in ("polyline","polygon"):
        pts=a["points"].replace(",", " ").split()
        cmd="M"+pts[0]+","+pts[1]
        for i in range(2,len(pts),2): cmd+=f" L{pts[i]},{pts[i+1]}"
        if tag=="polygon": cmd+=" Z"
        return cmd
    if tag in ("circle","ellipse"):
        cx=float(a["cx"]); cy=float(a["cy"])
        rx=float(a.get("r",a.get("rx",0))); ry=float(a.get("r",a.get("ry",0)))
        return f"M{num(cx-rx)},{num(cy)} a{num(rx)},{num(ry)} 0 1,0 {num(2*rx)},0 a{num(rx)},{num(ry)} 0 1,0 {num(-2*rx)},0"
    if tag=="rect":
        x=float(a["x"]); y=float(a["y"]); w=float(a["width"]); h=float(a["height"])
        rx=float(a.get("rx",a.get("ry",0))); ry=float(a.get("ry",a.get("rx",0)))
        if rx<=0 and ry<=0:
            return f"M{num(x)},{num(y)} h{num(w)} v{num(h)} h{num(-w)} Z"
        rx=min(rx,w/2); ry=min(ry,h/2)
        return (f"M{num(x+rx)},{num(y)} h{num(w-2*rx)} a{num(rx)},{num(ry)} 0 0 1 {num(rx)},{num(ry)} "
                f"v{num(h-2*ry)} a{num(rx)},{num(ry)} 0 0 1 {num(-rx)},{num(ry)} h{num(-(w-2*rx))} "
                f"a{num(rx)},{num(ry)} 0 0 1 {num(-rx)},{num(-ry)} v{num(-(h-2*ry))} "
                f"a{num(rx)},{num(ry)} 0 0 1 {num(rx)},{num(-ry)} Z")
    raise ValueError("unhandled "+tag)

def fetch(name):
    with urllib.request.urlopen(BASE.format(name),timeout=20) as r: return r.read().decode()

out=[]
out.append("""// AUTO-GENERATED from Lucide's MIT-licensed SVG source (lucide.dev).
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
""")

fail=[]
for pascal,kebab in ICONS.items():
    try:
        svg=fetch(kebab)
        root=ET.fromstring(svg)
        ds=[]
        for ch in list(root):
            tag=ch.tag.split('}')[-1]
            if tag in ("title","desc","defs"): continue
            ds.append(el_to_d(tag, ch.attrib))
        field="_"+pascal[0].lower()+pascal[1:]
        plist=", ".join('"'+d+'"' for d in ds)
        out.append(f"""private var {field}: ImageVector? = null
public val Lucide.{pascal}: ImageVector
    get() = {field} ?: lucide("{pascal}", listOf({plist})).also {{ {field} = it }}
""")
    except Exception as e:
        fail.append((pascal,kebab,str(e)))

dest="/Users/nalet.meinen/projects/zaentrum/chino-mobile/shared/src/commonMain/kotlin/com/composables/icons/lucide/Lucide.kt"
import os
os.makedirs(os.path.dirname(dest),exist_ok=True)
open(dest,"w").write("\n".join(out))
print("wrote",dest)
print("icons generated:",len(ICONS)-len(fail),"/",len(ICONS))
if fail: print("FAILURES:",fail)
