package com.firedoge.px4mc.api;

public interface PhysicsBackend {
    String id();

    boolean isAvailable();

    PhysicsWorld createWorld(PhysicsWorldConfig config);
}
