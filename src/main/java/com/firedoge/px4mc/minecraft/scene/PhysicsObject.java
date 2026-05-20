package com.firedoge.px4mc.minecraft.scene;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;

public interface PhysicsObject extends AutoCloseable {
    PhysicsObjectId id();

    PhysicsObjectType type();

    PhysicsPose pose();

    void setPose(PhysicsPose pose);

    PhysicsVector linearVelocity();

    void setLinearVelocity(PhysicsVector velocity);

    PhysicsObjectSnapshot snapshot();

    boolean isClosed();

    @Override
    void close();
}
