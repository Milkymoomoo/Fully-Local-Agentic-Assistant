cmake_minimum_required(VERSION 3.22.1)
project(belle_llm C CXX)
set(CMAKE_CXX_STANDARD 17)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # armv8.2-a+dotprod+fp16 covers Snapdragon 8 Gen 3, Exynos 2400 and Dimensity
    # chips from the last ~6 years; i8mm is left off for older-phone safety.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(belle_llm SHARED llm_core.cpp llm_jni.cpp)
target_include_directories(belle_llm PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(belle_llm llama android log)
target_link_options(belle_llm PRIVATE "-Wl,-z,max-page-size=16384")

# dav1d (8-bit AV1 decoder, BSD-2) prebuilt as a static lib per ABI: AVIF / AV1 backgrounds on phones without an AV1 codec
set(DAV1D_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/dav1d_prebuilt)
add_library(dav1d STATIC IMPORTED)
set_target_properties(dav1d PROPERTIES IMPORTED_LOCATION ${DAV1D_DIR}/${ANDROID_ABI}/libdav1d.a)
add_library(belle_av1 SHARED av1_jni.c)
target_include_directories(belle_av1 PRIVATE ${DAV1D_DIR}/include)
target_link_libraries(belle_av1 dav1d log)
target_link_options(belle_av1 PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")

# Generic ONNX Runtime runner for the on-phone 3D stages; binds at run time to sherpa-onnx's libonnxruntime.so
add_library(belle_ort SHARED ort_jni.c)
target_include_directories(belle_ort PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/ort_headers)
target_link_libraries(belle_ort log dl)
target_link_options(belle_ort PRIVATE "-Wl,-z,max-page-size=16384")
