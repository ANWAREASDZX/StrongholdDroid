# StrongholdDroid CMakeLists.txt — app-level native build.
#
# This is the entry point invoked by AGP's externalNativeBuild plugin.
# It pulls in libwine_bridge, libaudio_bridge, libinput_bridge, and
# links them against the prebuilt libwine / libbox64 / libpulse shared
# libraries that scripts/build_all.sh drops into PREBUILT_DIR/<ABI>/.
#
# If the prebuilt libs are missing, CMake fails *early* with a clear
# message instead of producing a broken APK.

cmake_minimum_required(VERSION 3.22.1)

# Version MUST match app/build.gradle.kts `versionName` (minus "-debug" suffix).
# Without an explicit VERSION, `PROJECT_VERSION` stays empty and the
# `-DSTRONGHOLDDROID_VERSION=...` compile definition compiles to an empty string.
project(strongholddroid VERSION 0.1.0 LANGUAGES C CXX)

# ---- Build configuration -----------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Hide non-exported symbols in release for smaller/faster binaries
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_options(-fvisibility=hidden -fvisibility-inlines-hidden)
endif()

# ---- Locate prebuilt native libraries ---------------------------------------
if(NOT DEFINED STRONGHOLDDROID_PREBUILT_DIR)
    set(STRONGHOLDDROID_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt")
endif()

set(ABI_DIR "")
if("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    set(ABI_DIR "arm64-v8a")
elseif("${ANDROID_ABI}" STREQUAL "x86_64")
    set(ABI_DIR "x86_64")
else()
    message(FATAL_ERROR
        "Unsupported ABI: '${ANDROID_ABI}'. StrongholdDroid only ships arm64-v8a ")
endif()

set(PREBUILT_ABI_DIR "${STRONGHOLDDROID_PREBUILT_DIR}/${ABI_DIR}")

# Sanity check — fail loud if the native runtime artifacts are missing.
# NOTE: the wine runtime (wine binary, wineserver, builtin DLLs, box64
# wow64 cpu dll, gl4es, libpulse, DXVK) is NOT linked into the JNI library
# — it ships as assets/prebuilt.zip (packaged by scripts/build_apk.sh)
# and is extracted into filesDir at runtime by EnvironmentBuilder.
# The wine loader runs as a separate process (fork/exec, see wine_bridge.cpp).
if(NOT EXISTS "${PREBUILT_ABI_DIR}/libwine/wine" OR NOT EXISTS "${PREBUILT_ABI_DIR}/wow64/wowbox64.dll")
    message(FATAL_ERROR
        "  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"
        "  ! Prebuilt native runtime not found in:\n"
        "  !   ${PREBUILT_ABI_DIR}\n"
        "  ! Expected libwine/wine, libwine/wineserver, wow64/wowbox64.dll,\n"
        "  ! wine_dlls/{aarch64-windows,i386-windows,aarch64-unix}/, libpulse.so,\n"
        "  ! libGL.so, dxvk-wine-dlls/.\n"
        "  !\n"
        "  ! Run scripts/build_all.sh first (or scripts/docker/build.sh)\n"
        "  ! before assembling the APK. See BUILD_GUIDE.md.\n"
        "  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
endif()

# ---- Common compile flags ----------------------------------------------------
add_compile_definitions(
    ANDROID
    "STRONGHOLDDROID_VERSION=\"${PROJECT_VERSION}\""
)

# ---- libstrongholddroid_jni : the JNI glue that Kotlin calls ---------------
add_library(strongholddroid_jni SHARED
    native_jni.cpp
    wine_bridge.cpp
    audio_bridge.cpp
    input_bridge.cpp
)

target_include_directories(strongholddroid_jni
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${PREBUILT_ABI_DIR}/include
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
find_library(vulkan-lib vulkan)
find_library(aaudio-lib aaudio)
find_library(opensles-lib OpenSLES)

# NOTE: the JNI bridge does NOT link against libwine/libpulse/libbox64:
#   • wine runs as a separate process (fork/exec in wine_bridge.cpp)
#   • audio goes through a FIFO owned by AudioBridge (no libpulse calls)
#   • box64 ships as the wow64 cpu dll (wow64/wowbox64.dll) loaded by wine,
#     not as a linked library
target_link_libraries(strongholddroid_jni
    PRIVATE
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${gles-lib}
        ${vulkan-lib}
        ${aaudio-lib}
        ${opensles-lib}
        m
        dl
)

# Strip release binaries
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET strongholddroid_jni POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
                $<TARGET_FILE:strongholddroid_jni>
        COMMENT "Stripping strongholddroid_jni")
endif()
