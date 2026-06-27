cmake_minimum_required(VERSION 3.22)

project("beatraxus_dsp")

# ✅ C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ✅ Optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -funroll-loops")

# ✅ ENABLE SOXR
set(HAVE_SOXR 1)

# ✅ Add SOXR subdirectory
add_subdirectory(soxr)

add_library(
    beatraxus_dsp
    SHARED
    PowerampStyleDSP.cpp
)

# ✅ Define flag for C++
target_compile_definitions(beatraxus_dsp PRIVATE HAVE_SOXR=1)

# Android log
find_library(log-lib log)

# ✅ Link AAudio, SOXR and MediaNDK
target_link_libraries(
    beatraxus_dsp
    ${log-lib}
    android
    aaudio
    soxr
    mediandk
)

# --- OpenAL Soft, built from the vendored source under third_party/ ---
#
# We deliberately keep it SHARED (the project default) rather than STATIC.
# Reasoning: OpenAL Soft is LGPLv2+. Linking it dynamically (libopenal.so as
# its own shared object inside your APK, rather than merged statically into
# your own native lib) is the long-established, low-friction way to use an
# LGPL library from a closed-source app: you're not distributing a
# derivative work, just dynamically linking against an unmodified library,
# so you are not obligated to open-source your own app code. You ARE
# obligated to: (a) keep the LGPL license text and a notice that you use
# OpenAL Soft somewhere in your app (see licenses/COPYING-openal-soft-LGPLv2.txt
# and ATTRIBUTION.md), and (b) make the OpenAL Soft *source* available /
# reproducible (this folder + upstream git history serves that purpose).
# If you ever modify openal-soft's own source, those modifications must be
# shared under LGPL too.
#
# Turn off everything we don't need for an Android app build (examples,
# command-line utils, install rules meant for desktop packaging).
set(LIBTYPE "SHARED" CACHE STRING "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_REQUIRE_OPENSL ON CACHE BOOL "" FORCE)   # Android backend

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/openal-soft
    ${CMAKE_CURRENT_BINARY_DIR}/openal-soft-build
)

# --- Our own thin JNI bridge ---
add_library(openal_jni_bridge SHARED
    openal_jni_bridge.cpp
)

target_link_libraries(openal_jni_bridge
    OpenAL::OpenAL
    ${log-lib}
)

target_include_directories(openal_jni_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/openal-soft/include
)

