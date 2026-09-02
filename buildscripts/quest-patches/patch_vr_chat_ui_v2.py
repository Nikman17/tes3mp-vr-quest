#!/usr/bin/env python3
"""VR UX round 2 (user feedback after live testing).

1. Virtual keyboard goes BACK to the left wrist: testers found the wrist
   anchor more convenient than a fixed panel in space (revert of the
   front-panel experiment; runs after patch_vr_vkb_and_autoname.py and
   reverses its placement edits, keeping the auto-name feature).
2. TES3MP chat: off the wrist onto a stationary front panel, hidden by
   default (appear-when-needed), toggled from the VR meta menu.
3. Standard Oculus Touch bindings restored: thumbstick clicks are
   always_run / auto_move again (chat actions stay available for manual
   rebinding via the user xrcontrollersuggestions.xml).
4. Singleplayer keeps name PREFILL but not auto-accept (auto-accept broke
   the vanilla chargen flow at new-game start; it stays multiplayer-only
   where the server drives the stages).

Idempotent, CRLF-tolerant. Runs for both engine trees where applicable.
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MP = os.path.join(ROOT, "openmw-vr")
SP = os.path.join(ROOT, "openmw-vr-sp")

def read_norm(p):
    return open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def write_lf(p, src):
    open(p, "w", encoding="utf-8", newline="\n").write(src)

def apply(p, pairs, marker):
    src = read_norm(p)
    if marker in src:
        print(f"{p}: already patched")
        return
    for i, (old, new) in enumerate(pairs, 1):
        if src.count(old) != 1:
            sys.exit(f"{p}: anchor #{i} found {src.count(old)} times (expected 1)")
        src = src.replace(old, new)
    write_lf(p, src)
    print(f"{p}: patched")

# ── 1. keyboard back to the wrist ────────────────────────────────────────

def vkb_to_wrist(tree, wrist_path):
    p = os.path.join(tree, "apps", "openmw", "mwvr", "vrgui.cpp")
    src = read_norm(p)
    changed = False
    old_off = "osg::Vec3 vkeyboardOffset = osg::Vec3(0.f, 0.6f, -0.55f); // Quest: front panel"
    new_off = "osg::Vec3 vkeyboardOffset = leftHudOffset + osg::Vec3(0,0.0001,0); // wrist (user preference)"
    if old_off in src:
        src = src.replace(old_off, new_off, 1)
        changed = True
    old_path = '"/ui/stationary/menu_quad/pose", // Quest: stationary front panel, not wrist'
    old_path_sp = '"/ui/input/stationary/pose", // Quest: stationary front panel, not wrist'
    new_path = f'"{wrist_path}",'
    for op in (old_path, old_path_sp):
        if op in src:
            src = src.replace(op, new_path, 1)
            changed = True
    old_rt = '// Quest: VirtualKeyboard keeps its stationary front-panel offset'
    new_rt = 'mLayerConfigs["VirtualKeyboard"].offset = mLayerConfigs["StatusHUD"].offset + osg::Vec3(0,0.0001,0);'
    if old_rt in src:
        src = src.replace(old_rt, new_rt, 1)
        changed = True
    if changed:
        write_lf(p, src)
        print(f"{p}: keyboard -> wrist")
    else:
        ok = "wrist (user preference)" in src
        print(f"{p}: {'already patched' if ok else 'VKB ANCHORS NOT FOUND'}")
        if not ok:
            sys.exit(1)

vkb_to_wrist(MP, "/world/user/hand/left/input/aim/pose")
if os.path.isdir(SP):
    vkb_to_wrist(SP, "/user/hand/left/input/aim/pose")

# ── 2. chat: stationary front panel + hidden by default (MP tree only) ───

apply(os.path.join(MP, "apps", "openmw", "mwvr", "vrgui.cpp"), [(
    """        LayerConfig chatConfig = LayerConfig{
            0,
            false,
            osg::Vec4{},
            leftHudOffset, // offset (meters)
            osg::Vec2(0.f,0.9f), // center (model space)
            osg::Vec2(.1f, .1f), // extent (meters)
            1024, // Spatial resolution (pixels per meter)
            osg::Vec2i(1024,1024), // Texture resolution
            defaultConfig.myGUIViewSize,
            SizingMode::Auto,
            "/world/user/hand/left/input/aim/pose",
            ""
        };""",
    """        LayerConfig chatConfig = LayerConfig{
            0,
            false,
            osg::Vec4{},
            osg::Vec3(0.f, 0.75f, -0.15f), // Quest: stationary front panel, upper area
            osg::Vec2(0.f,0.9f), // center (model space)
            osg::Vec2(.1f, .1f), // extent (meters)
            1024, // Spatial resolution (pixels per meter)
            osg::Vec2i(1024,1024), // Texture resolution
            defaultConfig.myGUIViewSize,
            SizingMode::Auto,
            "/ui/stationary/menu_quad/pose",
            ""
        };"""
)], marker='chatConfig = LayerConfig{\n            0,\n            false,\n            osg::Vec4{},\n            osg::Vec3(0.f, 0.75f, -0.15f)')

apply(os.path.join(MP, "apps", "openmw", "mwvr", "vrgui.cpp"), [(
    'mLayerConfigs["Chat"].offset = mLayerConfigs["StatusHUD"].offset + osg::Vec3(0,0.0002,0);',
    '// Quest: Chat stays on its stationary front panel (not the wrist)'
)], marker='Chat stays on its stationary front panel')

apply(os.path.join(MP, "apps", "openmw", "mwmp", "GUI", "GUIChat.cpp"), [(
    """        if (windowState == CHAT_DISABLED)
            windowState = CHAT_ENABLED;
    }

    void GUIChat::onClose()""",
    """        if (windowState == CHAT_DISABLED)
            windowState = CHAT_HIDDENMODE; // Quest: hidden by default, appears on new messages
    }

    void GUIChat::onClose()"""
)], marker="hidden by default, appears on new messages")

# ── 3. standard Touch bindings (undo chat stick-click rebinding) ─────────

xml = os.path.join(MP, "files", "xrcontrollersuggestions.xml")
src = read_norm(xml)
if 'ActionName="chat_say"' in src:
    src = src.replace(
        '<Binding ActionName="chat_say" Path="/user/hand/left/input/thumbstick/click"/>',
        '<Binding ActionName="always_run" Path="/user/hand/left/input/thumbstick/click"/>')
    src = src.replace(
        '<Binding ActionName="chat_mode" Path="/user/hand/right/input/thumbstick/click"/>',
        '<Binding ActionName="auto_move" Path="/user/hand/right/input/thumbstick/click"/>')
    write_lf(xml, src)
    print(f"{xml}: standard stick bindings restored")
else:
    print(f"{xml}: already standard")

# ── 4. SP: prefill only, no auto-accept ──────────────────────────────────

if os.path.isdir(SP):
    p = os.path.join(SP, "apps", "openmw", "mwgui", "charactercreation.cpp")
    src = read_norm(p)
    old = """                    if (!mPlayerName.empty() && mCreationStage < CSE_NameChosen)
                        onNameDialogDone(mNameDialog); // auto-accept the launcher-provided name
                    break;"""
    new = """                    // SP keeps the vanilla flow: prefilled name, manual confirm
                    break;"""
    if old in src:
        write_lf(p, src.replace(old, new, 1))
        print(f"{p}: auto-accept removed (prefill kept)")
    else:
        print(f"{p}: {'already patched' if 'prefilled name, manual confirm' in src else 'no auto-accept found (ok)'}")

print("done")
