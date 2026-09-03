#!/usr/bin/env python3
"""Native QGO-style performance controls (both engine trees).

Quest Game Optimizer works by nudging exactly two OpenXR facilities that the
runtime already advertises to us:
  1. XR_EXT_performance_settings - CPU/GPU clock level hints
  2. XR_FB_foveation(+configuration/update_state) - fixed foveated rendering

This patch wires both natively, driven by launcher-written settings:
  [VR] cpu level / gpu level      (-1 skip, 0..3 = powersave..boost)
  [VR] foveation level            (0 off, 1..3 = low..high)
  [VR] foveation dynamic          (bool)
Also gives the SP tree the same XR_FB_display_refresh_rate support the MP
tree already had. All calls degrade gracefully when unsupported.

Idempotent, CRLF-tolerant.
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
        src = src.replace(old, new, 1)
    write_lf(p, src)
    print(f"{p}: patched")

EXT_LINES = """        enableExtension("XR_EXT_performance_settings", true); // vr_perf
        enableExtension("XR_FB_foveation", true);
        enableExtension("XR_FB_foveation_configuration", true);
        enableExtension("XR_FB_swapchain_update_state", true);
        enableExtension("XR_FB_swapchain_update_state_opengl_es", true);"""

# ── extension enabling ────────────────────────────────────────────────────

apply(os.path.join(MP, "apps", "openmw", "mwvr", "openxrplatform.cpp"), [(
    '''        enableExtension("XR_FB_display_refresh_rate", true);''',
    '''        enableExtension("XR_FB_display_refresh_rate", true);
''' + EXT_LINES
)], marker="vr_perf")

apply(os.path.join(SP, "apps", "openmw", "mwvr", "openxrplatform.cpp"), [(
    '''        enableExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, false);''',
    '''        enableExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, false);
        enableExtension("XR_FB_display_refresh_rate", true); // vr_perf
''' + EXT_LINES
)], marker="vr_perf")

# ── session-time application (openxrmanagerimpl) ──────────────────────────

PERF_FN = """    void OpenXRManagerImpl::applyQuestPerfSettings()
    {
        // vr_perf: XR_EXT_performance_settings - same knobs QGO drives
        int cpu = -1, gpu = -1;
        try { cpu = Settings::Manager::getInt("cpu level", "VR"); } catch (...) {}
        try { gpu = Settings::Manager::getInt("gpu level", "VR"); } catch (...) {}
        if (cpu < 0 && gpu < 0)
            return;

        PFN_xrPerfSettingsSetPerformanceLevelEXT setLevel = nullptr;
        if (XR_FAILED(xrGetInstanceProcAddr(mInstance, "xrPerfSettingsSetPerformanceLevelEXT",
                reinterpret_cast<PFN_xrVoidFunction*>(&setLevel))) || !setLevel)
        {
            Log(Debug::Verbose) << "XR_EXT_performance_settings unavailable";
            return;
        }
        auto toLevel = [](int v) {
            switch (v)
            {
                case 0: return XR_PERF_SETTINGS_LEVEL_POWER_SAVINGS_EXT;
                case 1: return XR_PERF_SETTINGS_LEVEL_SUSTAINED_LOW_EXT;
                case 2: return XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT;
                default: return XR_PERF_SETTINGS_LEVEL_BOOST_EXT;
            }
        };
        if (cpu >= 0)
        {
            XrResult res = setLevel(mSession, XR_PERF_SETTINGS_DOMAIN_CPU_EXT, toLevel(cpu));
            Log(Debug::Info) << "CPU perf level " << cpu << (XR_SUCCEEDED(res) ? " set" : " FAILED");
        }
        if (gpu >= 0)
        {
            XrResult res = setLevel(mSession, XR_PERF_SETTINGS_DOMAIN_GPU_EXT, toLevel(gpu));
            Log(Debug::Info) << "GPU perf level " << gpu << (XR_SUCCEEDED(res) ? " set" : " FAILED");
        }
    }

