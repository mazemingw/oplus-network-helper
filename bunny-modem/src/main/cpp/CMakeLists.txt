cmake_minimum_required(VERSION 3.22)
project(bunnyqrtr C)

add_library(bunnyqrtr SHARED qrtr.c qmi_owners.c)
target_compile_options(bunnyqrtr PRIVATE -Wall -Wextra -O2)
target_link_options(bunnyqrtr PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(bunnyqrtr log)
