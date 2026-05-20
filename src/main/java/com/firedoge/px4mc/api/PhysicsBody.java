package com.firedoge.px4mc.api;

public interface PhysicsBody extends AutoCloseable {
    long nativeHandle();

    PhysicsPose pose();

    void setPose(PhysicsPose pose);

    PhysicsVector linearVelocity();

    void setLinearVelocity(PhysicsVector velocity);

    boolean isClosed();

    @Override
    void close();
}
