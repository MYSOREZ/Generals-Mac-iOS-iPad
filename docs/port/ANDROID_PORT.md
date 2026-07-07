# Zero Hour on Android — Port Guide

**Status: CI produces a real, installable APK.** GitHub Actions (§3, Option A)
builds and links the full engine, compiles DXVK's d3d8/d3d9 for Android, and
packages a signed APK — first achieved 07/07/2026. A device install then
surfaced two real runtime bugs (missing `libgamespy.so`, a D3D device-creation
crash on fresh installs — see §6) that are now fixed in source; the
verification checklist at the bottom is the remaining on-device bring-up work,
mirroring how the iOS port (see [`PORTING_PLAYBOOK.md`](PORTING_PLAYBOOK.md))
was hardened.

**Related community work, worth tracking:**
- [tarek369/GeneralsZH-Android](https://github.com/tarek369/GeneralsZH-Android)
  — an independent Android port of the same GeneralsX lineage (same DXVK
  D3D8→Vulkan approach, same directory layout, same `package-android-zh.sh`
  naming). Its [`android.md`](https://github.com/tarek369/GeneralsZH-Android/blob/main/android.md)
  engineering log is where the D3D format-selection bug in §6 was traced from;
  worth re-checking for new findings as their v0.1-android alpha matures.
- [p0ls3r/GenLauncher](https://github.com/p0ls3r/GenLauncher) — a Windows-only
  C# mod-management launcher for Generals/Zero Hour (symlink-based mod
  isolation, not portable to Android's sandboxed storage model as-is). Not
  integrated; a from-scratch Android launcher activity (mod list, GameData
  picker, "verify install" diagnostics) inspired by its UX is a plausible
  future addition, not a port of the C# code itself.

---

## 0. Architecture (what changes vs iOS — mostly: less)

```
Game code (1.6M LOC C++, unmodified game logic, loads retail .big assets)
  │
  ├─ Windowing/input ........ SDL3 (in-tree FetchContent) + SDLActivity Java shell
  ├─ Rendering ............... DirectX 8 calls → DXVK 2.6 d3d8/d3d9 (.so)
  │                            → Vulkan → vendor driver (Adreno/Mali)   ← no MoltenVK!
  ├─ Audio ................... OpenAL (openal-soft; OpenSL/AAudio backends)
  ├─ Video ................... FFmpeg 8.1 (vcpkg, static)
  ├─ Text .................... FreeType + bundled .ttf fonts (same iOS bundled-font
  │                            locator; Android has no fontconfig either)
  └─ App shell ............... android/ Gradle project; game = libmain.so loaded
                               by SDL3's SDLActivity; assets in external files dir
```

Android is the *easier* mobile target on the graphics axis: Vulkan is the
native GPU API, so one whole translation layer (MoltenVK) and its capability
mismatches drop out of the stack. What Android adds instead is the app-model
plumbing: the game must be a shared library in an activity process, storage is
scoped, and the GPU driver landscape is fragmented (see §2).

The touch gesture state machine, app-lifecycle render gate, resolution
injection, and bundled-font lookup from the iOS port apply 1:1 — they are now
compiled under a shared `SAGE_MOBILE_PLATFORM` guard
(iOS `TARGET_OS_IPHONE` **or** `__ANDROID__`) rather than iOS-only.

## 1. What was changed for Android (file manifest)

| File | Purpose |
|---|---|
| `CMakePresets.json` | `android-vulkan` preset: vcpkg + chainloaded NDK toolchain, arm64-v8a, API 28, `SAGE_DXVK_USE_LOCAL_FORK=ON` |
| `cmake/triplets/arm64-android.cmake` | overlay triplet pinning API level for vcpkg-built deps |
| `cmake/meson-arm64-android-cross.ini.in` | DXVK meson cross file (NDK clang, static libc++ into the DXVK libs) |
| `cmake/dx8.cmake` | `elseif(ANDROID)` branch: builds DXVK d3d8/d3d9 from the local fork with meson; same sdl3.pc trick as macOS (silent-SDL2-WSI trap); artifact copy to build root |
| `Patches/dxvk-android.patch` | unversioned `.so` names — APKs can't carry `libdxvk_d3d9.so.0.20600` + symlinks; verified to apply cleanly together with `dxvk-ios.patch` (whose WSI pixel-size fix Android also wants) |
| `cmake/sdl3.cmake` | Android: no system libpng (stb decodes PNG), no TIF/WEBP backends |
| `Core/.../WW3D2/CMakeLists.txt` | `SAGE_USE_FREETYPE` + Freetype link on Android; fontconfig excluded |
| `Core/.../WW3D2/render2dsentence.{h,cpp}` | bundled-font locator now iOS **and** Android |
| `GeneralsMD/Code/Main/CMakeLists.txt` | Android: `z_generals` builds as `libmain.so` (SDLActivity convention) instead of an executable |
| `GeneralsMD/Code/Main/SDL3Main.cpp` | Android env bootstrap: HOME→internal storage, cwd→external files dir (or its `GameData/`), DXVK cache→app cache dir, stderr→pullable log file with rotation, Options.ini seeding; fullscreen/immersive; `SDL_HINT_ANDROID_BLOCK_ON_PAUSE=0`; OpenAL Linux workarounds excluded on Android (they would mute OpenSL/AAudio) |
| `GeneralsMD/.../SDL3GameEngine.cpp` | touch gestures + lifecycle render gate generalized to `SAGE_MOBILE_PLATFORM` |
| `android/` | Gradle shell: `GeneralsZHActivity extends SDLActivity`, asset extraction, missing-game-data dialog, placeholder adaptive icon |
| `scripts/build/android/{build,package}-android-zh.sh` | build + verify artifacts (AArch64, `Sdl3WsiDriver` compiled in), stage jniLibs/Java/assets, gradle assemble |
| `vcpkg.json` | fontconfig excluded on android; ffmpeg enabled for android |

`dx8wrapper.cpp` needed **no change**: its existing Linux branch dlopens
`libdxvk_d3d8.so` by bare name, which on Android resolves through the app's
linker namespace to the APK-packaged library. Same for DXVK's Vulkan loader:
its non-Apple list already tries `libvulkan.so` first — that *is* Android's
system Vulkan loader.

## 2. The device / driver matrix (read this before filing "black screen" bugs)

DXVK 2.6 requires **Vulkan 1.3** with a handful of features (robustness2,
null descriptors, etc.). That, not CPU or OS version, decides whether a device
can run this port.

| Device | SoC / GPU | Vulkan | Verdict |
|---|---|---|---|
| **Poco F8 Pro** | Snapdragon 8 Elite / **Adreno 830** | 1.3+ (excellent proprietary driver) | **Primary target.** This is the device to bring the port up on. GPU-wise a 2003 title is a rounding error; expect native-res 120 Hz. |
| **Redmi Note 8 Pro** | Helio G90T / **Mali-G76 MC4** | **1.1 only** | **Not supported by the DXVK 2.6 path.** The APK installs (manifest gate is 1.1) but D3D init will fail with a clear log message. Options below. |

**Mali-G76 / Vulkan 1.1 options** (in order of realism):
1. **d3d8to9 + DXVK 1.10.x** — DXVK's 1.10 branch runs on Vulkan 1.1, but has
   no d3d8 frontend (d3d8 arrived in 2.4). Chaining the standalone `d3d8to9`
   shim in front of `libdxvk_d3d9.so` 1.10.3 is the plausible route; it is a
   separate integration effort and untested here.
2. **Zink-style GL fallback / software** — not worth it for this GPU class.
3. Accept it as a casualty: the G90T is a 2019 midrange chip whose Mali driver
   is frozen; even Winlator-class emulation struggles there for the same reason.

**About the helper repos provided alongside this one:**
- **Turnip_drivers_adreno / Mesa Turnip** — the open-source Vulkan driver for
  **Adreno 6xx/7xx**. It does *not* support the Adreno 830 (a8xx support is
  still maturing in Mesa), and the 8 Elite's stock driver is already Vulkan
  1.3-complete, so Turnip is **not needed for either of the target devices**.
  It becomes relevant for third-party devices with old/broken vendor drivers
  (e.g. Adreno 642L phones), via the AdrenoTools loading mechanism below.
- **AdrenoToolsDrivers** — packaged driver bundles consumed by
  [libadrenotools](https://github.com/bylaws/libadrenotools), which lets an app
  load a replacement Vulkan driver *into its own process* (how Winlator and the
  Switch emulators do it). A future enhancement here: an optional
  libadrenotools hook before `SDL_Vulkan_LoadLibrary`, letting users pick a
  Turnip build from a folder. Deliberately out of scope for the first bring-up
  — the primary device doesn't need it.
- **Winlator / MiceWine** — the *other* way to run Zero Hour on Android: the
  unmodified Windows binary under Wine + Box64 + DXVK-as-DLLs. It works today
  but pays the x86→ARM emulation tax and fights input/latency. This repo is the
  native path: the real engine compiled for ARM64, zero emulation, RTS-tuned
  touch controls. The Winlator tree remains valuable as a **reference** for
  Android-side DXVK configuration and driver quirks.

## 3. Building

### Option A — GitHub Actions (no local NDK/toolchain needed)

`.github/workflows/build-android.yml` builds the whole stack on a GitHub-hosted
runner and uploads a ready-to-sideload APK as a workflow artifact:
vcpkg deps → DXVK d3d8/d3d9 → `libmain.so` → Gradle `assembleDebug`, with the
same artifact verification (`Sdl3WsiDriver` compiled in, AArch64 ELF) the local
build script does.

Trigger it from the **Actions** tab → *Build Android* → *Run workflow*, or just
push to `main`/`claude/**` touching engine or `android/` files. Download the
`GeneralsXZH-android-<run>.apk` artifact from the run summary and `adb install`
it (or transfer + tap-install on the phone).

**Every CI build is signed with the same committed debug key**
(`android/app/debug.keystore` — a fixed, non-secret debug key checked into the
repo instead of the machine-local key Android tooling normally auto-generates)
and gets a strictly increasing `versionCode` from the workflow run number.
That combination is what makes consecutive CI builds installable **as updates
over each other** — tap a newer APK on the phone without uninstalling first —
instead of Android refusing the install over a signature mismatch.

**On a fork, Actions must be enabled once**: GitHub disables workflow runs on
forks by default. Go to the repo's **Actions** tab → click **"I understand my
workflows, go ahead and enable them"** (or Settings → Actions → General →
"Allow all actions and reusable workflows"). This is a one-time, per-repo
setting; it isn't something a workflow file can turn on for itself.

### Option B — Local build

Host: Linux or macOS.

```sh
# One-time
git clone <this repo> && cd <repo>
git submodule update --init references/fbraz3-dxvk
git clone https://github.com/microsoft/vcpkg ~/vcpkg && ~/vcpkg/bootstrap-vcpkg.sh
export VCPKG_ROOT=~/vcpkg
# Android Studio SDK Manager (or cmdline-tools): install NDK 26+, platform 35, build-tools
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/<version>
# meson + ninja + pkg-config via pip/brew/apt

# Build native code (game -> libmain.so, DXVK -> libdxvk_d3d8/9.so) and verify
./scripts/build/android/build-android-zh.sh

# Stage everything into the Gradle shell and produce the APK
./scripts/build/android/package-android-zh.sh --install
```

The first configure builds vcpkg deps (ffmpeg, curl+openssl, freetype…) for
`arm64-android` — expect 30–60 minutes cold.

## 4. Game data

No assets ship in the APK (2.7 GB, and they're the user's own). After
installing:

```sh
# From a machine that has the game (see scripts/get-assets.sh for SteamCMD):
adb push ~/GeneralsX/GeneralsZH/. \
    /storage/emulated/0/Android/data/com.generalsx.zerohour/files/
```

or copy the same files over USB/MTP with any file manager into
`Android/data/com.generalsx.zerohour/files/`. Both target devices allow this
for the app's own directory without extra permissions. Expected contents:
`*.big`, `Data/`, `ZH_Generals/`, plus the auto-extracted `fonts/`,
`dxvk.conf`, `DefaultOptions.ini` (the app extracts those from the APK on
first launch). A `GameData/` subfolder is also honored if you prefer keeping
the root clean.

User data (Options.ini, saves, map cache) lives in **internal** storage via
`HOME` (`/data/data/com.generalsx.zerohour/files/.local/share/GeneralsX/…`) and
survives asset reshuffles. The DXVK shader cache goes to the app cache dir
(OS-purgeable). The engine log is mirrored to
`files/generals-stderr.log` (+ `-prev.log` from the previous session) next to
the game data — pull it with `adb pull` when reporting issues; native stderr is
otherwise invisible on Android.

## 5. Verification checklist for first device bring-up

In dependency order; each gate isolates a failure class (the iOS port's ladder,
§8.2 of the playbook):

1. **vcpkg deps build for arm64-android** — watch ffmpeg and curl/openssl; both
   are supported by upstream vcpkg but versions move. `PKG_CONFIG_PATH` is
   blanked by the preset so host libs can't leak.
2. **Game code compiles under NDK clang/bionic** — expect a small round of
   fixes: bionic lacks some glibc-isms the desktop Linux build may lean on
   (`glob.h` exists since API 28 — that's part of why minSdk is 28).
3. **DXVK meson cross-build** — `dxvk-android.patch` + `dxvk-ios.patch` apply
   automatically at configure. Verify after build (the build script does):
   `strings libdxvk_d3d9.so | grep Sdl3WsiDriver`, `llvm-readelf -h` says
   AArch64, and `llvm-readelf -d libdxvk_d3d8.so` shows `SONAME libdxvk_d3d8.so`
   (no `.0` suffix — that's what the android patch is for).
4. **APK loads: `System.loadLibrary("main")` succeeds** — failure here =
   missing DT_NEEDED in jniLibs (check `llvm-readelf -d libmain.so` against the
   staged file list).
5. **D3D init: DXVK creates a device** — check `generals-stderr.log` for
   `Direct3DCreate8 returned` non-null and the adapter line naming the real GPU
   (`Adreno 830`). Failure modes: Vulkan feature gaps (Mali!), swapchain/WSI.
6. **Menu renders at native res** — the project's true halfway point.
7. **Touch controls** — tap/drag/long-press/two-finger pan/pinch, then the
   corner-tap scaling check (synthetic events carry windowID; wrong scaling
   lands taps off toward screen edges).
8. **Background/resume torture** — app switcher in and out ×10 during a
   skirmish; the lifecycle gate must keep DXVK off the dead surface. Android
   *destroys* the surface on background (unlike iOS which only seizes it) — if
   resume shows a black screen, DXVK's `VK_ERROR_SURFACE_LOST` handling needs
   the next round of work.
9. **Audio** — openal-soft must pick OpenSL or AAudio (the desktop-Linux
   `ALSOFT_DRIVERS` overrides are explicitly compiled out on Android). EVA,
   music, unit responses; then the §8.2/8.3 playbook regressions (stuck
   `disallowSpeech`, chirping zombie streams) — those fixes are in the shared
   code and should just hold.
10. **10-minute skirmish stability**, then a full Generals Challenge run
    (exercises the radar-format fallback fixed in `W3DRadar.cpp` — also shared
    code, also expected to hold).

## 6. Known gaps / next steps

### Fixed from real-device testing (07/07/2026)

- **`libgamespy.so` missing from the APK → `dlopen` crash on launch.**
  GamespySDK's own `CMakeLists.txt` does
  `set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})` — and
  `CMAKE_BINARY_DIR` names the *outermost* project's build dir even from
  inside a FetchContent'd subproject, so the library lands directly at
  `${BUILD_DIR}/libgamespy.so`, not under `_deps/gamespy-build/` like every
  other FetchContent output in this tree. `package-android-zh.sh` now looks
  there, and every SDL3_image/openal/gamespy library is a hard packaging
  failure instead of a warning. CI additionally verifies, via
  `llvm-readelf -d`, that every non-system `DT_NEEDED` of `libmain.so` is
  actually present in the built APK — the general form of this check, so the
  next missing library fails CI instead of shipping.
- **D3D device creation fails on a fresh install (`D3DERR_NOTAVAILABLE`).**
  `dx8wrapper.cpp` forces `_PresentParameters.Windowed = TRUE` on all
  non-Windows platforms (an existing Linux/Wayland fix), but the *format
  selection* branch a few lines below still keyed off the raw `IsWindowed`
  game setting — which defaults to `false` (fullscreen) with no `Options.ini`
  yet. That took the `else` branch's `Find_Color_And_Z_Mode()`, which has no
  real adapter enumeration under DXVK-native and returns `D3DFMT_UNKNOWN`,
  and `CreateDevice` refuses the mismatched result. Fixed by making the
  format-selection branch agree with the same platform reality
  (`useWindowedFormatPath`), plus a `D3DFMT_X8R8G8B8` fallback (Windows-only
  behavior unchanged) when `GetAdapterDisplayMode` itself reports
  `D3DFMT_UNKNOWN`. Traced from
  [tarek369/GeneralsZH-Android](https://github.com/tarek369/GeneralsZH-Android)'s
  engineering log, then independently confirmed against our own
  `dx8wrapper.cpp`.
- **`RTS_GAMEMEMORY_ENABLE=OFF` on the `android-vulkan` preset.** The engine's
  custom pool allocator intercepts global `operator delete` process-wide;
  freeing memory that OpenAL or libc++ containers allocated through the
  *system* allocator via that pool corrupts its bookkeeping. This is the same
  class of conflict the ASAN build config already disables the pool for —
  applied preemptively here rather than waiting for the heap-corruption crash
  tarek369's log describes hitting.

### Investigated, not changed

- **Archive-override priority (`ArchiveFileSystem::loadIntoDirectoryTree`)**:
  tarek369's log describes a `std::multimap` insertion-order bug causing most
  Zero Hour locomotors to be shadowed by base-Generals ones. Independent
  review of our copy: `dirInfo->m_files.insert(fileIt, ...)` with
  `fileIt = m_files.find(token)` is the standard, correct idiom for
  "insert immediately before the first existing entry of this key" in a
  multimap — the C++ standard guarantees O(1) insertion exactly there when the
  hint is valid for that position, which `find(token)` always is here. No
  reproduction on our tree; **flagged for verification during real gameplay
  testing** (compare Zero Hour unit movement/locomotor behavior against
  retail) rather than patched speculatively — this code runs identically on
  every platform this project ships, so a wrong fix here would be a silent,
  wide-blast-radius regression.
- **FFmpeg on Android**: tarek369's log states vcpkg's arm64-android FFmpeg
  port was broken for them and they stubbed it out
  (`RTS_BUILD_OPTION_FFMPEG=OFF`). Our own CI has FFmpeg **on** and links
  successfully against the pinned vcpkg commit (`VCPKG_COMMIT` in
  `build-android.yml`) — contradicts their finding, likely a difference in
  vcpkg version. Left enabled; watch cutscene playback specifically during
  device testing since that's the one path that would surface a silently
  broken decode.
- **Fresh-install language/registry fallback**: tarek369's log describes a
  crash in a `LanguageRegistry::init()`-equivalent when no `Options.ini`
  exists yet. No class by that name exists in our tree (different fork
  lineage); `registryini.cpp`'s `GetStringFromRegistry` already reads through
  an INI-based compat shim rather than a real registry, which should degrade
  gracefully, but this is unverified without a fresh-install device test.

- **Gradle wrapper is not committed** (binary jar). Use a system Gradle 8.x or
  open `android/` once in Android Studio to generate it.
- **Multiplayer**: broken in ALL native ports of this engine (cross-platform
  float determinism); same story as macOS/iOS. Campaign/skirmish/Challenge only.
- **Mali/Vulkan 1.1 path** (Redmi Note 8 Pro): see §2 — d3d8to9 + DXVK 1.10 is
  the researched route if demand exists.
- **libadrenotools driver replacement**: optional future hook for non-target
  Adreno devices with broken vendor drivers (Turnip_drivers_adreno /
  AdrenoToolsDrivers repos are the driver source for that).
- **Back button / IME**: SDL maps Back to `AC_BACK`; deciding whether it should
  open the in-game menu is a UX pass for after bring-up. On-screen keyboard for
  save names needs `SDL_StartTextInput` verification (the engine already gates
  text input on entry-field focus).
- **Performance**: no tuning expected to be needed (2003 game, 2024+ silicon),
  but `CADisableMinimumFrameDurationOnPhone`'s Android analog — high-refresh
  frame pacing via `SDL_HINT_ANDROID_LOW_LATENCY_AUDIO` and Swappy-style pacing
  — is unexplored.
