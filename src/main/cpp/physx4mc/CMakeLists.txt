cmake_minimum_required(VERSION 3.25)

project(physx4mc_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)

set(PHYSX_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../PhysX/physx" CACHE PATH "PhysX SDK root")
set(PHYSX_LIB_DIR "" CACHE PATH "Directory containing built PhysX libraries")
set(PHYSX_INCLUDE_DIR "${PHYSX_ROOT}/include")

add_library(physx4mc_native SHARED
    src/jni_bridge.cpp
    src/physx_body.cpp
    src/physx_context.cpp
    src/physx_shape.cpp
    src/physx_world.cpp
    src/conversions.cpp
)

target_include_directories(physx4mc_native
    PRIVATE
        include
        ${JNI_INCLUDE_DIRS}
)

if(EXISTS "${PHYSX_INCLUDE_DIR}/PxPhysicsAPI.h")
    target_include_directories(physx4mc_native PRIVATE "${PHYSX_INCLUDE_DIR}")
else()
    message(WARNING "PhysX headers were not found at ${PHYSX_INCLUDE_DIR}; building native bridge without PhysX")
endif()

set(PX4MC_PHYSX_LIBRARY_HINTS
    "${PHYSX_LIB_DIR}"
    "${PHYSX_ROOT}/bin/linux.x86_64"
    "${PHYSX_ROOT}/install/linux-gcc-cpu-only/PhysX/bin/linux.x86_64"
    "${PHYSX_ROOT}/install/linux-gcc-cpu-only/PhysX/lib"
    "${PHYSX_ROOT}/install/linux-gcc/PhysX/bin/linux.x86_64"
    "${PHYSX_ROOT}/install/linux-gcc/PhysX/lib"
)
set(PX4MC_PHYSX_LIBRARY_SUFFIXES "" debug checked profile release)

find_library(PX4MC_PHYSX_LIB NAMES PhysX PhysX_64 PhysX_static PhysX_static_64 PATHS ${PX4MC_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${PX4MC_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH)
find_library(PX4MC_PHYSX_COMMON_LIB NAMES PhysXCommon PhysXCommon_64 PhysXCommon_static PhysXCommon_static_64 PATHS ${PX4MC_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${PX4MC_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH)
find_library(PX4MC_PHYSX_FOUNDATION_LIB NAMES PhysXFoundation PhysXFoundation_64 PhysXFoundation_static PhysXFoundation_static_64 PATHS ${PX4MC_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${PX4MC_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH)
find_library(PX4MC_PHYSX_EXTENSIONS_LIB NAMES PhysXExtensions PhysXExtensions_64 PhysXExtensions_static PhysXExtensions_static_64 PATHS ${PX4MC_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${PX4MC_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH)
find_library(PX4MC_PHYSX_PVD_LIB NAMES PhysXPvdSDK PhysXPvdSDK_64 PhysXPvdSDK_static PhysXPvdSDK_static_64 PATHS ${PX4MC_PHYSX_LIBRARY_HINTS} PATH_SUFFIXES ${PX4MC_PHYSX_LIBRARY_SUFFIXES} NO_DEFAULT_PATH)

if(PX4MC_PHYSX_LIB AND PX4MC_PHYSX_COMMON_LIB AND PX4MC_PHYSX_FOUNDATION_LIB AND PX4MC_PHYSX_EXTENSIONS_LIB AND PX4MC_PHYSX_PVD_LIB)
    target_compile_definitions(physx4mc_native PRIVATE PX4MC_WITH_PHYSX=1)
    foreach(PX4MC_PHYSX_FOUND_LIB
        ${PX4MC_PHYSX_LIB}
        ${PX4MC_PHYSX_COMMON_LIB}
        ${PX4MC_PHYSX_FOUNDATION_LIB}
        ${PX4MC_PHYSX_EXTENSIONS_LIB}
        ${PX4MC_PHYSX_PVD_LIB}
    )
        if(PX4MC_PHYSX_FOUND_LIB MATCHES "_static")
            target_compile_definitions(physx4mc_native PRIVATE PX_PHYSX_STATIC_LIB)
        endif()
    endforeach()
    target_link_libraries(physx4mc_native
        PRIVATE
            ${PX4MC_PHYSX_EXTENSIONS_LIB}
            ${PX4MC_PHYSX_PVD_LIB}
            ${PX4MC_PHYSX_LIB}
            ${PX4MC_PHYSX_COMMON_LIB}
            ${PX4MC_PHYSX_FOUNDATION_LIB}
            dl
            pthread
    )
    set_target_properties(physx4mc_native PROPERTIES
        BUILD_RPATH "$ORIGIN"
        INSTALL_RPATH "$ORIGIN"
    )
    message(STATUS "Building native bridge with PhysX from ${PHYSX_ROOT}")
    message(STATUS "PhysX libraries:")
    message(STATUS "  PhysX: ${PX4MC_PHYSX_LIB}")
    message(STATUS "  PhysXCommon: ${PX4MC_PHYSX_COMMON_LIB}")
    message(STATUS "  PhysXFoundation: ${PX4MC_PHYSX_FOUNDATION_LIB}")
    message(STATUS "  PhysXExtensions: ${PX4MC_PHYSX_EXTENSIONS_LIB}")
    message(STATUS "  PhysXPvdSDK: ${PX4MC_PHYSX_PVD_LIB}")
else()
    target_compile_definitions(physx4mc_native PRIVATE PX4MC_WITH_PHYSX=0)
    message(WARNING "Built PhysX libraries were not found; building native bridge stub. Set PHYSX_LIB_DIR after building PhysX.")
endif()
