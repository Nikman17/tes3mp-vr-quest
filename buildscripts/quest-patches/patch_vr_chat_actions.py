#!/usr/bin/env python3
"""Dedicated VR controller actions for the TES3MP chat (Quest port).

The wrist chat layer is hard to hit with the pointer, so give chat two real
OpenXR actions that work regardless of GUI focus:
  chat_say   (left thumbstick click)  -> open the chat input line
                                         (VR virtual keyboard pops via focus)
  chat_mode  (right thumbstick click) -> toggle chat visibility
                                         (visible <-> appear-when-needed)

These replace the stock always_run / auto_move stick-click bindings for Oculus
Touch; both remain available for remapping via the user copy of
xrcontrollersuggestions.xml in /sdcard/tes3mpvr/config/.

Idempotent; applied to buildscripts/openmw-vr by setup-source.sh.
"""
import sys

BASE = "../openmw-vr/"
VRINPUT_HPP = BASE + "apps/openmw/mwvr/vrinput.hpp"
OPENXRINPUT_CPP = BASE + "apps/openmw/mwvr/openxrinput.cpp"
VRINPUTMANAGER_CPP = BASE + "apps/openmw/mwvr/vrinputmanager.cpp"
GUICONTROLLER_HPP = BASE + "apps/openmw/mwmp/GUIController.hpp"
GUICONTROLLER_CPP = BASE + "apps/openmw/mwmp/GUIController.cpp"
GUICHAT_HPP = BASE + "apps/openmw/mwmp/GUI/GUIChat.hpp"
GUICHAT_CPP = BASE + "apps/openmw/mwmp/GUI/GUIChat.cpp"
XRSUGGESTIONS = BASE + "files/xrcontrollersuggestions.xml"

EDITS = [
    # 1. New action ids
    (VRINPUT_HPP, "A_ChatSay", [(
        """        A_Recenter,
        A_VrLast""",
        """        A_Recenter,
        A_ChatSay,
        A_ChatMode,
        A_VrLast"""
    )]),
    # 2. Create the OpenXR actions (Gameplay set)
    (OPENXRINPUT_CPP, "chat_say", [(
        """        getActionSet(ActionSet::Gameplay).createMWAction(VrControlType::Press, MWInput::A_ToggleDebug, "toggle_debug", "Toggle the debug hud");""",
        """        getActionSet(ActionSet::Gameplay).createMWAction(VrControlType::Press, MWInput::A_ToggleDebug, "toggle_debug", "Toggle the debug hud");
        getActionSet(ActionSet::Gameplay).createMWAction(VrControlType::Press, A_ChatSay, "chat_say", "Chat Say");
        getActionSet(ActionSet::Gameplay).createMWAction(VrControlType::Press, A_ChatMode, "chat_mode", "Chat Visibility");"""
    )]),
    # 3. Dispatch to the TES3MP chat
    (VRINPUTMANAGER_CPP, "A_ChatSay", [(
        """                case A_VrMetaMenu:
                    MWBase::Environment::get().getWindowManager()->pushGuiMode(MWGui::GM_VrMetaMenu);
                    break;""",
        """                case A_VrMetaMenu:
                    MWBase::Environment::get().getWindowManager()->pushGuiMode(MWGui::GM_VrMetaMenu);
                    break;
                case A_ChatSay:
                    if (mwmp::Main::get().getGUIController())
                        mwmp::Main::get().getGUIController()->vrChatSay();
                    break;
                case A_ChatMode:
                    if (mwmp::Main::get().getGUIController())
                        mwmp::Main::get().getGUIController()->vrChatToggleVisibility();
                    break;"""
    ), (
        """#include "../mwmp/Main.hpp"
#include "../mwmp/Networking.hpp"
#include "../mwmp/ObjectList.hpp\"""",
        """#include "../mwmp/Main.hpp"
#include "../mwmp/Networking.hpp"
#include "../mwmp/ObjectList.hpp"
#include "../mwmp/GUIController.hpp\""""
    )]),
    # 4. GUIController wrappers (same guards as the keyboard path)
    (GUICONTROLLER_HPP, "vrChatSay", [(
        """        /// Returns 0 if there was no events
        bool pressedKey(int key);""",
        """        /// Returns 0 if there was no events
        bool pressedKey(int key);

        // VR controller entry points (no SDL keyboard on Quest)
        void vrChatSay();
        void vrChatToggleVisibility();"""
    )]),
    (GUICONTROLLER_CPP, "vrChatSay", [(
        """bool mwmp::GUIController::pressedKey(int key)""",
        """void mwmp::GUIController::vrChatSay()
{
    MWBase::WindowManager *windowManager = MWBase::Environment::get().getWindowManager();
    if (mChat == nullptr || windowManager->isConsoleMode() || windowManager->getMode() != MWGui::GM_None)
        return;
    mChat->pressedSay();
}

void mwmp::GUIController::vrChatToggleVisibility()
{
    MWBase::WindowManager *windowManager = MWBase::Environment::get().getWindowManager();
    if (mChat == nullptr || windowManager->isConsoleMode() || windowManager->getMode() != MWGui::GM_None)
        return;
    mChat->toggleVisibilityVR();
}

bool mwmp::GUIController::pressedKey(int key)"""
    )]),
    # 5. Shared VR visibility toggle in GUIChat (button handler reuses it)
    (GUICHAT_HPP, "toggleVisibilityVR", [(
        """        void pressedChatMode(); //switch chat mode""",
        """        void pressedChatMode(); //switch chat mode
        void toggleVisibilityVR(); // visible <-> appear-when-needed (never fully disabled)"""
    )]),
    (GUICHAT_CPP, "void GUIChat::toggleVisibilityVR", [(
        """    void GUIChat::onModeButtonClicked(MyGUI::Widget* _sender)
    {
        // VR: toggle between "always visible" and "appear when needed" only.
        // CHAT_DISABLED is unreachable on purpose: with the window fully hidden
        // there is no keyboard available to bring it back.
        windowState = (windowState == CHAT_ENABLED) ? CHAT_HIDDENMODE : CHAT_ENABLED;
        if (windowState == CHAT_ENABLED)
        {
            mMainWidget->setVisible(true);
            MWBase::Environment::get().getWindowManager()->messageBox("Chat visible");
        }
        else
        {
            curTime = 0;
            MWBase::Environment::get().getWindowManager()->messageBox("Chat appearing when needed");
        }
    }""",
        """    void GUIChat::onModeButtonClicked(MyGUI::Widget* _sender)
    {
        toggleVisibilityVR();
    }

    void GUIChat::toggleVisibilityVR()
    {
        // VR: toggle between "always visible" and "appear when needed" only.
        // CHAT_DISABLED is unreachable on purpose: with the window fully hidden
        // there is no keyboard available to bring it back.
        windowState = (windowState == CHAT_ENABLED) ? CHAT_HIDDENMODE : CHAT_ENABLED;
        if (windowState == CHAT_ENABLED)
        {
            mMainWidget->setVisible(true);
            MWBase::Environment::get().getWindowManager()->messageBox("Chat visible");
        }
        else
        {
            curTime = 0;
            MWBase::Environment::get().getWindowManager()->messageBox("Chat appearing when needed");
        }
    }"""
    )]),
]


