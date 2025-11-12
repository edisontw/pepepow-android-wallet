CMAKE_MINIMUM_REQUIRED(VERSION 3.1.0 FATAL_ERROR)
set(CMAKE_CXX_STANDARD 11)

set(BLS_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/bls-signatures)
set(BLS_SRC_DIR ${BLS_ROOT}/src)

file(GLOB BLS_HEADERS ${BLS_SRC_DIR}/*.hpp)
source_group("SrcHeaders" FILES ${BLS_HEADERS})

set(BLS_SOURCES
    ${BLS_SRC_DIR}/aggregationinfo.cpp
    ${BLS_SRC_DIR}/chaincode.cpp
    ${BLS_SRC_DIR}/extendedprivatekey.cpp
    ${BLS_SRC_DIR}/extendedpublickey.cpp
    ${BLS_SRC_DIR}/privatekey.cpp
    ${BLS_SRC_DIR}/publickey.cpp
    ${BLS_SRC_DIR}/signature.cpp
    ${BLS_SRC_DIR}/bls.cpp
    ${BLS_SRC_DIR}/threshold.cpp
)

add_library(blstmp ${BLS_HEADERS} ${BLS_SOURCES})

target_include_directories(blstmp
    PUBLIC
        ${BLS_SRC_DIR}
        ${BLS_RELIC_SOURCE_DIR}/include
        ${BLS_RELIC_BINARY_DIR}/include
)
