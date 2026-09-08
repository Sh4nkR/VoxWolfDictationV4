cmake_minimum_required(VERSION 3.22.1)
project(voxwolf_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# whisper.cpp source — built from the pinned submodule
# app/src/main/cpp -> repo root is four levels up
if(NOT DEFINED WHISPER_DIR)
    set(WHISPER_DIR "${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp")
endif()

# Flags per SPEC §4.8
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

# Disable OpenMP — Exynos 9825 shows worse tail latency with OpenMP thread pools
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_NO_OPENMP ON CACHE BOOL "" FORCE)

# No accelerate on ARM
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)

# Build ggml + whisper as static libraries
add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp)

# Our thin JNI bridge — five functions, no others
add_library(voxwolf_jni SHARED whisper_jni.cpp)

target_include_directories(voxwolf_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(voxwolf_jni
    whisper
    log
)