def patch_xml_oculus_bindings():
    """Rebind the stick clicks inside the Oculus Touch profile block only."""
    src = open(XRSUGGESTIONS, encoding="utf-8").read()
    if 'ActionName="chat_say"' in src:
        print(f"{XRSUGGESTIONS}: already patched")
        return
    start = src.find('LocalName="Oculus Touch Controllers"')
    end = src.find("</Profile>", start)
    if start == -1 or end == -1:
        sys.exit(f"{XRSUGGESTIONS}: Oculus Touch profile block not found")
    block = src[start:end]
    pairs = [(
        '<Binding ActionName="always_run" Path="/user/hand/left/input/thumbstick/click"/>',
        '<Binding ActionName="chat_say" Path="/user/hand/left/input/thumbstick/click"/>'
    ), (
        '<Binding ActionName="auto_move" Path="/user/hand/right/input/thumbstick/click"/>',
        '<Binding ActionName="chat_mode" Path="/user/hand/right/input/thumbstick/click"/>'
    )]
    for i, (old, new) in enumerate(pairs, 1):
        if block.count(old) != 1:
            sys.exit(f"{XRSUGGESTIONS}: oculus anchor #{i} found {block.count(old)} times (expected 1)")
        block = block.replace(old, new)
    src = src[:start] + block + src[end:]
    open(XRSUGGESTIONS, "w", encoding="utf-8", newline="\n").write(src)
    print(f"{XRSUGGESTIONS}: patched")


def main():
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    for path, marker, pairs in EDITS:
        src = open(path, encoding="utf-8").read()
        if marker in src:
            print(f"{path}: already patched")
            continue
        for i, (old, new) in enumerate(pairs, 1):
            if src.count(old) != 1:
                sys.exit(f"{path}: anchor #{i} found {src.count(old)} times (expected 1)")
            src = src.replace(old, new)
        open(path, "w", encoding="utf-8", newline="\n").write(src)
        print(f"{path}: patched")
    patch_xml_oculus_bindings()
    print("done")


if __name__ == "__main__":
    main()
