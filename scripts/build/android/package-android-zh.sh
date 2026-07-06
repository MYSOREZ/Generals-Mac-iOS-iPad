#!/bin/bash
# Package the Android build of Zero Hour into an APK.
#
# Flow:
#   1. Stage the native libraries built by build-android-zh.sh into the Gradle
#      shell's jniLibs (libmain.so, SDL3, SDL3_image, openal, gamespy, DXVK
#      d3d8/d3d9, libc++_shared from the NDK).
#   2. Stage SDL3's Java glue (org.libsdl.app.SDLActivity et al) from the
#      in-tree SDL3 source, so Java and native SDL always match versions.
#   3. Stage small runtime assets bundled into the APK and extracted on first
#      launch: fonts/ (Liberation, renamed), dxvk.conf, DefaultOptions.ini.
#   4. gradle assembleDebug -> app-debug.apk, ready for adb install.
#
# Game .big archives are NOT packaged: the user copies their own game data to
# /storage/emulated/0/Android/data/com.generalsx.zerohour/files/ (see
# docs/port/ANDROID_PORT.md).
#
# Usage: ./scripts/build/android/package-android-zh.sh [--install]
#   --install  adb install the APK to the first connected device
set -euo pipefail

DO_INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --install) DO_INSTALL=1 ;;
        *) echo "ERROR: unknown argument '$arg' (usage: $0 [--install])"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BUILD_DIR="${PROJECT_ROOT}/build/android-vulkan"
ANDROID_DIR="${PROJECT_ROOT}/android"
JNILIBS="${ANDROID_DIR}/app/src/main/jniLibs/arm64-v8a"
JAVA_SDL="${ANDROID_DIR}/app/src/main/java-sdl"
ASSETS="${ANDROID_DIR}/app/src/main/assets/gamedata"
STAGING="${GX_ANDROID_STAGING:-${HOME}/GeneralsX/android-staging}"

