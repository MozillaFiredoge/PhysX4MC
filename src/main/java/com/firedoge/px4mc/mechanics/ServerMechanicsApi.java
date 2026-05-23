package com.firedoge.px4mc.mechanics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class ServerMechanicsApi implements MechanicsApi {
    public static final ServerMechanicsApi INSTANCE = new ServerMechanicsApi();

    private ServerMechanicsApi() {
    }

    @Override
    public MechanicsWorld world(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerPhysicsRuntime.INSTANCE.sceneFor(level);
        return new RuntimeMechanicsWorld(level);
    }

    @Override
    public Optional<MechanicsWorld> existingWorld(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return ServerPhysicsRuntime.INSTANCE.existingScene(level)
                .map(ignored -> new RuntimeMechanicsWorld(level));
    }

    private record RuntimeMechanicsWorld(ServerLevel level) implements MechanicsWorld {
        private RuntimeMechanicsWorld {
            Objects.requireNonNull(level, "level");
        }

        @Override
        public ResourceKey<Level> levelKey() {
            return level.dimension();
        }

        @Override
        public MechanicsBodySnapshot createDynamicBox(MechanicsBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicBox(level, definition);
        }

        @Override
        public MechanicsBodySnapshot createDynamicCompoundBox(MechanicsCompoundBoxDefinition definition) {
            return ServerPhysicsRuntime.INSTANCE.createMechanicsDynamicCompoundBox(level, definition);
        }

        @Override
        public Optional<MechanicsBodySnapshot> snapshot(MechanicsBodyId id) {
            return ServerPhysicsRuntime.INSTANCE.mechanicsSnapshot(level, id);
        }

        @Override
        public List<MechanicsBodySnapshot> snapshots() {
            return ServerPhysicsRuntime.INSTANCE.mechanicsSnapshots(level);
        }

        @Override
        public boolean setPose(MechanicsBodyId id, PhysicsPose pose) {
            return ServerPhysicsRuntime.INSTANCE.setMechanicsPose(level, id, pose);
        }

        @Override
        public boolean setLinearVelocity(MechanicsBodyId id, PhysicsVector velocity) {
            return ServerPhysicsRuntime.INSTANCE.setMechanicsLinearVelocity(level, id, velocity);
        }

        @Override
        public boolean applyLinearImpulse(MechanicsBodyId id, PhysicsVector impulse) {
            return ServerPhysicsRuntime.INSTANCE.applyMechanicsLinearImpulse(level, id, impulse);
        }

        @Override
        public boolean removeBody(MechanicsBodyId id) {
            return ServerPhysicsRuntime.INSTANCE.removeMechanicsBody(level, id);
        }
    }
}
