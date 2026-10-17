cmake_minimum_required(VERSION 3.22.1)
project("adbpairing")

set(CMAKE_CXX_STANDARD 17)
add_compile_options(-fdata-sections -ffunction-sections -fno-exceptions -fno-rtti)
add_link_options(-Wl,--gc-sections -Wl,-z,max-page-size=16384)

# BoringSSL comes from the prefab AAR io.github.vvb2060.ndk:boringssl (see app/build.gradle.kts).
find_package(boringssl REQUIRED CONFIG)

add_library(adbpairing SHARED adb_pairing.cpp)
target_link_libraries(adbpairing log boringssl::crypto_static)
