# CMakeLists.txt for SallIPTV Secure Native Library

cmake_minimum_required(VERSION 3.10.2)
project("salliptv_secure")

# Create shared library
add_library(
    salliptv_secure
    SHARED
    salliptv_secure.cpp
)

# Find Android log library
find_library(
    log-lib
    log
)

# Link libraries
target_link_libraries(
    salliptv_secure
    ${log-lib}
)

# Set C++ standard
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compiler flags for security and optimization
target_compile_options(
    salliptv_secure
    PRIVATE
    -O2
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fno-stack-protector
    -fno-rtti
    -fno-exceptions
    -fomit-frame-pointer
    -ffunction-sections
    -fdata-sections
    -Wl,--gc-sections
    -fPIE
    -fPIC
)

# Linker flags
target_link_options(
    salliptv_secure
    PRIVATE
    -Wl,--strip-all
    -Wl,--exclude-libs,ALL
)

# Architecture-specific optimizations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(
        salliptv_secure
        PRIVATE
        -march=armv8-a
    )
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(
        salliptv_secure
        PRIVATE
        -march=armv7-a
        -mfpu=neon
    )
endif()
