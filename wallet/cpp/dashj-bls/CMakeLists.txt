#
# dashj-bls
#
cmake_minimum_required(VERSION 3.4.1)
set(CMAKE_CXX_STANDARD 11)

set(OPSYS "DROID")

add_library( # Sets the name of the library.
        dashjbls
        # Sets the library as a shared library.
       SHARED
        # Provides a relative path to your source file(s).
        dashj-bls-signature-wrapper.cpp
        stdio.cpp
        relic_stubs.c)

include(bls-signatures.cmake)

# Find Java

#find_package(JNI)
#if (JNI_FOUND)
#    message (STATUS "JNI_INCLUDE_DIRS=${JNI_INCLUDE_DIRS}")
#    message (STATUS "JNI_LIBRARIES=${JNI_LIBRARIES}")
#endif()

set(BLS_RELIC_INCLUDE_DIR "${BLS_RELIC_SOURCE_DIR}/include")
set(BLS_RELIC_BINARY_INCLUDE_DIR "${BLS_RELIC_BINARY_DIR}/include")

include_directories(#${JNI_INCLUDE_DIRS}
					${BLS_ROOT}/src
					${BLS_RELIC_BINARY_INCLUDE_DIR}
					${BLS_RELIC_INCLUDE_DIR}
)
message(STATUS "binary dir = ${CMAKE_BINARY_DIR}")

if (${ANDROID_ABI} STREQUAL "x86_64")
	include_directories(${ANDROID_SYSROOT}/usr/include/x86_64-linux-android)
elseif (${ANDROID_ABI} STREQUAL "x86")
	include_directories(${ANDROID_SYSROOT}/usr/include/i686-linux-android)
elseif (${ANDROID_ABI} STREQUAL "arm64-v8a")
	include_directories(${ANDROID_SYSROOT}/usr/include/aarch64-linux-android)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
	include_directories(${ANDROID_SYSROOT}/usr/include/arm-linux-androideabi)
endif()

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        dashjbls

        # Links the target library to the bls binaries
        blstmp relic_s)

#add_subdirectory(src/main/cpp)

add_library(pthread SHARED pthread.c)
