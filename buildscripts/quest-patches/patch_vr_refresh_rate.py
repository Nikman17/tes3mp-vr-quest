#!/usr/bin/env python3
"""Quest display refresh rate support (XR_FB_display_refresh_rate).

Reads `[VR] display refresh rate` from settings (written by the launcher's
VR SETTINGS panel) and requests it right after xrBeginSession. Quest 2
supports 60/72/90/120 Hz. Falls back gracefully when the extension or the
requested rate is unavailable.

Idempotent; applied to buildscripts/openmw-vr by setup-source.sh.
"""
import sys

PLATFORM = "../openmw-vr/apps/openmw/mwvr/openxrplatform.cpp"
MANAGER = "../openmw-vr/apps/openmw/mwvr/openxrmanagerimpl.cpp"

EDITS = [
    # 1. Enable the (optional) extension on Android
    (PLATFORM, "XR_FB_display_refresh_rate", [(
        """#ifdef __ANDROID__
        // Android runtimes (Meta Quest) require the VM/activity at instance creation
        enableExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, true);
#endif""",
        """#ifdef __ANDROID__
        // Android runtimes (Meta Quest) require the VM/activity at instance creation
        enableExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, true);
        // Optional: lets the user pick 72/90/120 Hz from the launcher
        enableExtension("XR_FB_display_refresh_rate", true);
#endif"""
    )]),
    # 2. Request the configured rate right after the session starts
    (MANAGER, "applyDisplayRefreshRate", [(
        """#include <components/debug/debuglog.hpp>""",
        """#include <components/debug/debuglog.hpp>
#include <components/settings/settings.hpp>"""
    ), (
        """            XrSessionBeginInfo beginInfo{ XR_TYPE_SESSION_BEGIN_INFO };
            beginInfo.primaryViewConfigurationType = mViewConfigType;
            CHECK_XRCMD(xrBeginSession(mSession, &beginInfo));

            break;""",
        """            XrSessionBeginInfo beginInfo{ XR_TYPE_SESSION_BEGIN_INFO };
            beginInfo.primaryViewConfigurationType = mViewConfigType;
            CHECK_XRCMD(xrBeginSession(mSession, &beginInfo));

            applyDisplayRefreshRate();

            break;"""
    ), (
        """    void OpenXRManagerImpl::handleEvents()""",
        """    void OpenXRManagerImpl::applyDisplayRefreshRate()
    {
        // XR_FB_display_refresh_rate: optional; the launcher writes the value
        const float wanted = Settings::Manager::getFloat("display refresh rate", "VR");
        if (wanted <= 0.f)
            return;

        PFN_xrRequestDisplayRefreshRateFB requestRate = nullptr;
        if (XR_FAILED(xrGetInstanceProcAddr(mInstance, "xrRequestDisplayRefreshRateFB",
                reinterpret_cast<PFN_xrVoidFunction*>(&requestRate))) || !requestRate)
        {
            Log(Debug::Verbose) << "XR_FB_display_refresh_rate unavailable, staying at runtime default";
            return;
        }

        XrResult res = requestRate(mSession, wanted);
        if (XR_SUCCEEDED(res))
            Log(Debug::Info) << "Display refresh rate set to " << wanted << " Hz";
        else
            Log(Debug::Warning) << "Failed to set display refresh rate " << wanted << " Hz: " << res;
    }

    void OpenXRManagerImpl::handleEvents()"""
    )]),
    # 3. Declaration
    ("../openmw-vr/apps/openmw/mwvr/openxrmanagerimpl.hpp", "applyDisplayRefreshRate", [(
        """        void handleEvents();""",
        """        void handleEvents();
        void applyDisplayRefreshRate();"""
    )]),
]


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
    print("done")


if __name__ == "__main__":
    main()
