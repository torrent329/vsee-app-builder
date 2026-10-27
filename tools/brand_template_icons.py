"""Add a small TORR3NT mark to sanitized app icons and TV banners.

Run only on a fresh apktool decode of each checked template. This prepares
candidate templates; playback validation is still required before release.
"""

from __future__ import annotations

import re
from pathlib import Path
from shutil import copyfile


ROOT = Path(__file__).resolve().parents[1] / "build-preview"
APPS = ("heatlive", "heatlivebackup", "heatvod", "heatvodultra",
        "liveultra", "liveultraplus")


def strokes() -> str:
    # Hand-drawn line letters, so the badge contains no redistributed font.
    letters = {
        "T": ("M0,0 L6,0", "M3,0 L3,8"),
        "O": ("M0,0 L6,0 L6,8 L0,8 Z",),
        "R": ("M0,8 L0,0 L5,0 L6,1 L6,3 L5,4 L0,4", "M3,4 L6,8"),
        "3": ("M0,0 L6,0 L6,8 L0,8", "M1,4 L6,4"),
        "N": ("M0,8 L0,0 L6,8 L6,0",),
    }
    parts = []
    for index, letter in enumerate("TORR3NT"):
        x = 26.5 + index * 8
        for path in letters[letter]:
            parts.append(re.sub(r"([ML])([0-9]+),([0-9]+)",
                                lambda m: f"{m[1]}{x + int(m[2]):g},"
                                          f"{74 + int(m[3]):g}", path))
    return " ".join(parts)


TEXT = strokes()
ICON_MARK = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#231640" android:strokeColor="#68DDF0"
        android:strokeWidth="1" android:pathData="M25,70 L83,70 C85,70 87,72 87,74 L87,82 C87,84 85,86 83,86 L25,86 C23,86 21,84 21,82 L21,74 C21,72 23,70 25,70 Z" />
    <path android:fillColor="@android:color/transparent"
        android:strokeColor="#F1FBFF" android:strokeWidth="1.15"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="{TEXT}" />
</vector>
'''

BANNER_MARK = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="104dp" android:height="24dp"
    android:viewportWidth="104" android:viewportHeight="24">
    <path android:fillColor="#231640" android:strokeColor="#68DDF0"
        android:strokeWidth="1" android:pathData="M5,1 L99,1 C101,1 103,3 103,5 L103,19 C103,21 101,23 99,23 L5,23 C3,23 1,21 1,19 L1,5 C1,3 3,1 5,1 Z" />
    <group android:scaleX="1.45" android:scaleY="1.35"
        android:translateX="-26" android:translateY="-92">
        <path android:fillColor="@android:color/transparent"
            android:strokeColor="#F1FBFF" android:strokeWidth="1.15"
            android:strokeLineCap="round" android:strokeLineJoin="round"
            android:pathData="{TEXT}" />
    </group>
</vector>
'''


def apply(name: str, decoded: Path | None = None) -> None:
    if decoded is None:
        decoded = ROOT / f"{name}-decoded"
    if not decoded.is_dir():
        raise FileNotFoundError(decoded)
    drawable = decoded / "res" / "drawable"
    (drawable / "torr3nt_icon_mark.xml").write_text(ICON_MARK, encoding="utf-8")
    (drawable / "torr3nt_banner_mark.xml").write_text(BANNER_MARK, encoding="utf-8")

    manifest = decoded / "AndroidManifest.xml"
    source = manifest.read_text(encoding="utf-8")
    match = re.search(r'android:banner="(@drawable/[^"]+)"', source)
    if not match:
        raise ValueError(f"No application banner: {name}")
    original_banner = match.group(1)
    source = source.replace(match.group(0),
                            'android:banner="@drawable/torr3nt_branded_banner"', 1)
    manifest.write_text(source, encoding="utf-8")
    (drawable / "torr3nt_branded_banner.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="{original_banner}" />
    <item android:drawable="@drawable/torr3nt_banner_mark"
        android:gravity="bottom|right" android:width="104dp"
        android:height="24dp" android:bottom="8dp" android:right="8dp" />
</layer-list>
''', encoding="utf-8")

    if name == "heatvod":
        original = decoded / "res" / "mipmap-xxxhdpi" / "ic_launcher.png"
        if not original.is_file():
            raise FileNotFoundError(original)
        nodpi = decoded / "res" / "drawable-nodpi"
        nodpi.mkdir(exist_ok=True)
        copyfile(original, nodpi / "torr3nt_heatvod_source.png")
        (drawable / "torr3nt_heatvod_background.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#227DC0" />
</shape>
''', encoding="utf-8")
        foreground = "@drawable/torr3nt_heatvod_source"
        adaptive = decoded / "res" / "mipmap-anydpi-v26"
        adaptive.mkdir(exist_ok=True)
        (adaptive / "ic_launcher.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/torr3nt_heatvod_background" />
    <foreground android:drawable="@drawable/torr3nt_branded_foreground" />
</adaptive-icon>
''', encoding="utf-8")
    else:
        adaptive = decoded / "res" / "mipmap-anydpi" / "ic_launcher.xml"
        source = adaptive.read_text(encoding="utf-8")
        match = re.search(r'<foreground android:drawable="([^"]+)"\s*/>', source)
        if not match:
            raise ValueError(f"No adaptive icon foreground: {name}")
        foreground = match.group(1)
        source = source.replace(match.group(0),
                                '<foreground android:drawable="@drawable/torr3nt_branded_foreground" />', 1)
        adaptive.write_text(source, encoding="utf-8")
    (drawable / "torr3nt_branded_foreground.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="{foreground}" />
    <item android:drawable="@drawable/torr3nt_icon_mark" />
</layer-list>
''', encoding="utf-8")


if __name__ == "__main__":
    for app in APPS:
        apply(app)
        print(f"Branded candidate: {app}")
