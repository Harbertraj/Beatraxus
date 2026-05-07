cmake_minimum_required(VERSION 3.22.1)

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

# ✅ Link SOXR
target_link_libraries(
    beatraxus_dsp
    ${log-lib}
    android
    soxr
)
