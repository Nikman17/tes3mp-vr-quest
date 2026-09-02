# TES3MP VR for Meta Quest 2

Standalone VR multiplayer Morrowind for Quest 2/3: **TES3MP 0.8.1** (protocol 10,
compatible with official 0.8.1 servers) merged with **OpenMW-VR** on OpenMW 0.47,
running natively on the headset via OpenXR — no PC required.

Built on the shoulders of:
- [TES3MP](https://github.com/TES3MP/TES3MP) — multiplayer for OpenMW (David Cernat & Stanislav Zhukov), including the official `0.8.1-vr` branch
- [OpenMW-VR](https://gitlab.com/madsbuvi/openmw) — VR port of OpenMW (madsbuvi)
- [VRtes3mp](https://github.com/Arawenz/VRtes3mp) — the TES3MP × OpenMW-VR merge (Arawenz)
- [openmw-android](https://github.com/xyzz/openmw-android) — Android port & buildscripts (xyzz)
- [openmw-vr-quest](https://github.com/tomups/openmw-vr-quest) — first OpenMW-VR × Android combination (tomups, mmry2940)

License: GPLv3. Requires original Morrowind data files.

## What works on device
- Full TES3MP multiplayer against stock 0.8.1 servers: chargen (with
  launcher-provided character name auto-filled), world/actor/container sync, chat
- **True singleplayer**: the APK carries TWO engines — `libopenmw.so` (TES3MP
  client) and `libopenmw-sp.so` (vanilla OpenMW-VR, no networking layer, full
  quest/script compatibility). The launcher picks one per mode.
  (An arm64 `tes3mp-server` also builds from this tree — `make tes3mp-server` +
  `quest-patches/patch_server_android.py` — planned as a separate host app.)
- Stereo OpenXR rendering through gl4es with a raw-GLES3 present path
- VR hands (Oculus Touch, standard OpenMW-VR bindings), realistic melee combat,
  wrist HUD, wrist virtual keyboard
- TES3MP chat on a front panel: hidden by default, pops up on new messages,
  toggled from the VR meta menu; pointer-clickable Type/Show-Hide buttons
- Cyrillic text (win1251 game data + TTF chat font with Cyrillic glyph ranges)
- Launcher panel: game data picker, mod/BSA/grass load order (with a
  Project Nirn one-click preset), character name, server address:port,
  VR settings (turning, per-eye resolution, refresh rate, HUD position,
  graphics preset)

## Graphics presets (launcher → VR settings)
| Preset | view distance | distant terrain | grass | normal maps |
|---|---|---|---|---|
| Performance | 2048 | off | off | off |
| Balanced | 4096 | on | 50% | off |
| Quality | 5120 | on | 100%, 2 cells | objects + terrain |

Shadows and the water shader stay off on all presets (Quest GPU budget).

## Quest-specific fixes carried by this repo
All engine-side changes are applied to the generated source tree by idempotent
patchers in `buildscripts/quest-patches/` (hooked into `setup-source.sh`):

| Patcher | What it does |
|---|---|
| `patch_openxr_android_init` | `xrInitializeLoaderKHR` + `XR_KHR_android_create_instance` |
| `patch_quest_swapchain_blit` | presents frames with raw GLES3 (gl4es virtualizes texture names; its quad-"blit" never reaches runtime swapchain images) + ESSL-compatible gamma shader |
| `patch_vr_chat_buttons` | pointer-clickable Type/Show-Hide buttons on the chat window |
| `patch_vr_chat_font` | Cyrillic glyph ranges for the chat TTF font |
| `patch_vr_chat_actions` | chat_say / chat_mode OpenXR actions (unbound by default; available for remapping) |
| `patch_vr_refresh_rate` | `XR_FB_display_refresh_rate` (72/90/120 Hz from the launcher) |
| `patch_fatal_log_order` | fatal errors hit the log file before the blocking SDL message box |
| `patch_vr_vkb_and_autoname` | chargen name prefill from the launcher (auto-accepted in MP) |
| `patch_vr_chat_ui_v2` | wrist keyboard, hidden-by-default front-panel chat, standard Touch stick bindings |
| `tes3mp-0.8.1.patch` | protocol 9→10 upgrade (verbatim upstream `0.8.0-vr..tes3mp-0.8.1-vr` diff) |
| `patch_server_android` | dedicated server compiles under aarch64/clang (va_list struct mapping, constexpr signature table mirrored by a runtime address table) |

Also fixed along the way: mwmp hooks silently dropped by the upstream VR merge
(chargen never advanced → every player stayed "Unlogged"), per-engine static
payload management keyed to app version+flavor+install time, null-safe
`omwSurfaceDestroyed`, `libGL.so` packaging, TES3MP wire-compat
(`resources/version` pinned to the `tes3mp-0.8.1` tag hash), `force shaders`
baked into VR overrides, and the `com.oculus.intent.category.VR` manifest
category (without it VrShell hosts the game as a flat 2D panel and the OpenXR
session never reaches FOCUSED).

## Building (WSL/Linux)
```bash
cd buildscripts
bash setup-source.sh              # populate openmw-vr/ from VRtes3mp + apply quest-patches
./build.sh --arch arm64           # deps + engine (first build takes a while)
bash build-sp-engine.sh           # vanilla singleplayer engine (openmw_vr target)
bash install_and_apk.sh           # package the APK (needs JAVA_HOME, ANDROID_HOME)
# -> android/app/build/outputs/apk/mainline/release/OpenMW-VR_release.apk
```

## Installing
1. `adb install -r OpenMW-VR_release.apk`
2. Push game data: `/sdcard/Morrowind/Morrowind.ini` + `/sdcard/Morrowind/Data Files/`
3. Launch **TES3MP VR** from Unknown Sources → pick the folder, set mods,
   character name and server → PLAY

Controller bindings follow the standard OpenMW-VR scheme (sticks: move/turn,
stick clicks: always run / auto move, triggers: jump / activate, grips:
sneak / pointer, A/X: ready weapon/spell, B: inventory, Y: rest, menu: meta
menu). Remap by editing `/sdcard/tes3mpvr/config/xrcontrollersuggestions.xml`
(user copy wins).

## Server compatibility
Client is TES3MP **0.8.1 / protocol 10**. Use the official
[tes3mp-server 0.8.1](https://github.com/TES3MP/TES3MP/releases/tag/tes3mp-0.8.1)
builds. `tes3mp-server-default.cfg` and `requiredDataFiles.json` on the server
must allow your content list.

## Known issues / roadmap
- The launcher is a 2D panel, so tools like Quest Game Optimizer classify the
  app as "Non-VR" — a native VR launcher (with 360° skybox) is planned
- VR-side combat/camera mwmp hooks (realisticcombat, vrcamera) still run the
  older merge generation — melee swing sync will improve in a future update
- Parallax depends on the texture pack shipping height data in the normal-map
  alpha; with plain `_n.dds` you only get normal mapping
- No in-game FPS counter yet; use `adb logcat` or QGO metrics for now
