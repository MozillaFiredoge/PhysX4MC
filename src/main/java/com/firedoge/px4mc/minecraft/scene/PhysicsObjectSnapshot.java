package com.firedoge.px4mc.minecraft.scene;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;

public record PhysicsObjectSnapshot(
        PhysicsObjectId id,
        PhysicsObjectType type,
        PhysicsPose pose,
        PhysicsVector linearVelocity,
        boolean closed
) {
    public PhysicsObjectSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
    }
}
