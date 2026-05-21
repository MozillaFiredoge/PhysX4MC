package com.firedoge.px4mc.command;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.firedoge.px4mc.mechanics.MechanicsBoxDefinition;
import com.firedoge.px4mc.mechanics.MechanicsDebugProxy;
import com.firedoge.px4mc.mechanics.MechanicsDebugProxies;
import com.firedoge.px4mc.mechanics.MechanicsWorld;
import com.firedoge.px4mc.minecraft.player.PlayerPhysicsManager;
import com.firedoge.px4mc.minecraft.player.PlayerPhysicsSnapshot;
import com.firedoge.px4mc.minecraft.player.PlayerProxyManager;
import com.firedoge.px4mc.minecraft.player.PlayerProxySnapshot;
import com.firedoge.px4mc.minecraft.scene.PhysicsObjectSnapshot;
import com.firedoge.px4mc.minecraft.scene.PhysicsObjectType;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelBreakResult;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelManager;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelPickResult;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelSnapshot;

import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
                .then(blockCommands())
                .then(sublevelCommands())
                .then(playerPhysicsCommands())
                .then(playerProxyCommands())
                .then(mechanicsCommands())
                .then(Commands.literal("physics_status")
                        .executes(context -> physicsStatus(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> clear(context.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerPhysicsCommands() {
        return Commands.literal("player_physics")
                .then(Commands.literal("enable")
                        .executes(context -> playerPhysicsEnable(
                                context.getSource(),
                                PlayerPhysicsManager.DEFAULT_PLAYER_MASS,
                                false
                        ))
                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                .executes(context -> playerPhysicsEnable(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "mass"),
                                        false
                                ))
                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                        .executes(context -> playerPhysicsEnable(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                BoolArgumentType.getBool(context, "debugProxy")
                                        )))))
                .then(Commands.literal("disable")
                        .executes(context -> playerPhysicsDisable(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> playerPhysicsStatus(context.getSource())))
                .then(Commands.literal("impulse")
                        .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .executes(context -> playerPhysicsImpulse(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "x"),
                                                        FloatArgumentType.getFloat(context, "y"),
                                                        FloatArgumentType.getFloat(context, "z")
                                                ))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerProxyCommands() {
        return Commands.literal("player_proxy")
                .then(Commands.literal("enable")
                        .executes(context -> playerProxyEnable(
                                context.getSource(),
                                PlayerProxyManager.DEFAULT_PROXY_MASS,
                                false
                        ))
                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                .executes(context -> playerProxyEnable(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "mass"),
                                        false
                                ))
                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                        .executes(context -> playerProxyEnable(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                BoolArgumentType.getBool(context, "debugProxy")
                                        )))))
                .then(Commands.literal("disable")
                        .executes(context -> playerProxyDisable(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> playerProxyStatus(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechanicsCommands() {
        return Commands.literal("mechanics")
                .then(Commands.literal("spawn_box")
                        .executes(context -> mechanicsSpawnBox(context.getSource(), 1.0F, 1.0F, false))
                        .then(Commands.argument("size", FloatArgumentType.floatArg(0.05F, 16.0F))
                                .executes(context -> mechanicsSpawnBox(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "size"),
                                        1.0F,
                                        false
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> mechanicsSpawnBox(
                                                context.getSource(),
                                                FloatArgumentType.getFloat(context, "size"),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                false
                                        ))
                                        .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                .executes(context -> mechanicsSpawnBox(
                                                        context.getSource(),
                                                        FloatArgumentType.getFloat(context, "size"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        BoolArgumentType.getBool(context, "debugProxy")
                                                ))))))
                .then(Commands.literal("list")
                        .executes(context -> mechanicsList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> mechanicsList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("impulse")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                        .executes(context -> mechanicsImpulse(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "idPrefix"),
                                                                FloatArgumentType.getFloat(context, "x"),
                                                                FloatArgumentType.getFloat(context, "y"),
                                                                FloatArgumentType.getFloat(context, "z")
                                                        )))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsRemove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("show")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsShowProxy(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("hide")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> mechanicsHideProxy(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> blockCommands() {
        return Commands.literal("block")
                .then(Commands.literal("detach")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> detachBlock(
                                        context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        1.0F,
                                        true
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> detachBlock(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                true
                                        ))
                                        .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                .executes(context -> detachBlock(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        BoolArgumentType.getBool(context, "debugProxy")
                                                ))))))
                .then(Commands.literal("detach_box")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> detachBlockBox(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                1.0F,
                                                true
                                        ))
                                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                .executes(context -> detachBlockBox(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        true
                                                ))
                                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                        .executes(context -> detachBlockBox(
                                                                context.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                                FloatArgumentType.getFloat(context, "mass"),
                                                                BoolArgumentType.getBool(context, "debugProxy")
                                                        )))))))
                .then(Commands.literal("list")
                        .executes(context -> detachedBlockList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> detachedBlockList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> restoreDetachedBlock(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> removeDetachedBlock(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sublevelCommands() {
        return Commands.literal("sublevel")
                .then(Commands.literal("assemble_block")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> assembleSubLevelBlock(
                                        context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        1.0F,
                                        true
                                ))
                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                        .executes(context -> assembleSubLevelBlock(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                FloatArgumentType.getFloat(context, "mass"),
                                                true
                                        ))
                                        .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                .executes(context -> assembleSubLevelBlock(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        BoolArgumentType.getBool(context, "debugProxy")
                                                ))))))
                .then(Commands.literal("assemble_box")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> assembleSubLevelBox(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                1.0F,
                                                true
                                        ))
                                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0.01F, 10000.0F))
                                                .executes(context -> assembleSubLevelBox(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                        FloatArgumentType.getFloat(context, "mass"),
                                                        true
                                                ))
                                                .then(Commands.argument("debugProxy", BoolArgumentType.bool())
                                                        .executes(context -> assembleSubLevelBox(
                                                                context.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                                                FloatArgumentType.getFloat(context, "mass"),
                                                                BoolArgumentType.getBool(context, "debugProxy")
                                                        )))))))
                .then(Commands.literal("list")
                        .executes(context -> subLevelList(context.getSource(), 8))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> subLevelList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")
                                ))))
                .then(Commands.literal("pick")
                        .executes(context -> subLevelPick(context.getSource(), 16.0F))
                        .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(0.1F, 128.0F))
                                .executes(context -> subLevelPick(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "maxDistance")
                                ))))
                .then(Commands.literal("break")
                        .executes(context -> subLevelBreak(context.getSource(), 16.0F))
                        .then(Commands.argument("maxDistance", FloatArgumentType.floatArg(0.1F, 128.0F))
                                .executes(context -> subLevelBreak(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "maxDistance")
                                ))))
                .then(Commands.literal("impulse")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .then(Commands.argument("x", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                        .then(Commands.argument("y", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                .then(Commands.argument("z", FloatArgumentType.floatArg(-100000.0F, 100000.0F))
                                                        .executes(context -> subLevelImpulse(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "idPrefix"),
                                                                FloatArgumentType.getFloat(context, "x"),
                                                                FloatArgumentType.getFloat(context, "y"),
                                                                FloatArgumentType.getFloat(context, "z")
                                                        )))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(context -> removeSubLevel(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "idPrefix")
                                ))))
                .then(Commands.literal("status")
                        .executes(context -> subLevelStatus(context.getSource())));
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

    private static int playerProxyEnable(CommandSourceStack source, float mass, boolean debugProxy) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player proxy can only be enabled by a player"));
            return 0;
        }
        try {
            PlayerProxySnapshot snapshot = PlayerProxyManager.INSTANCE.enable(player, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Enabled PhysX player proxy for " + snapshot.playerName()
                            + "; body=" + snapshot.body().id()
                            + "; pos=" + describeVector(snapshot.body().pose().position())
                            + "; halfExtents=" + describeVector(snapshot.body().halfExtents())
                            + "; mass=" + String.format("%.2f", snapshot.body().mass())
                            + "; debugProxy=" + snapshot.debugProxy()
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to enable player proxy: " + exception.getMessage()));
            return 0;
        }
    }

    private static int playerProxyDisable(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player proxy can only be disabled by a player"));
            return 0;
        }
        return PlayerProxyManager.INSTANCE.disable(player)
                .map(snapshot -> {
                    source.sendSuccess(() -> Component.literal(
                            "Disabled PhysX player proxy for " + snapshot.playerName()
                                    + "; body=" + snapshot.body().id()
                                    + "; lastPos=" + describeVector(snapshot.body().pose().position())
                                    + "; lastVel=" + describeVector(snapshot.body().linearVelocity())
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Player proxy is not enabled for " + player.getScoreboardName()));
                    return 0;
                });
    }

    private static int playerProxyStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player proxy status can only be queried by a player"));
            return 0;
        }
        Optional<PlayerProxySnapshot> maybeSnapshot = PlayerProxyManager.INSTANCE.snapshot(player);
        if (maybeSnapshot.isEmpty()) {
            source.sendFailure(Component.literal("Player proxy is not enabled for " + player.getScoreboardName()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(describePlayerProxy(maybeSnapshot.get())), false);
        return 1;
    }

    private static int playerPhysicsEnable(CommandSourceStack source, float mass, boolean debugProxy) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics can only be enabled by a player"));
            return 0;
        }
        try {
            PlayerPhysicsSnapshot snapshot = PlayerPhysicsManager.INSTANCE.enable(player, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Enabled PhysX player body for " + snapshot.playerName()
                            + "; body=" + snapshot.body().id()
                            + "; pos=" + describeVector(snapshot.body().pose().position())
                            + "; halfExtents=" + describeVector(snapshot.body().halfExtents())
                            + "; mass=" + String.format("%.2f", snapshot.body().mass())
                            + "; debugProxy=" + snapshot.debugProxy()
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to enable player physics: " + exception.getMessage()));
            return 0;
        }
    }

    private static int playerPhysicsDisable(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics can only be disabled by a player"));
            return 0;
        }
        return PlayerPhysicsManager.INSTANCE.disable(player)
                .map(snapshot -> {
                    source.sendSuccess(() -> Component.literal(
                            "Disabled PhysX player body for " + snapshot.playerName()
                                    + "; body=" + snapshot.body().id()
                                    + "; lastPos=" + describeVector(snapshot.body().pose().position())
                                    + "; lastVel=" + describeVector(snapshot.body().linearVelocity())
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Player physics is not enabled for " + player.getScoreboardName()));
                    return 0;
                });
    }

    private static int playerPhysicsStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics status can only be queried by a player"));
            return 0;
        }
        Optional<PlayerPhysicsSnapshot> maybeSnapshot = PlayerPhysicsManager.INSTANCE.snapshot(player);
        if (maybeSnapshot.isEmpty()) {
            source.sendFailure(Component.literal("Player physics is not enabled for " + player.getScoreboardName()));
            return 0;
        }
        PlayerPhysicsSnapshot snapshot = maybeSnapshot.get();
        source.sendSuccess(() -> Component.literal(describePlayerPhysics(snapshot)), false);
        return 1;
    }

    private static int playerPhysicsImpulse(CommandSourceStack source, float x, float y, float z) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player physics impulse can only be applied by a player"));
            return 0;
        }
        Optional<PlayerPhysicsSnapshot> before = PlayerPhysicsManager.INSTANCE.snapshot(player);
        if (before.isEmpty()) {
            source.sendFailure(Component.literal("Player physics is not enabled for " + player.getScoreboardName()));
            return 0;
        }
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        Optional<PlayerPhysicsSnapshot> after = PlayerPhysicsManager.INSTANCE.applyImpulse(player, impulse);
        if (after.isEmpty()) {
            source.sendFailure(Component.literal("Failed to apply impulse to PhysX player body"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Applied impulse " + describeVector(impulse)
                        + " to PhysX player body " + after.get().body().id()
                        + "; oldVel=" + describeVector(before.get().body().linearVelocity())
                        + "; newVel=" + describeVector(after.get().body().linearVelocity())
        ), true);
        return 1;
    }

    private static int mechanicsSpawnBox(CommandSourceStack source, float size, float mass, boolean debugProxy) {
        try {
            float halfExtent = size * 0.5F;
            Vec3 position = source.getPosition();
            MechanicsWorld world = PhysX4mc.api().world(source.getLevel());
            MechanicsBodySnapshot body = world.createDynamicBox(MechanicsBoxDefinition.gameplayDynamicBox(
                    new PhysicsPose(
                            new PhysicsVector(position.x(), position.y() + halfExtent + 1.5D, position.z()),
                            PhysicsQuaternion.IDENTITY
                    ),
                    new PhysicsVector(halfExtent, halfExtent, halfExtent),
                    mass
            ));

            String proxyStatus = debugProxy
                    ? MechanicsDebugProxies.show(source.getLevel(), body.id())
                            .map(proxy -> "debugProxy=" + proxy.entityId() + (proxy.created() ? " created" : " existing"))
                            .orElse("debugProxy=<failed>")
                    : "debugProxy=<none>";

            source.sendSuccess(() -> Component.literal(
                    "Spawned mechanics box " + body.id()
                            + "; size=" + String.format("%.2f", size)
                            + ", mass=" + String.format("%.2f", mass)
                            + "; " + proxyStatus
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to spawn mechanics box: " + exception.getMessage()));
            return 0;
        }
    }

    private static int mechanicsList(CommandSourceStack source, int limit) {
        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return 0;
        }

        Vec3 origin = source.getPosition();
        List<MechanicsBodySnapshot> allSnapshots = maybeWorld.get().snapshots().stream()
                .filter(snapshot -> !snapshot.closed())
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics bodies in this level"));
            return 0;
        }

        List<MechanicsBodySnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " mechanics bodies in this level"
        ), false);
        for (MechanicsBodySnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeMechanicsBody(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int mechanicsImpulse(CommandSourceStack source, String idPrefix, float x, float y, float z) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsWorld world = PhysX4mc.api().existingWorld(source.getLevel()).orElseThrow();
        MechanicsBodySnapshot body = maybeBody.get();
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        if (!world.applyLinearImpulse(body.id(), impulse)) {
            source.sendFailure(Component.literal("Failed to apply impulse to mechanics body " + body.id()));
            return 0;
        }
        PhysicsVector newVelocity = world.snapshot(body.id())
                .map(MechanicsBodySnapshot::linearVelocity)
                .orElse(PhysicsVector.ZERO);
        source.sendSuccess(() -> Component.literal(
                "Applied impulse " + describeVector(impulse)
                        + " to mechanics body " + body.id()
                        + "; oldVel=" + describeVector(body.linearVelocity())
                        + "; newVel=" + describeVector(newVelocity)
        ), true);
        return 1;
    }

    private static int mechanicsRemove(CommandSourceStack source, String idPrefix) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsWorld world = PhysX4mc.api().existingWorld(source.getLevel()).orElseThrow();
        MechanicsBodySnapshot body = maybeBody.get();
        if (!world.removeBody(body.id())) {
            source.sendFailure(Component.literal("Failed to remove mechanics body " + body.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Removed mechanics body " + body.id()
                        + "; lastPos=" + describeVector(body.pose().position())
                        + "; lastVel=" + describeVector(body.linearVelocity())
        ), true);
        return 1;
    }

    private static int mechanicsShowProxy(CommandSourceStack source, String idPrefix) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot body = maybeBody.get();
        Optional<MechanicsDebugProxy> proxy = MechanicsDebugProxies.show(source.getLevel(), body.id());
        if (proxy.isEmpty()) {
            source.sendFailure(Component.literal("Failed to show debug proxy for mechanics body " + body.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                (proxy.get().created() ? "Created" : "Found existing")
                        + " debug proxy " + proxy.get().entityId()
                        + " for mechanics body " + body.id()
        ), true);
        return 1;
    }

    private static int mechanicsHideProxy(CommandSourceStack source, String idPrefix) {
        Optional<MechanicsBodySnapshot> maybeBody = findMechanicsBody(source, idPrefix);
        if (maybeBody.isEmpty()) {
            return 0;
        }

        MechanicsBodySnapshot body = maybeBody.get();
        if (!MechanicsDebugProxies.hide(source.getLevel(), body.id())) {
            source.sendFailure(Component.literal("No debug proxy is bound to mechanics body " + body.id()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Hid debug proxy for mechanics body " + body.id()), true);
        return 1;
    }

    private static int detachBlock(CommandSourceStack source, BlockPos pos, float mass, boolean debugProxy) {
        try {
            SubLevelSnapshot subLevel = SubLevelManager.INSTANCE.assembleBlock(source.getLevel(), pos, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Detached block " + blockName(subLevel)
                            + " at " + describeBlockPos(subLevel.firstBlock().sourcePos())
                            + " into sublevel " + subLevel.id()
                            + "; body=" + subLevel.body().id()
                            + "; blocks=" + subLevel.blockCount()
                            + "; mass=" + String.format("%.2f", mass)
                            + "; debugProxy=" + debugProxy
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to detach block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int detachBlockBox(CommandSourceStack source, BlockPos from, BlockPos to, float mass, boolean debugProxy) {
        return assembleSubLevelBox(source, from, to, mass, debugProxy);
    }

    private static int assembleSubLevelBlock(CommandSourceStack source, BlockPos pos, float mass, boolean debugProxy) {
        try {
            SubLevelSnapshot subLevel = SubLevelManager.INSTANCE.assembleBlock(source.getLevel(), pos, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Assembled sublevel " + subLevel.id()
                            + "; body=" + subLevel.body().id()
                            + "; block=" + blockName(subLevel)
                            + "; source=" + describeSubLevelBounds(subLevel)
                            + "; mass=" + String.format("%.2f", mass)
                            + "; debugProxy=" + debugProxy
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to assemble sublevel block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int assembleSubLevelBox(CommandSourceStack source, BlockPos from, BlockPos to, float mass, boolean debugProxy) {
        try {
            SubLevelSnapshot subLevel = SubLevelManager.INSTANCE.assembleBox(source.getLevel(), from, to, mass, debugProxy);
            source.sendSuccess(() -> Component.literal(
                    "Assembled sublevel " + subLevel.id()
                            + "; body=" + subLevel.body().id()
                            + "; blocks=" + subLevel.blockCount()
                            + "; bounds=" + describeSubLevelBounds(subLevel)
                            + "; mass=" + String.format("%.2f", mass)
                            + "; debugProxy=" + debugProxy
            ), true);
            return subLevel.blockCount();
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to assemble sublevel: " + exception.getMessage()));
            return 0;
        }
    }

    private static int detachedBlockList(CommandSourceStack source, int limit) {
        return subLevelList(source, limit);
    }

    private static int subLevelList(CommandSourceStack source, int limit) {
        Vec3 origin = source.getPosition();
        List<SubLevelSnapshot> allSnapshots = SubLevelManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> !snapshot.body().closed())
                .sorted(Comparator.comparingDouble(snapshot -> distanceSqr(snapshot, origin)))
                .toList();
        if (allSnapshots.isEmpty()) {
            source.sendFailure(Component.literal("No sublevels in this level"));
            return 0;
        }

        List<SubLevelSnapshot> snapshots = allSnapshots.stream()
                .limit(limit)
                .toList();
        source.sendSuccess(() -> Component.literal(
                "Showing " + snapshots.size() + " of " + allSnapshots.size() + " sublevels in this level"
        ), false);
        for (SubLevelSnapshot snapshot : snapshots) {
            double distance = Math.sqrt(distanceSqr(snapshot, origin));
            source.sendSuccess(() -> Component.literal(describeSubLevel(snapshot, distance)), false);
        }
        return snapshots.size();
    }

    private static int restoreDetachedBlock(CommandSourceStack source, String idPrefix) {
        Optional<SubLevelSnapshot> maybeSubLevel = findSubLevel(source, idPrefix);
        if (maybeSubLevel.isEmpty()) {
            return 0;
        }

        SubLevelSnapshot subLevel = maybeSubLevel.get();
        try {
            return SubLevelManager.INSTANCE.restoreOriginal(source.getLevel(), subLevel.id())
                    .map(restored -> {
                        source.sendSuccess(() -> Component.literal(
                                "Restored sublevel " + restored.id()
                                        + " at " + describeSubLevelBounds(restored)
                                        + "; blocks=" + restored.blockCount()
                                        + " and removed mechanics body " + restored.body().id()
                        ), true);
                        return 1;
                    })
                    .orElseGet(() -> {
                        source.sendFailure(Component.literal("Sublevel " + subLevel.id() + " no longer exists"));
                        return 0;
                    });
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to restore sublevel: " + exception.getMessage()));
            return 0;
        }
    }

    private static int removeDetachedBlock(CommandSourceStack source, String idPrefix) {
        return removeSubLevel(source, idPrefix);
    }

    private static int removeSubLevel(CommandSourceStack source, String idPrefix) {
        Optional<SubLevelSnapshot> maybeSubLevel = findSubLevel(source, idPrefix);
        if (maybeSubLevel.isEmpty()) {
            return 0;
        }

        SubLevelSnapshot subLevel = maybeSubLevel.get();
        return SubLevelManager.INSTANCE.remove(source.getLevel(), subLevel.id())
                .map(removed -> {
                    source.sendSuccess(() -> Component.literal(
                            "Removed sublevel " + removed.id()
                                    + "; body=" + removed.body().id()
                                    + "; source=" + describeSubLevelBounds(removed)
                                    + "; blocks=" + removed.blockCount()
                                    + "; block=" + blockName(removed)
                    ), true);
                    return 1;
                })
                .orElseGet(() -> {
                    source.sendFailure(Component.literal("Sublevel " + subLevel.id() + " no longer exists"));
                    return 0;
                });
    }

    private static int subLevelStatus(CommandSourceStack source) {
        List<SubLevelSnapshot> snapshots = SubLevelManager.INSTANCE.snapshots(source.getLevel());
        int blockCount = snapshots.stream()
                .mapToInt(SubLevelSnapshot::blockCount)
                .sum();
        int visualCount = snapshots.stream()
                .mapToInt(SubLevelSnapshot::visualCount)
                .sum();
        int dirtyBlockCount = snapshots.stream()
                .mapToInt(SubLevelSnapshot::dirtyBlockCount)
                .sum();
        source.sendSuccess(() -> Component.literal(
                "Sublevels=" + snapshots.size()
                        + ", blocks=" + blockCount
                        + ", dirtyBlocks=" + dirtyBlockCount
                        + ", visuals=" + visualCount
        ), false);
        return snapshots.size();
    }

    private static int subLevelPick(CommandSourceStack source, float maxDistance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Sublevel pick can only be used by a player"));
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        PhysicsVector origin = new PhysicsVector(eye.x(), eye.y(), eye.z());
        PhysicsVector direction = new PhysicsVector(look.x(), look.y(), look.z());
        try {
            Optional<SubLevelPickResult> result = SubLevelManager.INSTANCE.pickBlock(
                    source.getLevel(),
                    origin,
                    direction,
                    maxDistance
            );
            if (result.isEmpty()) {
                source.sendFailure(Component.literal("No sublevel block hit within " + String.format("%.2f", maxDistance) + " blocks"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(describeSubLevelPick(result.get())), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to pick sublevel block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int subLevelBreak(CommandSourceStack source, float maxDistance) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Sublevel break can only be used by a player"));
            return 0;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        PhysicsVector origin = new PhysicsVector(eye.x(), eye.y(), eye.z());
        PhysicsVector direction = new PhysicsVector(look.x(), look.y(), look.z());
        try {
            Optional<SubLevelBreakResult> result = SubLevelManager.INSTANCE.breakPickedBlock(
                    source.getLevel(),
                    origin,
                    direction,
                    maxDistance
            );
            if (result.isEmpty()) {
                source.sendFailure(Component.literal("No sublevel block hit within " + String.format("%.2f", maxDistance) + " blocks"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(describeSubLevelBreak(result.get())), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to break sublevel block: " + exception.getMessage()));
            return 0;
        }
    }

    private static int subLevelImpulse(CommandSourceStack source, String idPrefix, float x, float y, float z) {
        Optional<SubLevelSnapshot> maybeSubLevel = findSubLevel(source, idPrefix);
        if (maybeSubLevel.isEmpty()) {
            return 0;
        }

        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return 0;
        }

        SubLevelSnapshot subLevel = maybeSubLevel.get();
        PhysicsVector impulse = new PhysicsVector(x, y, z);
        MechanicsWorld world = maybeWorld.get();
        if (!world.applyLinearImpulse(subLevel.body().id(), impulse)) {
            source.sendFailure(Component.literal("Failed to apply impulse to sublevel " + subLevel.id() + " body " + subLevel.body().id()));
            return 0;
        }
        PhysicsVector newVelocity = world.snapshot(subLevel.body().id())
                .map(MechanicsBodySnapshot::linearVelocity)
                .orElse(PhysicsVector.ZERO);
        source.sendSuccess(() -> Component.literal(
                "Applied impulse " + describeVector(impulse)
                        + " to sublevel " + subLevel.id()
                        + "; body=" + subLevel.body().id()
                        + "; oldVel=" + describeVector(subLevel.body().linearVelocity())
                        + "; newVel=" + describeVector(newVelocity)
        ), true);
        return 1;
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
        int playerProxies = PlayerProxyManager.INSTANCE.forgetLevel(source.getLevel());
        int playerBindings = PlayerPhysicsManager.INSTANCE.forgetLevel(source.getLevel());
        int removed = ServerPhysicsRuntime.INSTANCE.clearLevel(source.getLevel());
        int sublevels = SubLevelManager.INSTANCE.forgetLevel(source.getLevel());
        source.sendSuccess(() -> Component.literal(
                "Cleared " + removed + " physics objects in this level"
                        + (playerProxies > 0 ? "; released " + playerProxies + " player proxies" : "")
                        + (playerBindings > 0 ? "; released " + playerBindings + " player physics bindings" : "")
                        + (sublevels > 0 ? "; forgot " + sublevels + " sublevel records" : "")
        ), true);
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

    private static Optional<MechanicsBodySnapshot> findMechanicsBody(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Mechanics body id prefix must not be empty"));
            return Optional.empty();
        }

        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(source.getLevel());
        if (maybeWorld.isEmpty()) {
            source.sendFailure(Component.literal("No mechanics world exists in this level"));
            return Optional.empty();
        }

        List<MechanicsBodySnapshot> matches = maybeWorld.get().snapshots().stream()
                .filter(snapshot -> !snapshot.closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            Optional<SubLevelSnapshot> sublevelMatch = findSubLevelSilently(source, normalizedPrefix);
            if (sublevelMatch.isPresent()) {
                source.sendFailure(Component.literal(
                        "Prefix " + idPrefix + " matches sublevel " + sublevelMatch.get().id()
                                + "; use /px4mc sublevel impulse " + idPrefix + " <x> <y> <z>, or use body id "
                                + sublevelMatch.get().body().id()
                ));
                return Optional.empty();
            }
            source.sendFailure(Component.literal("No mechanics body starts with id prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " mechanics bodies; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<SubLevelSnapshot> findSubLevel(CommandSourceStack source, String idPrefix) {
        String normalizedPrefix = idPrefix.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrefix.isEmpty()) {
            source.sendFailure(Component.literal("Sublevel id prefix must not be empty"));
            return Optional.empty();
        }

        List<SubLevelSnapshot> matches = SubLevelManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> !snapshot.body().closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                        || snapshot.body().id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("No sublevel starts with id/body prefix " + idPrefix));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            source.sendFailure(Component.literal("Id prefix " + idPrefix + " matches " + matches.size() + " sublevels; use a longer prefix"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Optional<SubLevelSnapshot> findSubLevelSilently(CommandSourceStack source, String normalizedPrefix) {
        return SubLevelManager.INSTANCE.snapshots(source.getLevel()).stream()
                .filter(snapshot -> !snapshot.body().closed())
                .filter(snapshot -> snapshot.id().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .findFirst();
    }

    private static String describeMechanicsBody(MechanicsBodySnapshot snapshot, double distance) {
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " role=" + snapshot.role()
                + " type=" + snapshot.type()
                + " pos=" + describeVector(snapshot.pose().position())
                + " vel=" + describeVector(snapshot.linearVelocity())
                + " halfExtents=" + describeVector(snapshot.halfExtents())
                + " mass=" + String.format("%.2f", snapshot.mass())
                + " distance=" + String.format("%.2f", distance);
    }

    private static String describeSubLevel(SubLevelSnapshot snapshot, double distance) {
        MechanicsBodySnapshot body = snapshot.body();
        return shortId(snapshot)
                + " id=" + snapshot.id()
                + " body=" + body.id()
                + " block=" + blockName(snapshot)
                + " blocks=" + snapshot.blockCount()
                + " dirty=" + snapshot.dirtyBlockCount()
                + " visuals=" + snapshot.visualCount()
                + " source=" + describeSubLevelBounds(snapshot)
                + " pos=" + describeVector(body.pose().position())
                + " vel=" + describeVector(body.linearVelocity())
                + " halfExtents=" + describeVector(body.halfExtents())
                + " mass=" + String.format("%.2f", body.mass())
                + " distance=" + String.format("%.2f", distance);
    }

    private static String describeSubLevelPick(SubLevelPickResult result) {
        return "SubLevelPick id=" + result.id()
                + " body=" + result.body().id()
                + " block=" + blockName(result.blockState())
                + " local=" + describeBlockPos(result.localPos())
                + " source=" + describeBlockPos(result.block().sourcePos())
                + " worldHit=" + describeVector(result.worldHit())
                + " localHit=" + describeVector(result.localHit())
                + " distance=" + String.format("%.3f", result.distance());
    }

    private static String describeSubLevelBreak(SubLevelBreakResult result) {
        SubLevelPickResult pick = result.pick();
        return "SubLevelBreak id=" + pick.id()
                + " body=" + pick.body().id()
                + " block=" + blockName(pick.blockState())
                + " local=" + describeBlockPos(pick.localPos())
                + " source=" + describeBlockPos(pick.block().sourcePos())
                + " remainingBlocks=" + result.remainingBlocks()
                + " dirtyBlocks=" + result.dirtyBlocks()
                + " removedVisuals=" + result.removedVisuals()
                + " removedSublevel=" + result.removedSubLevel();
    }

    private static String describePlayerPhysics(PlayerPhysicsSnapshot snapshot) {
        MechanicsBodySnapshot body = snapshot.body();
        return "PlayerPhysics player=" + snapshot.playerName()
                + " body=" + body.id()
                + " role=" + body.role()
                + " pos=" + describeVector(body.pose().position())
                + " vel=" + describeVector(body.linearVelocity())
                + " halfExtents=" + describeVector(body.halfExtents())
                + " mass=" + String.format("%.2f", body.mass())
                + " debugProxy=" + snapshot.debugProxy()
                + " syncedTicks=" + snapshot.syncedTicks();
    }

    private static String describePlayerProxy(PlayerProxySnapshot snapshot) {
        MechanicsBodySnapshot body = snapshot.body();
        return "PlayerProxy player=" + snapshot.playerName()
                + " body=" + body.id()
                + " role=" + body.role()
                + " pos=" + describeVector(body.pose().position())
                + " vel=" + describeVector(body.linearVelocity())
                + " halfExtents=" + describeVector(body.halfExtents())
                + " mass=" + String.format("%.2f", body.mass())
                + " debugProxy=" + snapshot.debugProxy()
                + " syncedTicks=" + snapshot.syncedTicks();
    }

    private static String shortId(PhysicsObjectSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(MechanicsBodySnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static String shortId(SubLevelSnapshot snapshot) {
        return snapshot.id().toString().substring(0, 8);
    }

    private static double distanceSqr(PhysicsObjectSnapshot snapshot, Vec3 origin) {
        PhysicsVector position = snapshot.pose().position();
        double dx = position.x() - origin.x();
        double dy = position.y() - origin.y();
        double dz = position.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqr(MechanicsBodySnapshot snapshot, Vec3 origin) {
        PhysicsVector position = snapshot.pose().position();
        double dx = position.x() - origin.x();
        double dy = position.y() - origin.y();
        double dz = position.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqr(SubLevelSnapshot snapshot, Vec3 origin) {
        return distanceSqr(snapshot.body(), origin);
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

    private static String describeBlockPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String blockName(SubLevelSnapshot snapshot) {
        String name = blockName(snapshot.firstBlockState());
        return snapshot.assembly() ? name + "+..." : name;
    }

    private static String blockName(net.minecraft.world.level.block.state.BlockState blockState) {
        return BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
    }

    private static String describeSubLevelBounds(SubLevelSnapshot snapshot) {
        if (!snapshot.assembly()) {
            return describeBlockPos(snapshot.firstBlock().sourcePos());
        }
        return describeBlockPos(snapshot.bounds().minSourcePos()) + " to " + describeBlockPos(snapshot.bounds().maxSourcePos());
    }
}
