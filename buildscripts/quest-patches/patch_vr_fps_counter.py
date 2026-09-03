#!/usr/bin/env python3
"""FPS counter on the VR wrist HUD.

Adds a small TextBox to openmw_hud_vr.layout (StatusHUD layer = left wrist)
and updates it twice a second from HUD::onFrame. Enabled by the launcher via
"[VR] show fps" in settings.cfg; costs nothing when disabled.

Applies to both engine trees. Idempotent, CRLF-tolerant.
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = [os.path.join(ROOT, "openmw-vr"), os.path.join(ROOT, "openmw-vr-sp")]

def read_norm(p):
    return open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def write_lf(p, src):
    open(p, "w", encoding="utf-8", newline="\n").write(src)

LAYOUT_ANCHOR = """  <Widget type="Widget" layer="StatusHUD" position="0 0 255 91" name="_Main" align="Default">
    <!-- Energy bars -->"""

LAYOUT_NEW = """  <Widget type="Widget" layer="StatusHUD" position="0 0 255 91" name="_Main" align="Default">
    <!-- vr_fps_counter -->
    <Widget type="TextBox" skin="SandText" position="130 0 120 18" align="Right Top" name="FPSText">
      <Property key="TextAlign" value="Right Top"/>
      <Property key="TextColour" value="1 0.85 0.4"/>
      <Property key="Visible" value="false"/>
    </Widget>
    <!-- Energy bars -->"""

CPP_ANCHOR = """    void HUD::onFrame(float dt)
    {"""

CPP_NEW = """    void HUD::onFrame(float dt)
    {
        // vr_fps_counter: wrist FPS readout, enabled via "[VR] show fps"
        static int fpsInit = 0;
        static MyGUI::TextBox* fpsText = nullptr;
        static float fpsTimer = 0.f;
        static int fpsFrames = 0;
        if (fpsInit == 0)
        {
            fpsInit = 1;
            bool show = false;
            try { show = Settings::Manager::getBool("show fps", "VR"); }
            catch (...) { show = false; }
            if (show && mMainWidget)
            {
                MyGUI::Widget* w = mMainWidget->findWidget("FPSText");
                if (w)
                {
                    fpsText = w->castType<MyGUI::TextBox>(false);
                    if (fpsText)
                        fpsText->setVisible(true);
                }
            }
        }
        if (fpsText)
        {
            ++fpsFrames;
            fpsTimer += dt;
            if (fpsTimer >= 0.5f)
            {
                fpsText->setCaption(MyGUI::utility::toString(int(fpsFrames / fpsTimer + 0.5f)) + " FPS");
                fpsTimer = 0.f;
                fpsFrames = 0;
            }
        }"""

for tree in TREES:
    if not os.path.isdir(tree):
        continue
    layout = os.path.join(tree, "files", "mygui", "openmw_hud_vr.layout")
    src = read_norm(layout)
    if "vr_fps_counter" in src:
        print(f"{layout}: already patched")
    else:
        if src.count(LAYOUT_ANCHOR) != 1:
            sys.exit(f"{layout}: anchor not found")
        write_lf(layout, src.replace(LAYOUT_ANCHOR, LAYOUT_NEW, 1))
        print(f"{layout}: patched")

    cpp = os.path.join(tree, "apps", "openmw", "mwgui", "hud.cpp")
    src = read_norm(cpp)
    if "vr_fps_counter" in src:
        print(f"{cpp}: already patched")
    else:
        if src.count(CPP_ANCHOR) != 1:
            sys.exit(f"{cpp}: onFrame anchor not found")
        src = src.replace(CPP_ANCHOR, CPP_NEW, 1)
        if "#include <components/settings/settings.hpp>" not in src:
            inc = '#include "hud.hpp"'
            if inc not in src:
                sys.exit(f"{cpp}: include anchor missing")
            src = src.replace(inc, inc + "\n\n#include <components/settings/settings.hpp>", 1)
        write_lf(cpp, src)
        print(f"{cpp}: patched")

print("done")
