cmake_minimum_required(VERSION 3.22.1)
project(nh_iperf)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(IPERF_DIR ${CMAKE_SOURCE_DIR}/iperf)

# 1) 收集 iperf 源文件
file(GLOB IPERF_SRC
        ${IPERF_DIR}/src/*.c
)

# 排除测试文件 + CLI main +  sctp
list(FILTER IPERF_SRC EXCLUDE REGEX ".*/t_.*\\.c$")
list(FILTER IPERF_SRC EXCLUDE REGEX ".*/main\\.c$")
list(FILTER IPERF_SRC EXCLUDE REGEX ".*/iperf_sctp\\.c$")

if (NOT IPERF_SRC)
    message(FATAL_ERROR "No iperf sources found. Expected: ${IPERF_DIR}/src/*.c")
endif()

# 2) 编译 iperf 为静态库
add_library(iperf3 STATIC
        ${IPERF_SRC}
)

# 只给 iperf3 加 include（别用全局 include_directories）
target_include_directories(iperf3 PRIVATE
        ${IPERF_DIR}/src
        ${IPERF_DIR}/include
)

# 强制启用 stdatomic，避免 atomic_uint_fast64_t 冲突
target_compile_definitions(iperf3 PRIVATE HAVE_STDATOMIC_H=1)

# 3) JNI 动态库
add_library(nh_iperf SHARED
        nh_iperf_jni.cpp
)

# JNI 这边也要 include iperf 头（因为 nh_iperf_jni.cpp include 了 iperf_api.h）
target_include_directories(nh_iperf PRIVATE
        ${IPERF_DIR}/src
        ${IPERF_DIR}/include
)

# JNI 编译单元同样需要 HAVE_STDATOMIC_H
target_compile_definitions(nh_iperf PRIVATE HAVE_STDATOMIC_H=1)

# 4) Android 系统库
find_library(log-lib log)
find_library(android-lib android)
# ===== 16KB page size support (Android 15+ requirement) =====
# 让 ELF LOAD 段按 16KB 对齐，避免 16KB page size 设备加载失败
target_link_options(nh_iperf PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)


target_link_libraries(nh_iperf
        iperf3
        ${log-lib}
        ${android-lib}
)
