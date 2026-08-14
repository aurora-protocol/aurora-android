cmake_minimum_required(VERSION 3.22.1)
project(aurora_android_jni C)

set(AURORA_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(auroracore SHARED IMPORTED GLOBAL)
set_target_properties(auroracore PROPERTIES
    IMPORTED_LOCATION "${AURORA_CORE_DIR}/libauroracore.so"
)

add_library(aurora_android_jni SHARED aurora_jni.c)
target_include_directories(aurora_android_jni PRIVATE "${AURORA_CORE_DIR}")
target_compile_options(aurora_android_jni PRIVATE -Wall -Wextra -Werror)
target_link_libraries(aurora_android_jni auroracore log)
