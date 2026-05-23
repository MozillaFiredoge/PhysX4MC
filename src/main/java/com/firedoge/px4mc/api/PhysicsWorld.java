package com.firedoge.px4mc.api;

import java.util.List;

public interface PhysicsWorld extends AutoCloseable {
    String backendId();

    PhysicsShape createBoxShape(float halfExtentX, float halfExtentY, float halfExtentZ);

    PhysicsBody createStaticPlane(PhysicsVector normal, double distance);

    PhysicsBody createBody(RigidBodyDefinition definition);

    default PhysicsBody createDynamicCompoundBoxBody(PhysicsPose pose, List<PhysicsBoxCollider> boxes, float mass) {
        throw new UnsupportedOperationException(backendId() + " does not support dynamic compound box bodies");
    }

    void destroyBody(PhysicsBody body);

    void step(float deltaSeconds);

    default boolean gpuDynamicsEnabled() {
        return false;
    }

    default String gpuDynamicsStatus() {
        return "not_requested";
    }

    boolean isClosed();

    @Override
    void close();
}
