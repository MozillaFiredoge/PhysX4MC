package com.firedoge.px4mc.backend.physx;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsBackend;
import com.firedoge.px4mc.api.PhysicsWorld;
import com.firedoge.px4mc.api.PhysicsWorldConfig;

public final class PhysXBackend implements PhysicsBackend {
    public static final String ID = "physx";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return PhysXNative.isPhysXLinked();
    }

    @Override
    public PhysicsWorld createWorld(PhysicsWorldConfig config) {
        Objects.requireNonNull(config, "config");
        PhysXNative.load();
        if (!PhysXNative.isPhysXLinked()) {
            throw new IllegalStateException("PhysX native bridge was built without linked PhysX libraries");
        }
        long handle = PhysXNative.nativeCreateWorld(
                config.gravity().x(),
                config.gravity().y(),
                config.gravity().z(),
                config.fixedTimeStep(),
                config.maxSubSteps(),
                config.enableGpuDynamics()
        );
        return new PhysXWorld(handle, config);
    }
}
