package com.firedoge.px4mc.backend.physx;

import com.firedoge.px4mc.nativebridge.NativeLibraryLoader;

public final class PhysXNative {
    public static final String LIBRARY_NAME = "physx4mc_native";

    private PhysXNative() {
    }

    public static synchronized void load() {
        NativeLibraryLoader.load(LIBRARY_NAME);
    }

    public static boolean isLoaded() {
        return NativeLibraryLoader.isLoaded(LIBRARY_NAME);
    }

    public static boolean isPhysXLinked() {
        return isLoaded() && nativeIsPhysXLinked();
    }

    static native boolean nativeIsPhysXLinked();

    static native long nativeCreateWorld(double gravityX, double gravityY, double gravityZ, float fixedTimeStep, int maxSubSteps);

    static native void nativeDestroyWorld(long worldHandle);

    static native void nativeStepWorld(long worldHandle, float deltaSeconds);

    static native long nativeCreateBoxShape(long worldHandle, float halfExtentX, float halfExtentY, float halfExtentZ);

    static native long nativeCreateStaticPlane(long worldHandle, double normalX, double normalY, double normalZ, double distance);

    static native long nativeCreateStaticBody(
            long worldHandle,
            long shapeHandle,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW
    );

    static native long nativeCreateDynamicBody(
            long worldHandle,
            long shapeHandle,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW,
            float mass
    );

    static native boolean nativeGetBodyPose(long bodyHandle, double[] output);

    static native void nativeSetBodyPose(
            long bodyHandle,
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double rotationW
    );

    static native void nativeSetLinearVelocity(long bodyHandle, double velocityX, double velocityY, double velocityZ);

    static native void nativeDestroyBody(long bodyHandle);

    static native void nativeDestroyShape(long shapeHandle);
}
