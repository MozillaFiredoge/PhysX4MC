package com.firedoge.px4mc.mechanics;

import java.util.List;
import java.util.Optional;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface MechanicsWorld {
    ResourceKey<Level> levelKey();

    MechanicsBodySnapshot createDynamicBox(MechanicsBoxDefinition definition);

    Optional<MechanicsBodySnapshot> snapshot(MechanicsBodyId id);

    List<MechanicsBodySnapshot> snapshots();

    boolean setPose(MechanicsBodyId id, PhysicsPose pose);

    boolean setLinearVelocity(MechanicsBodyId id, PhysicsVector velocity);

    boolean applyLinearImpulse(MechanicsBodyId id, PhysicsVector impulse);

    boolean removeBody(MechanicsBodyId id);
}
