package com.firedoge.px4mc.platform.neoforge;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.backend.physx.PhysXBackend;
import com.firedoge.px4mc.command.Px4mcCommands;
import com.firedoge.px4mc.minecraft.player.PlayerPhysicsManager;
import com.firedoge.px4mc.minecraft.player.PlayerProxyManager;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelEntityBridge;
import com.firedoge.px4mc.minecraft.sublevel.ServerSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelManager;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelPersistence;
import com.firedoge.px4mc.physics.PhysicsManager;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class NeoForgeEvents {
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Px4mcCommands.register(event);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        SubLevelPersistence.startServer();
        boolean physxAvailable = PhysicsManager.INSTANCE.backend(PhysXBackend.ID)
                .map(backend -> backend.isAvailable())
                .orElse(false);
        PhysX4mc.LOGGER.info("PhysX4mc server hooks ready; PhysX native loaded={}", physxAvailable);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        SubLevelPersistence.restore(event.getServer());
        PlayerProxyManager.INSTANCE.syncBeforePhysics(event.getServer());
        ServerPhysicsRuntime.INSTANCE.tick(event.getServer());
        PlayerPhysicsManager.INSTANCE.tick(event.getServer());
        SubLevelManager.INSTANCE.tick(event.getServer());
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelContainers.server(level).ifPresent(container -> {
                SubLevelEntityBridge.tickAttachedEntities(level, container);
                SubLevelEntityBridge.tickEntityInside(level, container);
                container.trackingSystem().tick();
            });
        }
        SubLevelPersistence.capturePeriodically(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendExistingPlotChunks(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendExistingPlotChunks(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendExistingPlotChunks(player);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            int removed = ServerPhysicsRuntime.INSTANCE.unloadChunkCollision(level, event.getChunk().getPos().toLong());
            if (removed > 0) {
                PhysX4mc.LOGGER.debug("Released {} physics terrain colliders for chunk {}", removed, event.getChunk().getPos());
            }
        }
    }

    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos());
            for (Direction side : event.getNotifiedSides()) {
                ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos().relative(side));
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.removeTerrainCollisionAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerPhysicsRuntime.INSTANCE.updateTerrainCollisionAt(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SubLevelPersistence.captureBeforeLevelSave(level);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        SubLevelPersistence.flush(event.getServer());
        PlayerProxyManager.INSTANCE.close(event.getServer());
        PlayerPhysicsManager.INSTANCE.close(event.getServer());
        SubLevelManager.INSTANCE.close(event.getServer());
        ServerPhysicsRuntime.INSTANCE.close(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ServerPhysicsRuntime.INSTANCE.close();
    }

    private static void sendExistingPlotChunks(ServerPlayer player) {
        ServerSubLevelContainer container = SubLevelContainers.server(player.serverLevel()).orElse(null);
        if (container != null) {
            container.trackingSystem().resyncPlayer(player);
        }
    }
}
