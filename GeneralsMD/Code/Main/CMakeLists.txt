# GeneralsX @build Android port 06/07/2026 On Android the game is not a process
# of its own: SDL3's Java shell (SDLActivity in android/) loads the game as a
# shared library named libmain.so and calls its SDL_main. Everywhere else the
# game remains a regular executable.
if(ANDROID)
    add_library(z_generals SHARED)
else()
    add_executable(z_generals WIN32)
endif()

# Use a binary name that doesn't conflict with original game.
# GeneralsX @build BenderAI 15/02/2026 Linux branding update
if("${CMAKE_SYSTEM}" MATCHES "Windows")
    set_target_properties(z_generals PROPERTIES OUTPUT_NAME "generalszh${RTS_BUILD_OUTPUT_SUFFIX}")
elseif(ANDROID)
    # SDLActivity.getLibraries() loads "main" -> libmain.so (SDL convention)
    set_target_properties(z_generals PROPERTIES OUTPUT_NAME main)
else()
    # GeneralsX @build BenderAI 15/02/2026 Linux branding update
    set_target_properties(z_generals PROPERTIES OUTPUT_NAME GeneralsXZH)
endif()

# GeneralsX @build BenderAI 13/02/2026 Windows-specific libraries
if(WIN32)
    target_link_libraries(z_generals PRIVATE
        binkstub
        comctl32
        core_debug
        d3d8
        dinput8
        dxguid
        imm32
        milesstub
        vfw32
        winmm
    )
endif()

# Cross-platform core libraries
target_link_libraries(z_generals PRIVATE
    core_profile_legacy
    d3dx8
    z_gameengine
    z_gameenginedevice
    zi_always
)

# Linux SDL3 windowing/input (Phase 1)
# GeneralsX @build BenderAI 11/02/2026
if(SAGE_USE_SDL3)
    target_link_libraries(z_generals PRIVATE sdl3lib)
endif()

# GeneralsX @feature Android port 10/07/2026 libadrenotools (custom Vulkan
# driver loading, see cmake/adrenotools.cmake) -- linked into libmain.so
# itself since TryLoadCustomVulkanDriver() in SDL3Main.cpp must call
# adrenotools_open_libvulkan() before DXVK's separately-built .so gets a
# chance to dlopen the stock libvulkan.so on its own.
if(ANDROID)
    target_link_libraries(z_generals PRIVATE adrenotools)
endif()

# GeneralsX @feature Android port 12/07/2026 CI passes the GitHub Actions run
# number (the same value baked into the artifact filename, e.g.
# GeneralsXZH-android-run108.apk) via -DANDROID_CI_BUILD_NUMBER so it can be
# logged at app startup -- without this a crash log has no way to prove which
# build produced it, which has already caused us to misattribute a tester's
# log to the wrong build once. Local/non-CI builds don't pass the define, so
# default to 0.
if(NOT DEFINED ANDROID_CI_BUILD_NUMBER)
    set(ANDROID_CI_BUILD_NUMBER 0)
endif()

# TODO Originally referred to build host and user, replace with git info perhaps?
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/GeneratedVersion.h
"#pragma once

#define VERSION_LOCALBUILDNUM 0
#define VERSION_BUILDUSER \"\"
#define VERSION_BUILDLOC \"\"
#define ANDROID_CI_BUILD_NUMBER ${ANDROID_CI_BUILD_NUMBER}
"
)

# Based on original binary values for these variables.
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/BuildVersion.h
"#pragma once

#define VERSION_MAJOR 1
#define VERSION_MINOR 4
#define VERSION_BUILDNUM 601
"
)

if(MSVC)
    target_link_options(z_generals PRIVATE "/NODEFAULTLIB:libci.lib")
endif()

target_include_directories(z_generals PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}
)

target_sources(z_generals PRIVATE
    $<$<BOOL:${WIN32}>:WinMain.cpp>
    $<$<BOOL:${WIN32}>:WinMain.h>
    # Linux SDL3 entry point (Phase 1.5 - TheSuperHackers @build 10/02/2026)
    $<$<NOT:$<BOOL:${WIN32}>>:SDL3Main.cpp>
    # Linux stubs for P2-P3 platform functions (P2-P3 - TheSuperHackers @build 13/02/2026 BenderAI Session 37)
    $<$<NOT:$<BOOL:${WIN32}>>:LinuxStubs.cpp>
    # GeneralsX @build Android port 07/07/2026 Installs as soon as libmain.so is
    # dlopen'd (ELF constructor), before any engine code — captures crashes that
    # happen before SDL3Main.cpp's own logging is ready.
    $<$<BOOL:${ANDROID}>:AndroidCrashHandler.cpp>
)

# RC files optional for MinGW builds
# Icon: LoadIcon() handles missing resource gracefully (uses default system icon)
# Manifest: DPI awareness metadata (nice to have but not essential)
if(MSVC)
    # VS2005 and later adds default manifest, we need to turn it off to prevent conflict with custom manifest
    if(NOT IS_VS6_BUILD)
        target_link_options(z_generals PRIVATE
            "/MANIFEST:NO"
        )
    endif()

    target_sources(z_generals PRIVATE
        RTS.RC
    )
endif()

# Strip debug symbols to separate file for MinGW Release builds
# This creates generalszh${RTS_BUILD_OUTPUT_SUFFIX}.exe.debug (similar to MSVC .pdb files)
if(MINGW AND COMMAND add_debug_strip_target)
    add_debug_strip_target(z_generals)
endif()
