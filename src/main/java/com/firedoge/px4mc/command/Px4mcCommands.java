package com.firedoge.px4mc.command;

import com.mojang.brigadier.arguments.FloatArgumentType;

import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.scene.PhysicsObjectSnapshot;
import com.firedoge.px4mc.minecraft.scene.PhysicsObjectType;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class Px4mcCommands {
    private Px4mcCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("px4mc")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn_box")
                        .executes(context -> spawnBox(context.getSource(), 1.0F, 1.0F))
                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.05F, 16.0F))
                                .executes(context -> spawnBox(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "size"),
                                        1.0F
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> spawnBox(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "size"),
                                                FloatArgumentType.getFloat(context, "mass")
                                        )))))
                .then(Commands.literal("set_velocity")
                        .then(Commands.argument("x", FloatArgumentType.floatArg(-100.0F, 100.0F))
                                .then(Commands.argument("y", FloatArgumentType.floatArg(-100.0F, 100.0F))
                                        .then(Commands.argument("z", FloatArgumentType.floatArg(-100.0F, 100.0F))
                                                .executes(context -> setVelocity(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "x"),
                                                        FloatArgumentType.getFloat(context, "y"),
                                                        FloatArgumentType.getFloat(context, "z")
                                                ))))))
                .then(Commands.literal("physics_status")
                        .executes(context -> physicsStatus(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> clear(context.getSource()))));
    }

    private static int spawnBox(CommandSourceStack source, float size, float mass) {
        try {
            Vec3 position = source.getPosition();
            ServerPhysicsRuntime.SpawnedDebugBox spawned = ServerPhysicsRuntime.INSTANCE.spawnDebugBox(source.getLevel(), position, size, mass);
            source.sendSuccess(() -> Component.literal(
                    "Spawned physics box " + spawned.object().id() + " bound to entity " + spawned.entityId()
                            + "; size=" + String.format("%.2f", size)
                            + ", mass=" + String.format("%.2f", mass)
                            + "; queued " + spawned.terrainChunkQueueCount() + " terrain chunks"
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn physics box: " + exception.getMessage()));
            return 0;
        }
    }

    private static int setVelocity(CommandSourceStack source, float x, float y, float z) {
        PhysicsVector velocity = new PhysicsVector(x, y, z);
        return ServerPhysicsRuntime.INSTANCE.setNearestDynamicBoxVelocity(source.getLevel(), source.getPosition(), velocity, 32.0D)
                .map(result -> {
                    source.sendSuccess(() -> Component.literal(
                            "Set velocity for " + result.objectId()
                                    + " from " + describeVector(result.previousVelocity())
                                    + " to " + describeVector(result.newVelocity())
                                    + "; distance=" + String.format("%.2f", result.distance())
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("No dynamic physics box found within 32 blocks"));
                    return 0;
                });
    }

    private static int physicsStatus(CommandSourceStack source) {
        ServerPhysicsRuntime.RuntimeStatus status = ServerPhysicsRuntime.INSTANCE.status();
        String sample = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX)
                .findFirst()
                .map(Px4mcCommands::describeSample)
                .orElse("sample=<none>");
        source.sendSuccess(() -> Component.literal(
                "PhysX linked=" + status.nativeLinked()
                        + ", scenes=" + status.sceneCount()
                        + ", objects=" + status.objectCount()
                        + ", dynamicBoxes=" + status.dynamicBoxCount()
                        + ", terrainColliders=" + status.terrainColliderCount()
                        + ", terrainChunks=" + status.terrainChunkCount()
                        + ", terrainQueued=" + status.terrainQueuedChunkCount()
                        + ", terrainBuilt=" + status.terrainBuiltChunkCount()
                        + ", terrainDirty=" + status.terrainDirtyChunkCount()
                        + ", boundEntities=" + status.boundEntityCount()
                        + ", proxyRecreated=" + status.debugProxyRecreateCount()
                        + ", lastProxyRecreated=" + status.lastDebugProxyRecreateCount()
                        + ", lastStepMs=" + String.format("%.3f", status.lastStepMillis())
                        + ", lastTerrainChunks=" + status.lastTerrainChunkBuildCount()
                        + ", lastTerrainAdded=" + status.lastTerrainColliderBuildCount()
                        + ", lastTerrainPartial=" + status.lastTerrainPartialColliderBuildCount()
                        + ", lastTerrainBuildMs=" + String.format("%.3f", status.lastTerrainBuildMillis())
                        + ", " + sample
        ), false);
        return status.objectCount();
    }

    private static int clear(CommandSourceStack source) {
        int removed = ServerPhysicsRuntime.INSTANCE.clearLevel(source.getLevel());
        source.sendSuccess(() -> Component.literal("Cleared " + removed + " physics objects in this level"), true);
        return removed;
    }

    private static String describeSample(PhysicsObjectSnapshot snapshot) {
        return "sample=" + snapshot.id()
                + " type=" + snapshot.type()
                + " pos=" + describeVector(snapshot.pose().position())
                + " vel=" + describeVector(snapshot.linearVelocity());
    }

    private static String describeVector(PhysicsVector vector) {
        return "("
                + String.format("%.2f", vector.x())
                + ", "
                + String.format("%.2f", vector.y())
                + ", "
                + String.format("%.2f", vector.z())
                + ")";
    }
}
