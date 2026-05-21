package com.firedoge.px4mc.mechanics;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record MechanicsBodySnapshot(
        MechanicsBodyId id,
        ResourceKey<Level> levelKey,
        MechanicsBodyType type,
        MechanicsBodyRole role,
        PhysicsPose pose,
        PhysicsVector linearVelocity,
        PhysicsVector halfExtents,
        float mass,
        boolean closed
) {
    public MechanicsBodySnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
        Objects.requireNonNull(halfExtents, "halfExtents");
    }
}
