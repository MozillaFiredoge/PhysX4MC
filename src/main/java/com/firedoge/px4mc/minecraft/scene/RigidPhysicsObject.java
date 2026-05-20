package com.firedoge.px4mc.minecraft.scene;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsBody;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsShape;
import com.firedoge.px4mc.api.PhysicsVector;

final class RigidPhysicsObject implements PhysicsObject {
    private final ServerPhysicsScene scene;
    private final PhysicsObjectId id;
    private final PhysicsObjectType type;
    private final PhysicsBody body;
    private final PhysicsShape ownedShape;
    private boolean closed;

    RigidPhysicsObject(ServerPhysicsScene scene, PhysicsObjectId id, PhysicsObjectType type, PhysicsBody body, PhysicsShape ownedShape) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.body = Objects.requireNonNull(body, "body");
        this.ownedShape = ownedShape;
    }

    @Override
    public PhysicsObjectId id() {
        return id;
    }

    @Override
    public PhysicsObjectType type() {
        return type;
    }

    @Override
    public PhysicsPose pose() {
        return body.pose();
    }

    @Override
    public void setPose(PhysicsPose pose) {
        body.setPose(pose);
    }

    @Override
    public PhysicsVector linearVelocity() {
        return body.linearVelocity();
    }

    @Override
    public void setLinearVelocity(PhysicsVector velocity) {
        body.setLinearVelocity(velocity);
    }

    @Override
    public PhysicsObjectSnapshot snapshot() {
        return new PhysicsObjectSnapshot(id, type, pose(), linearVelocity(), closed);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        body.close();
        if (ownedShape != null) {
            ownedShape.close();
        }
        scene.forgetObject(id);
    }
}
