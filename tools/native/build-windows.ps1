$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path "$PSScriptRoot/../.."
$PhysXRoot = if ($env:PHYSX_ROOT) { $env:PHYSX_ROOT } else { Join-Path $ProjectRoot "PhysX/physx" }
$CMakeArgs = @(
    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
    "-DPHYSX_ROOT=$PhysXRoot"
)

if ($env:PHYSX_LIB_DIR) {
    $CMakeArgs += "-DPHYSX_LIB_DIR=$env:PHYSX_LIB_DIR"
}

cmake -S "$ProjectRoot/src/main/cpp/physx4mc" -B "$ProjectRoot/build/native/windows-x86_64" @CMakeArgs
cmake --build "$ProjectRoot/build/native/windows-x86_64" --config RelWithDebInfo
