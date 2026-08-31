#!/usr/bin/env python3
"""Log fatal errors to the client log BEFORE showing the SDL message box.

On Android SDL_ShowSimpleMessageBox blocks until the dialog is dismissed;
when the app is killed while the box is up, the `Log(Debug::Error)` line
after it never runs and the log file ends with no error message at all,
which makes startup fatals undebuggable. Write the log line first.
"""
import sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = os.path.join(ROOT, "openmw-vr", "components", "debug", "debugging.cpp")

OLD = """            SDL_ShowSimpleMessageBox(0, (appName + ": Fatal error").c_str(), e.what(), nullptr);

        Log(Debug::Error) << "Error: " << e.what();"""

NEW = """            logFatalErrorFirst = true;

        Log(Debug::Error) << "Error: " << e.what();
        logfile.flush();

        if (logFatalErrorFirst)
            SDL_ShowSimpleMessageBox(0, (appName + ": Fatal error").c_str(), e.what(), nullptr);"""

def main():
    src = open(F, encoding="utf-8").read()
    if "logFatalErrorFirst" in src:
        print("patch_fatal_log_order: already applied")
        return
    assert src.count(OLD) == 1, "anchor not found"
    src = src.replace(OLD, NEW)
    # declare the flag right before the catch block's try companion — simplest:
    anchor = "    catch (const std::exception& e)\n    {\n"
    assert src.count(anchor) == 1
    src = src.replace(anchor, "    catch (const std::exception& e)\n    {\n        bool logFatalErrorFirst = false;\n")
    # the #if/#endif around the original single-statement if now wraps the flag set
    open(F, "w", encoding="utf-8", newline="\n").write(src)
    print("patch_fatal_log_order: applied")

if __name__ == "__main__":
    main()
