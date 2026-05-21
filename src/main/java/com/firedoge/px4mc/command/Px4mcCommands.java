package com.firedoge.px4mc.command;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

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
                .then(Commands.literal("spawn_stress_grid")
                        .then(Commands.argument("countX", IntegerArgumentType.integer(1, 128))
                                .then(Commands.argument("countY", IntegerArgumentType.integer(1, 128))
                                        .then(Commands.argument("countZ", IntegerArgumentType.integer(1, 128))
                                                .executes(context -> spawnStressGrid(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "countX"),
                                                        IntegerArgumentType.getInteger(context, "countY"),
                                                        IntegerArgumentType.getInteger(context, "countZ"),
                                                        1.2F,
                                                        1.0F,
                                                        1.0F
                                                ))
                                                .then(Commands.argument("spacing", FloatArgumentType.floatArg(0.05F, 16.0F))
                                                        .executes(context -> spawnStressGrid(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "countX"),
                                                                IntegerArgumentType.getInteger(context, "countY"),
                                                                IntegerArgumentType.getInteger(context, "countZ"),
                                                                FloatArgumentType.getFloat(context, "spacing"),
                                                                1.0F,
                                                                1.0F
                                                        ))
                                                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.05F, 16.0F))
                                                                .executes(context -> spawnStressGrid(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "countX"),
                                                                        IntegerArgumentType.getInteger(context, "countY"),
                                                                        IntegerArgumentType.getInteger(context, "countZ"),
                                                                        FloatArgumentType.getFloat(context, "spacing"),
                                                                        FloatArgumentType.getFloat(context, "size"),
                                                                        1.0F
                                                                ))
                                                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                                        .executes(context -> spawnStressGrid(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "countX"),
                                                                                IntegerArgumentType.getInteger(context, "countY"),
                                                                                IntegerArgumentType.getInteger(context, "countZ"),
                                                                                FloatArgumentType.getFloat(context, "spacing"),
                                                                                FloatArgumentType.getFloat(context, "size"),
                                                                                FloatArgumentType.getFloat(context, "mass")
                                                                        )))))))))
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
                .then(Commands.literal("list_boxes")
                        .executes(context -> listBoxes(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                                .executes(context -> listBoxes(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("remove_nearest")
                        .executes(context -> removeNearest(context.getSource(), 32.0F))
                        .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(1.0F, 256.0F))
                                .executes(context -> removeNearest(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "maxDistance")
                                ))))
                .then(Commands.literal("remove_box")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> removeBox(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
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

    private static int spawnStressGrid(CommandSourceStack source, int countX, int countY, int countZ, float spacing, float size, float mass) {
        try {
            ServerPhysicsRuntime.StressGridResult spawned = ServerPhysicsRuntime.INSTANCE.spawnStressGrid(
                    source.getLevel(),
                    source.getPosition(),
                    countX,
                    countY,
                    countZ,
                    spacing,
                    size,
                    mass
            );
            ServerPhysicsRuntime.RuntimeStatus status = ServerPhysicsRuntime.INSTANCE.status();
            source.sendSuccess(() -> Component.literal(
                    "Spawned stress grid " + spawned.created() + "/" + spawned.requested() + " physics-only boxes"
                            + "; dims=" + countX + "x" + countY + "x" + countZ
                            + ", spacing=" + String.format("%.2f", spacing)
                            + ", size=" + String.format("%.2f", size)
                            + ", mass=" + String.format("%.2f", mass)
                            + "; gpuRequested=" + status.gpuDynamicsRequested()
                            + ", gpuScenes=" + status.gpuDynamicsSceneCount()
                            + ", gpuStatus=" + status.gpuDynamicsStatus()
                            + "; queued " + spawned.terrainChunkQueueCount() + " terrain chunks"
            ), true);
            return spawned.created();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn stress grid: " + exception.getMessage()));
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

    private static int listBoxes(CommandSourceStack source, int limit) {
        Vec3 origin = source.getPosition();
        List<PhysicsObjectSnapshot> allSnapshots = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed())
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic physics boxes in this level"));
            return 0;
        }

        List<PhysicsObjectSnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " dynamic physics boxes in this level"
        ), false);
        for (PhysicsObjectSnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeBox(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int removeNearest(CommandSourceStack source, float maxDistance) {
        Vec3 origin = source.getPosition();
        double maxDistanceSqr = maxDistance * maxDistance;
        Optional<PhysicsObjectSnapshot> nearest = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed())
                .filter(snapshot -> distanceSqr(snapshot, origin) <= maxDistanceSqr)
                .min(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)));

        if (nearest.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic physics box found within " + String.format("%.2f", maxDistance) + " blocks"));
            return 0;
        }

        double distance = Math.sqrt(distanceSqr(nearest.get(), origin));
        return removeSelectedBox(source, nearest.get(), distance);
    }

    private static int removeBox(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Box id prefix must not be empty"));
            return 0;
        }

        List<PhysicsObjectSnapshot> matches = ServerPhysicsRuntime.INSTANCE.snapshotsFor(source.getLevel()).stream()
                .filter(snapshot -> snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic physics box starts with id prefix " + idPrefix));
            return 0;
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " boxes; use a longer prefix"));
            return 0;
        }

        double distance = Math.sqrt(distanceSqr(matches.get(0), source.getPosition()));
        return removeSelectedBox(source, matches.get(0), distance);
    }

    private static int removeSelectedBox(CommandSourceStack source, PhysicsObjectSnapshot snapshot, double distance) {
        return ServerPhysicsRuntime.INSTANCE.removeDynamicBox(source.getLevel(), snapshot.id())
                .map(removed -> {
                    String entity = removed.entityId() == null ? "<none>" : removed.entityId().toString();
                    source.sendSuccess(() -> Component.literal(
                            "Removed physics box " + removed.objectId()
                                    + " boundEntity=" + entity
                                    + "; lastPos=" + describeVector(removed.lastPosition())
                                    + "; lastVel=" + describeVector(removed.lastVelocity())
                                    + "; distance=" + String.format("%.2f", distance)
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Physics box " + snapshot.id() + " no longer exists"));
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
                        + ", gpuRequested=" + status.gpuDynamicsRequested()
                        + ", gpuScenes=" + status.gpuDynamicsSceneCount()
                        + ", gpuStatus=" + status.gpuDynamicsStatus()
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
                        + ", lastTickMs=" + String.format("%.3f", status.lastRuntimeTickMillis())
                        + ", lastQueueActiveMs=" + String.format("%.3f", status.lastQueueActiveMillis())
                        + ", lastTerrainProcessMs=" + String.format("%.3f", status.lastTerrainProcessMillis())
                        + ", lastStepPhaseMs=" + String.format("%.3f", status.lastStepPhaseMillis())
                        + ", lastSyncEntitiesMs=" + String.format("%.3f", status.lastSyncEntitiesMillis())
                        + ", syncObjectLookupMs=" + String.format("%.3f", status.lastSyncObjectLookupMillis())
                        + ", syncEntityLookupMs=" + String.format("%.3f", status.lastSyncEntityLookupMillis())
                        + ", syncRecreateMs=" + String.format("%.3f", status.lastSyncRecreateMillis())
                        + ", syncPoseReadMs=" + String.format("%.3f", status.lastSyncPoseReadMillis())
                        + ", syncApplyMs=" + String.format("%.3f", status.lastSyncApplyMillis())
                        + ", lastStepMs=" + String.format("%.3f", status.lastStepMillis())
                        + ", activeSnapshots=" + status.lastActiveSnapshotCount()
                        + ", activeDynamics=" + status.lastActiveDynamicCount()
                        + ", activeTerrainQueued=" + status.lastActiveTerrainQueuedCount()
                        + ", activeTerrainSkippedHeight=" + status.lastActiveTerrainSkippedHeightCount()
                        + ", activeTerrainScanLimit=" + status.activeTerrainMaxScansPerTick()
                        + ", syncedEntities=" + status.lastSyncedEntityCount()
                        + ", entityPoseSyncs=" + status.lastEntityPoseSyncCount()
                        + ", syncRemoved=" + status.lastSyncRemovedBindingCount()
                        + ", syncMissingEntities=" + status.lastSyncMissingEntityCount()
                        + ", proxySyncTransform=" + status.debugProxySyncTransform()
                        + ", entitySyncLimit=" + status.maxEntityPoseSyncsPerTick()
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

    private static String describeBox(PhysicsObjectSnapshot snapshot, double distance) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " pos=" + describeVector(snapshot.pose().position())
                + " vel=" + describeVector(snapshot.linearVelocity())
                + " distance=" + String.format("%.2f", distance);
    }

    private static String shortId(PhysicsObjectSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static double distanceSqr(PhysicsObjectSnapshot snapshot, Vec3 origin) {
        PhysicsVector position = snapshot.pose().position();
        double dx = position.x() - origin.x();
        double dy = position.y() - origin.y();
        double dz = position.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
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
