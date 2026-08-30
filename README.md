# TES3MP VR for Meta Quest 2

Standalone VR multiplayer Morrowind for Quest 2/3: **TES3MP 0.8.0** (protocol 9,
compatible with official 0.8.0 servers) merged with **OpenMW-VR** on OpenMW 0.47,
running natively on the headset via OpenXR — no PC required.

Built on the shoulders of:
- [TES3MP](https://github.com/TES3MP/TES3MP) — multiplayer for OpenMW (David Cernat & Stanislav Zhukov)
- [OpenMW-VR](https://gitlab.com/madsbuvi/openmw) — VR port of OpenMW (madsbuvi)
- [VRtes3mp](https://github.com/Arawenz/VRtes3mp) — the TES3MP × OpenMW-VR merge (Arawenz)
- [openmw-android](https://github.com/xyzz/openmw-android) — Android port & buildscripts (xyzz)
- [openmw-vr-quest](https://github.com/tomups/openmw-vr-quest) — first OpenMW-VR × Android combination (tomups, mmry2940)

License: GPLv3. Requires original Morrowind data files.

## What works on device
- Full TES3MP multiplayer: chargen, world/actor/container sync, chat
- Stereo OpenXR rendering through gl4es with a raw-GLES3 present path
- VR hands (Oculus Touch), realistic melee combat, wrist HUD & chat
- Cyrillic text (win1251 game data + TTF chat font with Cyrillic glyph ranges)
- Launcher panel: game data picker, mod/BSA/grass load order, server address,
  VR settings (turning, per-eye resolution, refresh rate, HUD position)

## Quest-specific fixes carried by this repo
All engine-side changes are applied to the generated source tree by idempotent
patchers in `buildscripts/quest-patches/` (hooked into `setup-source.sh`):

| Patcher | What it does |
|---|---|
| `patch_openxr_android_init` | `xrInitializeLoaderKHR` + `XR_KHR_android_create_instance` |
| `patch_quest_swapchain_blit` | presents frames with raw GLES3 (gl4es virtualizes texture names; its quad-"blit" never reaches runtime swapchain images) + ESSL-compatible gamma shader |
| `patch_vr_chat_buttons` | pointer-clickable Type/Show-Hide buttons on the chat window |
| `patch_vr_chat_font` | Cyrillic glyph ranges for the chat TTF font |
| `patch_vr_chat_actions` | chat input / visibility on thumbstick clicks (OpenXR actions) |
| `patch_vr_refresh_rate` | `XR_FB_display_refresh_rate` (72/90/120 Hz from the launcher) |

Also patched along the way: null-safe `omwSurfaceDestroyed` (Oculus shell kills
the 2D surface mid-startup), `libGL.so` packaging, TES3MP wire-compat
(`resources/version` pinned to the official 0.8.0 hash so the RakNet connection
password matches stock servers), `force shaders` baked into VR overrides
(the FFP multitexture path calls `glClientActiveTexture`, which GLES lacks).

## Building (WSL/Linux)
```bash
cd buildscripts
bash setup-source.sh              # populate openmw-vr/ from VRtes3mp + apply quest-patches
./build.sh --arch arm64           # deps + engine (first build takes a while)
bash install_and_apk.sh           # package the APK (needs JAVA_HOME, ANDROID_HOME)
# -> android/app/build/outputs/apk/mainline/release/OpenMW-VR_release.apk
```

## Installing
1. `adb install -r OpenMW-VR_release.apk`
2. Push game data: `/sdcard/Morrowind/Morrowind.ini` + `/sdcard/Morrowind/Data Files/`
3. Launch **TES3MP VR** from Unknown Sources → pick the folder, set mods & server → LAUNCH

Controller bindings can be remapped by editing
`/sdcard/tes3mpvr/config/xrcontrollersuggestions.xml` (user copy wins).

## Server compatibility
Client is TES3MP **0.8.0 / protocol 9**. Use the official
[tes3mp-server 0.8.0](https://github.com/TES3MP/TES3MP/releases/tag/tes3mp-0.8.0)
builds. `tes3mp-server-default.cfg` and `requiredDataFiles.json` on the server
must allow your content list.
