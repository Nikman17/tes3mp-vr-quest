#!/usr/bin/env python3
"""Give the TES3MP chat font (Russo One TTF) explicit glyph ranges incl. Cyrillic.

Without a <Codes> block MyGUI only rasterizes the default ASCII range, so any
Cyrillic chat text renders as tofu even when the game encoding is win1251.
Russo One ships with full Cyrillic coverage - just ask for it.

Idempotent; applied to buildscripts/openmw-vr by setup-source.sh.
"""
import sys

FILES = [
    "../openmw-vr/files/mygui/openmw_font.xml",
]

OLD = """    <Resource type="ResourceTrueTypeFont" name="Russo">
        <Property key="Source" value="RussoOne-Regular.ttf"/>
        <Property key="Size" value="11"/>
        <Property key="Resolution" value="96"/>
        <Property key="Antialias" value="false"/>
        <Property key="TabWidth" value="8"/>
        <Property key="OffsetHeight" value="0"/>
    </Resource>"""

NEW = """    <Resource type="ResourceTrueTypeFont" name="Russo">
        <Property key="Source" value="RussoOne-Regular.ttf"/>
        <Property key="Size" value="11"/>
        <Property key="Resolution" value="96"/>
        <Property key="Antialias" value="false"/>
        <Property key="TabWidth" value="8"/>
        <Property key="OffsetHeight" value="0"/>
        <Codes>
            <Code range="33 126"/>
            <Code range="1024 1279"/>
            <Code range="8210 8230"/>
        </Codes>
    </Resource>"""

if __name__ == "__main__":
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    for path in FILES:
        src = open(path, encoding="utf-8").read()
        if '<Code range="1024 1279"/>' in src:
            print(f"{path}: already patched")
            continue
        if src.count(OLD) != 1:
            sys.exit(f"{path}: Russo font anchor not found")
        src = src.replace(OLD, NEW)
        open(path, "w", encoding="utf-8", newline="\n").write(src)
        print(f"{path}: patched")
    print("done")
