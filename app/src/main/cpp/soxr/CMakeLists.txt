add_library(soxr STATIC
    src/soxr.c
    src/cr.c
    src/cr32.c
    src/cr64.c
    src/filter.c
    src/data-io.c
    src/dbesi0.c
    src/fft4g.c
    src/fft4g32.c
    src/fft4g64.c
    src/vr32.c
)

target_compile_definitions(soxr PRIVATE
    SOXR_LIB
    WITH_CR32=1
    WITH_CR64=1
    WITH_VR32=1
)

target_include_directories(soxr PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)
