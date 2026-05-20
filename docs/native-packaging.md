# Native Packaging

M7 packages the Linux JNI bridge into the mod jar as a generated resource.

## Gradle Tasks

```text
./gradlew buildNativeLinux
./gradlew copyNativeLinux
./gradlew -Pphysx4mc.bundleNativeLinux=true build
```

`buildNativeLinux` runs `tools/native/build-linux.sh` and produces:

```text
build/native/linux-x86_64/libphysx4mc_native.so
```

`copyNativeLinux` copies that file into:

```text
build/generated/nativeResources/native/linux-x86_64/libphysx4mc_native.so
```

When `-Pphysx4mc.bundleNativeLinux=true` is set, `processResources` depends on
`copyNativeLinux`, so the final jar includes the Linux native library.

A normal `./gradlew build` does not require a local PhysX checkout and does not
bundle native libraries. This keeps CI and first-commit validation lightweight.

## Loader Path

`NativeLibraryLoader` tries these locations in order:

1. `System.loadLibrary`, for libraries already on `java.library.path`.
2. `physx4mc.nativeLibraryPath` or `PHYSX4MC_NATIVE_PATH`, for development.
3. `/native/<platform>/<mapped-library-name>` inside the mod jar.

For Linux x86_64, the bundled resource path is:

```text
/native/linux-x86_64/libphysx4mc_native.so
```

The NeoForge dev run still sets `physx4mc.nativeLibraryPath` to
`build/native/linux-x86_64`, which keeps local iteration direct. Packaged jars
can use the bundled resource fallback when built with
`-Pphysx4mc.bundleNativeLinux=true`.

## Current Scope

Only Linux x86_64 is packaged. Windows and macOS should be added after the Linux
simulation, collision, and entity sync path has stayed stable for a while.