# --- 1. native libraries -----------------------------------------------------
GAME_LIB="$(find "${BUILD_DIR}" -name libmain.so -not -path "*/_deps/*" 2>/dev/null | head -1)"
if [[ -z "${GAME_LIB}" ]]; then
    echo "ERROR: libmain.so not found — run ./scripts/build/android/build-android-zh.sh first."
    exit 1
fi

rm -rf "${JNILIBS}"
mkdir -p "${JNILIBS}"
cp "${GAME_LIB}" "${JNILIBS}/libmain.so"

# Required runtime .so set. Fail loudly on any missing file: a stale or partial
# stage produces an APK that dies at System.loadLibrary / D3D init.
declare -a REQUIRED_LIBS=(
    "_deps/sdl3-build/libSDL3.so"
    "libdxvk_d3d8.so"
    "libdxvk_d3d9.so"
)
for rel in "${REQUIRED_LIBS[@]}"; do
    src="${BUILD_DIR}/${rel}"
    if [[ ! -f "${src}" ]]; then
        echo "ERROR: required library missing: ${src}"
        exit 1
    fi
    cp "${src}" "${JNILIBS}/"
done

# Optional libs: present in default configs, but their absence is survivable
# or config-dependent — warn, don't die. (SDL3_image/openal are DT_NEEDED by
# libmain.so in default configs, so a missing copy WILL fail at load: treat a
# warning here as an error unless you know your config changed.)
declare -a OPTIONAL_LIB_GLOBS=(
    "_deps/sdl3_image-build/libSDL3_image.so"
    "_deps/openal_soft-build/libopenal.so"
    "_deps/gamespy-build/libgamespy.so"
)
for rel in "${OPTIONAL_LIB_GLOBS[@]}"; do
    src="$(ls ${BUILD_DIR}/${rel} 2>/dev/null | head -1 || true)"
    if [[ -n "${src}" && -f "${src}" ]]; then
        cp "${src}" "${JNILIBS}/"
    else
        found="$(find "${BUILD_DIR}/_deps" -maxdepth 2 -name "$(basename "${rel}")" 2>/dev/null | head -1)"
        if [[ -n "${found}" ]]; then
            cp "${found}" "${JNILIBS}/"
        else
            echo "WARNING: $(basename "${rel}") not found under ${BUILD_DIR} — APK may fail to load."
        fi
    fi
done

# libc++_shared.so from the NDK (ANDROID_STL=c++_shared)
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "ERROR: ANDROID_NDK_HOME must be set (for libc++_shared.so)."
    exit 1
fi
LIBCXX="$(ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so 2>/dev/null | head -1)"
if [[ -z "${LIBCXX}" ]]; then
    echo "ERROR: libc++_shared.so not found in the NDK sysroot."
    exit 1
fi
cp "${LIBCXX}" "${JNILIBS}/"

echo "==> Staged $(ls "${JNILIBS}" | wc -l | tr -d ' ') native libraries:"
ls -la "${JNILIBS}"

# --- 2. SDL3 Java glue -------------------------------------------------------
SDL_JAVA_SRC="${BUILD_DIR}/_deps/sdl3-src/android-project/app/src/main/java/org/libsdl/app"
if [[ ! -d "${SDL_JAVA_SRC}" ]]; then
    echo "ERROR: SDL3 Java sources not found at ${SDL_JAVA_SRC} (configure/build first)."
    exit 1
fi
rm -rf "${JAVA_SDL}"
mkdir -p "${JAVA_SDL}/org/libsdl/app"
cp "${SDL_JAVA_SRC}"/*.java "${JAVA_SDL}/org/libsdl/app/"
echo "==> Staged SDL3 Java glue ($(ls "${JAVA_SDL}/org/libsdl/app" | wc -l | tr -d ' ') files)"

# --- 3. runtime assets -------------------------------------------------------
mkdir -p "${ASSETS}"

# Fonts (Liberation, renamed to the Windows names the game requests).
if [[ ! -f "${STAGING}/fonts/arial.ttf" ]]; then
    echo "==> Fonts not staged yet; fetching Liberation fonts"
    GX_FONTS="${STAGING}/fonts" "${PROJECT_ROOT}/scripts/build/ios/stage-fonts.sh"
fi
rm -rf "${ASSETS}/fonts"
cp -R "${STAGING}/fonts" "${ASSETS}/fonts"

# dxvk.conf — tuned translation-layer defaults (16x aniso, quiet logs).
if [[ -f "${STAGING}/dxvk.conf" ]]; then
    cp "${STAGING}/dxvk.conf" "${ASSETS}/dxvk.conf"
else
    cat > "${ASSETS}/dxvk.conf" <<'EOF'
# DXVK configuration for GeneralsX Zero Hour on Android.
# Read from the game's working directory at startup.
dxvk.logLevel = none
# RTS camera angles smear terrain with plain trilinear; 16x anisotropic is the
# single biggest perceived-sharpness win and free on modern mobile GPUs.
d3d9.samplerAnisotropy = 16
EOF
fi

# DefaultOptions.ini — seed full detail on first run: the 2003 GPU auto-detect
# doesn't know "Adreno 830" and would silently pick Low LOD + quarter-res textures.
if [[ -f "${STAGING}/DefaultOptions.ini" ]]; then
    cp "${STAGING}/DefaultOptions.ini" "${ASSETS}/DefaultOptions.ini"
else
    cat > "${ASSETS}/DefaultOptions.ini" <<'EOF'
AntiAliasing = 1
BuildingOcclusion = yes
DynamicLOD = no
ExtraAnimations = yes
GameSpyIPAddress = 0.0.0.0
HeatEffects = yes
IPAddress = 0.0.0.0
IdealStaticGameLOD = High
Retaliation = yes
ShowSoftWaterEdge = yes
ShowTrees = yes
StaticGameLOD = High
TextureReduction = 0
UseAlternateMouse = no
UseCloudMap = yes
UseDoubleClickAttackMove = no
UseLightMap = yes
UseShadowDecals = yes
UseShadowVolumes = yes
EOF
fi
echo "==> Staged APK assets:"
find "${ASSETS}" -type f | sed "s|${ASSETS}/|    |"

# --- 4. gradle ---------------------------------------------------------------
cd "${ANDROID_DIR}"
GRADLE_CMD=""
if [[ -x "./gradlew" ]]; then
    GRADLE_CMD="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
else
    echo "ERROR: no gradle wrapper and no 'gradle' on PATH."
    echo "       Either install Gradle 8.x (sdkman/brew/apt) or open android/ once in"
    echo "       Android Studio (which generates the wrapper), then re-run this script."
    exit 1
fi
echo "==> ${GRADLE_CMD} assembleDebug"
"${GRADLE_CMD}" assembleDebug

APK="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "${APK}" ]]; then
    echo "ERROR: expected APK not found at ${APK}"
    exit 1
fi
echo "==> APK: ${APK}"

if [[ $DO_INSTALL -eq 1 ]]; then
    command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found on PATH"; exit 1; }
    echo "==> adb install -r"
    adb install -r "${APK}"
    echo "==> Installed. Game data goes to:"
    echo "    /storage/emulated/0/Android/data/com.generalsx.zerohour/files/"
    echo "    e.g.: adb push ~/GeneralsX/GeneralsZH/. /storage/emulated/0/Android/data/com.generalsx.zerohour/files/"
fi
