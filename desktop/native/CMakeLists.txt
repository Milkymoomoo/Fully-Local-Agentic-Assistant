# Native libraries for the Windows (and Linux) desktop program, built from the SAME sources as the phone app
# (app/src/main/cpp): belle_llm (llama.cpp chat) and belle_ort (ONNX Runtime runner for Photo -> 3D).
#
#   cmake -S desktop/native -B build-native -G Ninja -DCMAKE_BUILD_TYPE=Release -DBELLE_LLM_BACKEND=cpu|cuda|vulkan
#
# BELLE_LLM_BACKEND picks the llama.cpp variant; each lands in its own folder (out/<backend>/) because the program
# loads the one that matches the acceleration setting. belle_ort is backend-neutral (the onnxruntime it binds to
# decides), so it is only built with the cpu variant.
cmake_minimum_required(VERSION 3.22)
project(belle_desktop C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(BELLE_LLM_BACKEND "cpu" CACHE STRING "llama.cpp backend: cpu, cuda or vulkan")
set(APP_CPP ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
set(ROOT ${CMAKE_CURRENT_LIST_DIR}/../..)
find_package(JNI REQUIRED COMPONENTS JVM)
set(OUT ${CMAKE_BINARY_DIR}/out/${BELLE_LLM_BACKEND})
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${OUT})
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${OUT})
if(MSVC)
    add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX>:/utf-8>")   # not for nvcc, which would take /utf-8 as an input file
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")   # static CRT: no Visual C++ redistributable needed
endif()

# ---- llama.cpp ----
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
foreach(opt LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_OPENSSL LLAMA_CURL
            GGML_NATIVE GGML_OPENMP GGML_LLAMAFILE)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
# x86-64 baseline: AVX2 + FMA + F16C (every Intel CPU since 2013 / AMD since 2015)
set(GGML_AVX ON CACHE BOOL "" FORCE)
set(GGML_AVX2 ON CACHE BOOL "" FORCE)
set(GGML_FMA ON CACHE BOOL "" FORCE)
set(GGML_F16C ON CACHE BOOL "" FORCE)
if(BELLE_LLM_BACKEND STREQUAL "cuda")
    set(GGML_CUDA ON CACHE BOOL "" FORCE)
    # Turing .. Blackwell (RTX 20xx - 50xx); 120 needs CUDA 12.8+
    if(NOT DEFINED CMAKE_CUDA_ARCHITECTURES)
        set(CMAKE_CUDA_ARCHITECTURES "75-virtual;80-virtual;86-real;89-real;120-real" CACHE STRING "" FORCE)
    endif()
    set(GGML_CUDA_STATIC OFF CACHE BOOL "" FORCE)   # cudart / cublas DLLs come from the downloaded CUDA runtime pack
elseif(BELLE_LLM_BACKEND STREQUAL "vulkan")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
elseif(NOT BELLE_LLM_BACKEND STREQUAL "cpu")
    message(FATAL_ERROR "unknown BELLE_LLM_BACKEND ${BELLE_LLM_BACKEND}")
endif()
add_subdirectory(${ROOT}/third_party/llama.cpp build-llama)

add_library(belle_llm SHARED ${APP_CPP}/llm_core.cpp ${APP_CPP}/llm_jni.cpp)
target_include_directories(belle_llm PRIVATE ${JNI_INCLUDE_DIRS} ${ROOT}/third_party/llama.cpp/include ${ROOT}/third_party/llama.cpp/ggml/include)
target_link_libraries(belle_llm PRIVATE llama)
if(NOT BELLE_LLM_BACKEND STREQUAL "cpu")
    target_compile_definitions(belle_llm PRIVATE BELLE_LLM_GPU=1)   # offload layers (llm_core.cpp)
endif()

# ---- ONNX Runtime runner (binds at run time to the onnxruntime the program loaded) ----
if(BELLE_LLM_BACKEND STREQUAL "cpu")
    add_library(belle_ort SHARED ${APP_CPP}/ort_jni.c)
    target_include_directories(belle_ort PRIVATE ${JNI_INCLUDE_DIRS} ${ROOT}/third_party/ort_headers)
    if(NOT WIN32)
        target_link_libraries(belle_ort PRIVATE dl)
    endif()
endif()
