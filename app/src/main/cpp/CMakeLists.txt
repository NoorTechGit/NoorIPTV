cmake_minimum_required(VERSION 3.22.1)

project("salliptv")

# ============================================================================
# FLAGS DE COMPILATION SÉCURISÉS
# ============================================================================

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-stack-protector -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra")

# Strip symboles en release
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -s")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s")

# ============================================================================
# BIBLIOTHÈQUE PRINCIPALE
# ============================================================================

add_library(
    native-lib
    SHARED
    # Core
    native-lib.cpp
    
    # Sécurité avancée
    anti-debug.cpp
    crypto-utils.cpp
    # Note: Pour AES-256-GCM avec OpenSSL, voir instructions dans crypto-utils.cpp
)

target_link_libraries(
    native-lib
    # Standard
    android
    log
)

# ============================================================================
# OPTIONS DE LIEN SÉCURISÉES
# ============================================================================

set_target_properties(native-lib PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

# Strip tous les symboles
set_target_properties(native-lib PROPERTIES
    LINK_FLAGS "-Wl,--strip-all -Wl,--exclude-libs,ALL"
)