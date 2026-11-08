cmake_minimum_required(VERSION 3.18.1)
project(fileforge CXX)

add_library(fileforge SHARED native.cpp)

find_library(log-lib log)
target_link_libraries(fileforge ${log-lib})
