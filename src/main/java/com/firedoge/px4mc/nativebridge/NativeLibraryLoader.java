package com.firedoge.px4mc.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeLibraryLoader {
    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private NativeLibraryLoader() {
    }

    public static boolean isLoaded(String libraryName) {
        return LOADED.contains(libraryName);
    }

    public static synchronized void load(String libraryName) {
        if (LOADED.contains(libraryName)) {
            return;
        }

        UnsatisfiedLinkError directLoadFailure = null;
        try {
            System.loadLibrary(libraryName);
            LOADED.add(libraryName);
            return;
        } catch (UnsatisfiedLinkError error) {
            directLoadFailure = error;
        }

        if (loadFromConfiguredPath(libraryName)) {
            LOADED.add(libraryName);
            return;
        }

        loadBundled(libraryName, directLoadFailure);
        LOADED.add(libraryName);
    }

    private static boolean loadFromConfiguredPath(String libraryName) {
        String configuredPath = System.getProperty("physx4mc.nativeLibraryPath");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("PHYSX4MC_NATIVE_PATH");
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            return false;
        }

        Path path = Paths.get(configuredPath);
        String mappedName = System.mapLibraryName(libraryName);
        Path library = Files.isDirectory(path) ? path.resolve(mappedName) : path;
        if (!Files.isRegularFile(library)) {
            return false;
        }

        System.load(library.toAbsolutePath().toString());
        return true;
    }

    private static void loadBundled(String libraryName, UnsatisfiedLinkError directLoadFailure) {
        NativePlatform platform = NativePlatform.detect();
        String mappedName = System.mapLibraryName(libraryName);
        String resourcePath = "/native/" + platform.resourcePath() + "/" + mappedName;

        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new NativeException("Missing bundled native library resource: " + resourcePath, directLoadFailure);
            }

            Path tempDirectory = Files.createTempDirectory("physx4mc-natives");
            Path extractedLibrary = tempDirectory.resolve(mappedName);
            Files.copy(input, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
            extractedLibrary.toFile().deleteOnExit();
            tempDirectory.toFile().deleteOnExit();
            System.load(extractedLibrary.toAbsolutePath().toString());
        } catch (IOException | UnsatisfiedLinkError exception) {
            throw new NativeException("Failed to load native library " + libraryName, exception);
        }
    }
}
