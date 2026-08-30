#!/usr/bin/env python3
"""Add VR-clickable buttons to the TES3MP chat window (Quest port).

VR controllers do not generate SDL keyboard events, so the stock chat hotkeys
(keySay/keyChatMode) are unreachable in the headset. This adds two MyGUI buttons
to the chat window that the VR pointer can click:
  - "Type..."     -> GUIChat::pressedSay() (focuses the edit box; the VR virtual
                     keyboard opens automatically on EditBox focus)
  - "Show/Hide"   -> toggles CHAT_ENABLED <-> CHAT_HIDDENMODE. The full
                     CHAT_DISABLED state is intentionally skipped in VR: with the
                     window hidden there would be no way to bring it back without
                     a keyboard.

Idempotent; applied to buildscripts/openmw-vr by setup-source.sh.
"""
import sys

LAYOUT = "../openmw-vr/files/mygui/tes3mp_chat.layout"
CPP = "../openmw-vr/apps/openmw/mwmp/GUI/GUIChat.cpp"
HPP = "../openmw-vr/apps/openmw/mwmp/GUI/GUIChat.hpp"

OLD_HISTORY_POS = '<Widget type="EditBox" skin="MW_TextBoxEdit" position="5 5 380 328" align="Stretch" name="list_History">'
NEW_HISTORY_POS = '<Widget type="EditBox" skin="MW_TextBoxEdit" position="5 5 380 296" align="Stretch" name="list_History">'

BUTTONS_BLOCK = """
    <!-- VR pointer buttons: controllers cannot press keyboard chat hotkeys -->
    <Widget type="Button" skin="MW_Button" position="5 306 150 26" align="Left Bottom" name="btn_Say">
        <Property key="Caption" value="Type..."/>
    </Widget>
    <Widget type="Button" skin="MW_Button" position="235 306 150 26" align="Right Bottom" name="btn_Mode">
        <Property key="Caption" value="Show/Hide"/>
    </Widget>

    <!-- Command line -->"""

CPP_CTOR_ANCHOR = """        getWidget(mCommandLine, "edit_Command");
        getWidget(mHistory, "list_History");"""
CPP_CTOR_NEW = """        getWidget(mCommandLine, "edit_Command");
        getWidget(mHistory, "list_History");
        getWidget(mSayButton, "btn_Say");
        getWidget(mModeButton, "btn_Mode");
        mSayButton->eventMouseButtonClick += MyGUI::newDelegate(this, &GUIChat::onSayButtonClicked);
        mModeButton->eventMouseButtonClick += MyGUI::newDelegate(this, &GUIChat::onModeButtonClicked);"""

CPP_METHODS_ANCHOR = """    void GUIChat::pressedSay()"""
CPP_METHODS_NEW = """    void GUIChat::onSayButtonClicked(MyGUI::Widget* _sender)
    {
        // Make sure the window is interactable before opening the input line
        if (windowState == CHAT_DISABLED)
            windowState = CHAT_ENABLED;
        pressedSay();
    }

    void GUIChat::onModeButtonClicked(MyGUI::Widget* _sender)
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
    }

    void GUIChat::pressedSay()"""

HPP_ANCHOR = """        MyGUI::EditBox* mCommandLine;
        MyGUI::EditBox* mHistory;"""
HPP_NEW = """        MyGUI::EditBox* mCommandLine;
        MyGUI::EditBox* mHistory;
        MyGUI::Button* mSayButton;
        MyGUI::Button* mModeButton;"""

HPP_METHOD_ANCHOR = """        void pressedChatMode(); //switch chat mode"""
HPP_METHOD_NEW = """        void pressedChatMode(); //switch chat mode
        void onSayButtonClicked(MyGUI::Widget* _sender);
        void onModeButtonClicked(MyGUI::Widget* _sender);"""

CPP_INCLUDE_ANCHOR = '#include <MyGUI_EditBox.h>'
CPP_INCLUDE_NEW = '#include <MyGUI_EditBox.h>\n#include <MyGUI_Button.h>'


def apply(path, pairs, marker):
    src = open(path, encoding="utf-8").read()
    if marker in src:
        print(f"{path}: already patched")
        return
    for i, (old, new) in enumerate(pairs, 1):
        if src.count(old) != 1:
            sys.exit(f"{path}: anchor #{i} found {src.count(old)} times (expected 1)")
        src = src.replace(old, new)
    open(path, "w", encoding="utf-8", newline="\n").write(src)
    print(f"{path}: patched")


if __name__ == "__main__":
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    apply(LAYOUT, [(OLD_HISTORY_POS, NEW_HISTORY_POS), ("\n    <!-- Command line -->", BUTTONS_BLOCK)], "btn_Say")
    apply(CPP, [(CPP_INCLUDE_ANCHOR, CPP_INCLUDE_NEW), (CPP_CTOR_ANCHOR, CPP_CTOR_NEW), (CPP_METHODS_ANCHOR, CPP_METHODS_NEW)], "onSayButtonClicked")
    apply(HPP, [(HPP_ANCHOR, HPP_NEW), (HPP_METHOD_ANCHOR, HPP_METHOD_NEW)], "onSayButtonClicked")
    print("done")
