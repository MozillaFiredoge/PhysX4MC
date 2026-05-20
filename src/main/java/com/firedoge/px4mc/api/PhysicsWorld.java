package com.firedoge.px4mc.api;

public interface PhysicsWorld extends AutoCloseable {
    String backendId();

    PhysicsShape createBoxShape(float halfExtentX, float halfExtentY, float halfExtentZ);

    PhysicsBody createStaticPlane(PhysicsVector normal, double distance);

    PhysicsBody createBody(RigidBodyDefinition definition);

    void destroyBody(PhysicsBody body);

    void step(float deltaSeconds);

    boolean isClosed();

    @Override
    void close();
}
