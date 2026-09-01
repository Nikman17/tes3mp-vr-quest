#!/usr/bin/env python3
"""Quest UX fixes for text input in VR.

1. vrgui.cpp: the VR virtual keyboard was anchored to the LEFT WRIST
   ("/world/user/hand/left/input/aim/pose", 0.25m panel). On Quest users
   simply never find it there, and while it holds MyGUI key focus the
   TextInputDialog's OK button appears dead. Re-anchor it as a stationary
   front panel (same tracking path as the menus), sized comfortably.
   Also give the Chat layer a small depth epsilon where it shared the
   exact wrist offset with StatusHUD (z-fighting flicker).

2. charactercreation.cpp: if the launcher provided a character name
   ("player name" in [General] settings), auto-fill it in the chargen
   name dialog so no VR typing is required to start a campaign.

Applies to BOTH engine trees (TES3MP 0.47-vr and vanilla 0.48 openmw-vr);
each fix is anchor-guarded and idempotent.
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = [
    os.path.join(ROOT, "openmw-vr"),
    os.path.join(ROOT, "openmw-vr-sp"),
]

VKB_OLD_TAIL = '''            2048, // Spatial resolution (pixels per meter)
            osg::Vec2i(2048,2048), // Texture resolution
            osg::Vec2(1,1),
            SizingMode::Auto,
            "/world/user/hand/left/input/aim/pose",
            ""
        };'''

VKB_NEW_TAIL = '''            2048, // Spatial resolution (pixels per meter)
            osg::Vec2i(2048,2048), // Texture resolution
            osg::Vec2(1,1),
            SizingMode::Auto,
            "/ui/stationary/menu_quad/pose", // Quest: stationary front panel, not wrist
            ""
        };'''

def read_norm(p):
    """Read with line endings normalized to LF (trees may carry CRLF)."""
    return open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def patch_vrgui(tree):
    p = os.path.join(tree, "apps", "openmw", "mwvr", "vrgui.cpp")
    src = read_norm(p)
    changed = False

    if VKB_OLD_TAIL in src:
        src = src.replace(VKB_OLD_TAIL, VKB_NEW_TAIL, 1)
        changed = True

    # SP (0.48) tree variant: path has no /world prefix, stationary path differs
    sp_old = '''            SizingMode::Auto,
            "/user/hand/left/input/aim/pose",
            ""
        };
        LayerConfig statusHUDConfig'''
    sp_new = '''            SizingMode::Auto,
            "/ui/input/stationary/pose", // Quest: stationary front panel, not wrist
            ""
        };
        LayerConfig statusHUDConfig'''
    if sp_old in src:
        src = src.replace(sp_old, sp_new, 1)
        changed = True

    # keyboard offset: place in front, slightly below eye line (instead of wrist+epsilon)
    old_off = "osg::Vec3 vkeyboardOffset = leftHudOffset + osg::Vec3(0,0.0001,0);"
    new_off = "osg::Vec3 vkeyboardOffset = osg::Vec3(0.f, 0.6f, -0.55f); // Quest: front panel"
    if old_off in src:
        src = src.replace(old_off, new_off, 1)
        changed = True

    # wrist-mode runtime override: keep StatusHUD on the wrist, keyboard stays put
    old_rt = 'mLayerConfigs["VirtualKeyboard"].offset = mLayerConfigs["StatusHUD"].offset + osg::Vec3(0,0.0001,0);'
    new_rt = '// Quest: VirtualKeyboard keeps its stationary front-panel offset'
    if old_rt in src:
        src = src.replace(old_rt, new_rt, 1)
        changed = True

    # chat z-fight with StatusHUD at identical wrist offset (TES3MP tree only)
    old_chat = 'mLayerConfigs["Chat"].offset = mLayerConfigs["StatusHUD"].offset;'
    new_chat = 'mLayerConfigs["Chat"].offset = mLayerConfigs["StatusHUD"].offset + osg::Vec3(0,0.0002,0);'
    if old_chat in src:
        src = src.replace(old_chat, new_chat, 1)
        changed = True

    if changed:
        open(p, "w", encoding="utf-8", newline="\n").write(src)
        print(f"{p}: patched")
    else:
        ok = ("Quest: stationary front panel" in src)
        print(f"{p}: {'already patched' if ok else 'ANCHORS NOT FOUND!'}")
        if not ok:
            sys.exit(1)

NAME_OLD = '''                    mNameDialog->setTextInput(mPlayerName);
                    mNameDialog->setNextButtonShow(mCreationStage >= CSE_NameChosen);
                    mNameDialog->eventDone += MyGUI::newDelegate(this, &CharacterCreation::onNameDialogDone);
                    mNameDialog->setVisible(true);
                    break;'''
NAME_NEW = '''                    {
                        // Quest: allow pre-set character name from the launcher
                        // (typing in VR is painful; skip the dialog entirely)
                        std::string presetName = Settings::Manager::getString("player name", "General");
                        if (!presetName.empty() && mPlayerName.empty())
                            mPlayerName = presetName;
                    }
                    mNameDialog->setTextInput(mPlayerName);
                    mNameDialog->setNextButtonShow(mCreationStage >= CSE_NameChosen);
                    mNameDialog->eventDone += MyGUI::newDelegate(this, &CharacterCreation::onNameDialogDone);
                    mNameDialog->setVisible(true);
                    if (!mPlayerName.empty() && mCreationStage < CSE_NameChosen)
                        onNameDialogDone(mNameDialog); // auto-accept the launcher-provided name
                    break;'''

def patch_chargen(tree):
    p = os.path.join(tree, "apps", "openmw", "mwgui", "charactercreation.cpp")
    src = read_norm(p)
    if "auto-accept the launcher-provided name" in src:
        print(f"{p}: already patched")
        return
    # tolerate the earlier prefill-only revision of this patch
    src = src.replace('''                    {
                        // Quest: allow pre-set character name from the launcher
                        std::string presetName = Settings::Manager::getString("player name", "General");
                        if (!presetName.empty() && mPlayerName.empty())
                            mPlayerName = presetName;
                    }
                    mNameDialog->setTextInput(mPlayerName);''',
        '                    mNameDialog->setTextInput(mPlayerName);', 1)
    if NAME_OLD not in src:
        print(f"{p}: name anchor missing!")
        sys.exit(1)
    src = src.replace(NAME_OLD, NAME_NEW, 1)
    if '#include "components/settings/settings.hpp"' not in src and "components/settings/settings.hpp" not in src:
        anchor = '#include "charactercreation.hpp"'
        assert anchor in src
        src = src.replace(anchor, anchor + '\n\n#include <components/settings/settings.hpp>', 1)
    open(p, "w", encoding="utf-8", newline="\n").write(src)
    print(f"{p}: patched")

for tree in TREES:
    if os.path.isdir(tree):
        patch_vrgui(tree)
        patch_chargen(tree)
    else:
        print(f"skip missing tree {tree}")
print("done")
