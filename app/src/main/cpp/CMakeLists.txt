cmake_minimum_required(VERSION 3.22.1)
project(aurora_android_jni C)

set(AURORA_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/generated/auroracore/${ANDROID_ABI}")

add_library(auroracore SHARED IMPORTED GLOBAL)
set_target_properties(auroracore PROPERTIES
    IMPORTED_LOCATION "${AURORA_CORE_DIR}/libauroracore.so"
)

add_library(aurora_android_jni SHARED aurora_jni.c)
set_target_properties(aurora_android_jni PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED YES
    C_EXTENSIONS NO
    C_VISIBILITY_PRESET hidden
)
target_include_directories(aurora_android_jni PRIVATE "${AURORA_CORE_DIR}")
target_compile_options(aurora_android_jni PRIVATE -Wall -Wextra -Werror)
target_link_options(aurora_android_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(aurora_android_jni auroracore log)
