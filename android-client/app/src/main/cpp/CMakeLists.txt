# %BANNER_BEGIN%
# ---------------------------------------------------------------------
# %COPYRIGHT_BEGIN%
# Copyright (c) 2021 Magic Leap, Inc. All Rights Reserved.
# Use of this file is governed by the Software License Agreement,
# located here: https://www.magicleap.com/software-license-agreement-ml2
# Terms and conditions applicable to third-party materials accompanying
# this distribution may also be found in the top-level NOTICE file
# appearing herein.
# %COPYRIGHT_END%
# ---------------------------------------------------------------------
# %BANNER_END%

cmake_minimum_required(VERSION 3.22.1)

project(ml2_api_lib)

file(TO_CMAKE_PATH "$ENV{MLSDK}" MLSDK)
list(APPEND CMAKE_MODULE_PATH "${MLSDK}/cmake" "${ANDROID_NDK}/../../mlsdk/cmake")

find_package(MagicLeap REQUIRED)

add_library(ml2_api_lib SHARED ml2_camera.cpp)

target_link_libraries(ml2_api_lib
    ML::camera
    ML::media_error
)

if (COMMAND copy_artifacts)
    copy_artifacts(ml2_api_lib)
endif()