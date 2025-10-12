cmake_minimum_required(VERSION 3.15)
project(JSyzygy C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME must be defined to locate JNI headers")
endif()

set(JAVA_HOME $ENV{JAVA_HOME})

add_library(JSyzygy SHARED
    tbprobe.c
    jni_bridge.c)

target_include_directories(JSyzygy
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${JAVA_HOME}/include)

if(WIN32)
    target_include_directories(JSyzygy PRIVATE ${JAVA_HOME}/include/win32)
else()
    target_include_directories(JSyzygy PRIVATE ${JAVA_HOME}/include/linux)
endif()

target_compile_definitions(JSyzygy
    PRIVATE
        TB_USE_ATOMIC
        TB_NO_HW_POP_COUNT)

if(MSVC)
    target_compile_options(JSyzygy PRIVATE /O2)
else()
    target_compile_options(JSyzygy PRIVATE -O2)
endif()

if(NOT WIN32)
    target_link_libraries(JSyzygy PRIVATE m)
endif()

set_target_properties(JSyzygy PROPERTIES
    OUTPUT_NAME "JSyzygy")
