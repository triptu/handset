"""Builds the app's bundled font and icons from their upstream releases.

Usage: python3 ui_resources.py <work dir> <res dir>
The work dir must hold GoogleSansFlex.ttf (google/fonts, ofl/googlesansflex) and the unpacked
@phosphor-icons/core package under package/. Needs fonttools.
"""
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

WEIGHTS = {400: "regular", 500: "medium", 600: "semibold"}
# Latin, Latin-1, Latin Extended-A, general punctuation, the rupee and the arrows the copy uses
UNICODES = "U+0020-007E,U+00A0-017F,U+2000-206F,U+20B9,U+2190-2193,U+2212,U+2715,U+FFFD"

ICONS = {
    "regular": [
        "calendar-blank", "gear-six", "magnifying-glass", "caret-down", "caret-left", "caret-right", "arrow-left", "x",
        "arrow-square-out", "house", "briefcase", "coffee", "tree", "map-pin", "chat-circle", "hash", "globe",
        "bowl-food", "car", "envelope-simple", "music-notes", "book-open", "asterisk", "file-text", "video-camera",
        "users", "terminal-window", "sparkle", "code", "plugs", "trash", "copy", "check",
        "map-trifold", "squares-four", "lock-simple", "hand-tap", "plus", "paper-plane-tilt",
        "robot", "shield-check", "record", "lightning", "key", "info",
    ],
}


def font(work: Path, res: Path):
    for weight, name in WEIGHTS.items():
        f = TTFont(work / "GoogleSansFlex.ttf")
        instancer.instantiateVariableFont(f, {"wght": weight, "wdth": 100, "opsz": None, "slnt": 0, "ROND": 0}, inplace=True)
        options = subset.Options()
        options.layout_features = ["kern", "liga", "calt", "tnum", "pnum", "case", "ccmp", "locl", "mark", "mkmk"]
        options.name_IDs = ["*"]
        sub = subset.Subsetter(options)
        sub.populate(unicodes=subset.parse_unicodes(UNICODES))
        sub.subset(f)
        f.save(res / "font" / f"google_sans_flex_{name}.ttf")


def icons(work: Path, res: Path):
    for style, names in ICONS.items():
        for name in names:
            svg = (work / "package" / "assets" / style / (name + ("" if style == "regular" else "-fill") + ".svg")).read_text()
            paths = re.findall(r'<path d="([^"]+)"', svg)
            body = "\n".join(f'    <path android:fillColor="@android:color/white" android:pathData="{d}"/>' for d in paths)
            out = res / "drawable" / ("ph_" + name.replace("-", "_") + ("" if style == "regular" else "_fill") + ".xml")
            out.write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n<!-- Phosphor Icons, MIT licence -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"'
                f' android:viewportWidth="256" android:viewportHeight="256">\n{body}\n</vector>\n'
            )


if __name__ == "__main__":
    work, res = Path(sys.argv[1]), Path(sys.argv[2])
    (res / "font").mkdir(exist_ok=True)
    font(work, res)
    icons(work, res)
