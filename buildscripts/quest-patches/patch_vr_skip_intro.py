#!/usr/bin/env python3
"""Skip the company logo intro video on Quest.

SP boot was hanging in WindowManager::playVideo(logo, true): the blocking
video path (ffmpeg -> VR video layer) never returns on device. The intro
adds nothing on a headset and slows every boot, so it is skipped entirely.

Applies to both engine trees. Idempotent, CRLF-tolerant.
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = [os.path.join(ROOT, "openmw-vr"), os.path.join(ROOT, "openmw-vr-sp")]

OLD = """    if (!mSkipMenu)
    {
        const std::string& logo = Fallback::Map::getString("Movies_Company_Logo");
        if (!logo.empty())
            mEnvironment.getWindowManager()->playVideo(logo, true);
    }"""

NEW = """    if (!mSkipMenu)
    {
        // Quest: the blocking intro video path hangs on device (ffmpeg into
        // the VR video layer); skip straight to the menu. vr_skip_intro
        Log(Debug::Info) << "Skipping company logo video (VR/Android build)";
    }"""

for tree in TREES:
    if not os.path.isdir(tree):
        continue
    p = os.path.join(tree, "apps", "openmw", "engine.cpp")
    src = open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")
    if "vr_skip_intro" in src:
        print(f"{p}: already patched")
        continue
    if src.count(OLD) != 1:
        sys.exit(f"{p}: anchor found {src.count(OLD)} times (expected 1)")
    open(p, "w", encoding="utf-8", newline="\n").write(src.replace(OLD, NEW, 1))
    print(f"{p}: patched")
print("done")