"""

REFRESH_FN = """    void OpenXRManagerImpl::applyDisplayRefreshRate()
    {
        // vr_perf: XR_FB_display_refresh_rate (launcher writes the value)
        float wanted = 0.f;
        try { wanted = Settings::Manager::getFloat("display refresh rate", "VR"); } catch (...) {}
        if (wanted <= 0.f)
            return;
        PFN_xrRequestDisplayRefreshRateFB requestRate = nullptr;
        if (XR_FAILED(xrGetInstanceProcAddr(mInstance, "xrRequestDisplayRefreshRateFB",
                reinterpret_cast<PFN_xrVoidFunction*>(&requestRate))) || !requestRate)
            return;
        XrResult res = requestRate(mSession, wanted);
        Log(Debug::Info) << "Display refresh rate " << wanted << " Hz "
                         << (XR_SUCCEEDED(res) ? "set" : "FAILED");
    }

"""

def patch_manager(tree, add_refresh):
    p = os.path.join(tree, "apps", "openmw", "mwvr", "openxrmanagerimpl.cpp")
    src = read_norm(p)
    if "applyQuestPerfSettings" in src:
        print(f"{p}: already patched")
        return
    begin_old = """            CHECK_XRCMD(xrBeginSession(mSession, &beginInfo));
"""
    if src.count(begin_old) != 1:
        sys.exit(f"{p}: xrBeginSession anchor x{src.count(begin_old)}")
    calls = "\n            applyQuestPerfSettings();\n"
    if add_refresh:
        calls += "            applyDisplayRefreshRate();\n"
    src = src.replace(begin_old, begin_old + calls, 1)

    fn_anchor = "    void OpenXRManagerImpl::handleEvents()"
    if src.count(fn_anchor) != 1:
        sys.exit(f"{p}: handleEvents anchor missing")
    funcs = PERF_FN + (REFRESH_FN if add_refresh else "")
    src = src.replace(fn_anchor, funcs + fn_anchor, 1)

    if "#include <components/settings/settings.hpp>" not in src:
        inc = "#include <components/debug/debuglog.hpp>"
        if inc not in src:
            sys.exit(f"{p}: include anchor missing")
        src = src.replace(inc, inc + "\n#include <components/settings/settings.hpp>", 1)
    write_lf(p, src)
    print(f"{p}: patched")

    h = os.path.join(tree, "apps", "openmw", "mwvr", "openxrmanagerimpl.hpp")
    hsrc = read_norm(h)
    if "applyQuestPerfSettings" not in hsrc:
        ha = "        void handleEvents();"
        if hsrc.count(ha) != 1:
            sys.exit(f"{h}: handleEvents decl anchor missing")
        decl = "        void handleEvents();\n        void applyQuestPerfSettings();"
        if add_refresh:
            decl += "\n        void applyDisplayRefreshRate();"
        write_lf(h, hsrc.replace(ha, decl, 1))
        print(f"{h}: patched")

patch_manager(MP, add_refresh=False)   # MP already has applyDisplayRefreshRate
patch_manager(SP, add_refresh=True)

# ── foveation on color swapchains (openxrswapchainimpl) ───────────────────

FOV_HELPER = """
namespace
{
    // vr_perf: XR_FB_foveation - apply an FFR profile to a color swapchain
    void applyQuestFoveation(XrInstance instance, XrSession session, XrSwapchain swapchain)
    {
        int level = 0;
        bool dynamic = true;
        try { level = Settings::Manager::getInt("foveation level", "VR"); } catch (...) {}
        try { dynamic = Settings::Manager::getBool("foveation dynamic", "VR"); } catch (...) {}
        if (level <= 0)
            return;
        if (level > 3) level = 3;

        PFN_xrCreateFoveationProfileFB createProfile = nullptr;
        PFN_xrDestroyFoveationProfileFB destroyProfile = nullptr;
        PFN_xrUpdateSwapchainFB updateSwapchain = nullptr;
        xrGetInstanceProcAddr(instance, "xrCreateFoveationProfileFB", reinterpret_cast<PFN_xrVoidFunction*>(&createProfile));
        xrGetInstanceProcAddr(instance, "xrDestroyFoveationProfileFB", reinterpret_cast<PFN_xrVoidFunction*>(&destroyProfile));
        xrGetInstanceProcAddr(instance, "xrUpdateSwapchainFB", reinterpret_cast<PFN_xrVoidFunction*>(&updateSwapchain));
        if (!createProfile || !updateSwapchain)
        {
            MWVR::Log(Debug::Verbose) << "XR_FB_foveation unavailable";
            return;
        }

        XrFoveationLevelProfileCreateInfoFB levelInfo{ XR_TYPE_FOVEATION_LEVEL_PROFILE_CREATE_INFO_FB };
        levelInfo.level = static_cast<XrFoveationLevelFB>(level);
        levelInfo.verticalOffset = 0.f;
        levelInfo.dynamic = dynamic ? XR_FOVEATION_DYNAMIC_LEVEL_ENABLED_FB : XR_FOVEATION_DYNAMIC_DISABLED_FB;

        XrFoveationProfileCreateInfoFB profileInfo{ XR_TYPE_FOVEATION_PROFILE_CREATE_INFO_FB };
        profileInfo.next = &levelInfo;

        XrFoveationProfileFB profile = XR_NULL_HANDLE;
        if (XR_FAILED(createProfile(session, &profileInfo, &profile)))
            return;

        XrSwapchainStateFoveationFB state{ XR_TYPE_SWAPCHAIN_STATE_FOVEATION_FB };
        state.profile = profile;
        XrResult res = updateSwapchain(swapchain, reinterpret_cast<XrSwapchainStateBaseHeaderFB*>(&state));
        MWVR::Log(Debug::Info) << "Foveation level " << level << (dynamic ? " (dynamic)" : "")
                               << (XR_SUCCEEDED(res) ? " applied" : " FAILED");
        if (destroyProfile)
            destroyProfile(profile);
    }
}
"""

def patch_swapchain(tree):
    p = os.path.join(tree, "apps", "openmw", "mwvr", "openxrswapchainimpl.cpp")
    src = read_norm(p)
    if "applyQuestFoveation" in src:
        print(f"{p}: already patched")
        return
    create_anchor = "            auto res = xrCreateSwapchain(xr->impl().xrSession(), &swapchainCreateInfo, &mSwapchain);"
    if src.count(create_anchor) != 1:
        sys.exit(f"{p}: create anchor missing")
    src = src.replace(create_anchor, create_anchor + """
            if (XR_SUCCEEDED(res) && mUsage == Use::COLOR)
                applyQuestFoveation(xr->impl().xrInstance(), xr->impl().xrSession(), mSwapchain);""", 1)

    # helper needs to sit after the includes/usings, before first namespace use
    ns_anchor = "namespace MWVR {"
    alt_anchor = "namespace MWVR\n{"
    if ns_anchor in src:
        src = src.replace(ns_anchor, FOV_HELPER.replace("MWVR::Log", "Log") + "\n" + ns_anchor, 1) \
            if False else src.replace(ns_anchor, ns_anchor + "\n" + FOV_HELPER.replace("MWVR::Log", "Log"), 1)
    elif alt_anchor in src:
        src = src.replace(alt_anchor, alt_anchor + "\n" + FOV_HELPER.replace("MWVR::Log", "Log"), 1)
    else:
        sys.exit(f"{p}: namespace anchor missing")

    if "#include <components/settings/settings.hpp>" not in src:
        first_inc = src.index("#include")
        line_end = src.index("\n", first_inc)
        src = src[:line_end+1] + "#include <components/settings/settings.hpp>\n#include <components/debug/debuglog.hpp>\n" + src[line_end+1:]
    write_lf(p, src)
    print(f"{p}: patched")

patch_swapchain(MP)
patch_swapchain(SP)
print("done")
