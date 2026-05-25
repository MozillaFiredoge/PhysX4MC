package com.firedoge.px4mc.minecraft.sublevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.render.ClientSubLevelBlockView;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ClientSubLevelContainer implements SubLevelContainer {
    private final ClientLevel level;
    private final Map<ChunkPos, LevelChunk> plotChunks = new LinkedHashMap<>();
    private final Map<SubLevelId, ClientTrackedSubLevel> trackedSubLevels = new LinkedHashMap<>();
    private final Map<ChunkPos, SubLevelId> trackedSubLevelsByChunk = new LinkedHashMap<>();
    private final Map<ChunkPos, RemovedClientSubLevelProjection> removedProjectionsByChunk = new LinkedHashMap<>();
    private final SubLevelPlotAllocator plotAllocator = new SubLevelPlotAllocator();
    private static final long REMOVED_PROJECTION_TTL_MILLIS = 2500L;

    public ClientSubLevelContainer(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public ClientLevel level() {
        return level;
    }

    @Override
    public List<PhysicsSubLevel> subLevels() {
        return List.of();
    }

    @Override
    public Optional<PhysicsSubLevel> subLevel(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        return Optional.empty();
    }

    @Override
    public Optional<PhysicsSubLevel> subLevelAtPlotBlock(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return trackedSubLevels.size();
    }

    public synchronized boolean inPlotBounds(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return plotAllocator.inBounds(chunkPos);
    }

    public synchronized boolean inPlotBounds(int chunkX, int chunkZ) {
        return plotAllocator.inBounds(chunkX, chunkZ);
    }

    public synchronized Optional<LevelChunk> plotChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return Optional.ofNullable(plotChunks.get(chunkPos));
    }

    public synchronized LevelChunk putPlotChunk(ChunkPos chunkPos, LevelChunk chunk) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        Objects.requireNonNull(chunk, "chunk");
        cleanupRemovedProjections();
        if (!chunk.getPos().equals(chunkPos)) {
            throw new IllegalArgumentException("Chunk " + chunk.getPos() + " does not match plot position " + chunkPos);
        }
        if (!inPlotBounds(chunkPos)) {
            throw new IllegalArgumentException("Chunk is outside sublevel plot bounds: " + chunkPos);
        }
        LevelChunk previous = plotChunks.put(chunkPos, chunk);
        ClientTrackedSubLevel tracked = trackedSubLevelForChunk(chunkPos).orElse(null);
        if (tracked != null) {
            tracked.markChunkLoaded(chunkPos);
        }
        return previous;
    }

    public synchronized Optional<LevelChunk> removePlotChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        cleanupRemovedProjections();
        trackedSubLevelForChunk(chunkPos).ifPresent(tracked -> tracked.markChunkDropped(chunkPos));
        return Optional.ofNullable(plotChunks.remove(chunkPos));
    }

    public synchronized int loadedPlotChunkCount() {
        return plotChunks.size();
    }

    @Override
    public synchronized void clientStartTracking(SubLevelClientMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        cleanupRemovedProjections();
        clientStopTracking(metadata.id());
        ClientTrackedSubLevel tracked = new ClientTrackedSubLevel(metadata);
        tracked.attachLevelView(new ClientSubLevelBlockView(this, tracked));
        trackedSubLevels.put(metadata.id(), tracked);
        for (ChunkPos chunkPos : metadata.chunkPositions()) {
            if (!inPlotBounds(chunkPos)) {
                throw new IllegalArgumentException("Sublevel metadata references chunk outside plotyard: " + chunkPos);
            }
            trackedSubLevelsByChunk.put(chunkPos, metadata.id());
            if (plotChunks.containsKey(chunkPos)) {
                tracked.markChunkLoaded(chunkPos);
            }
        }
    }

    @Override
    public synchronized void clientFinalizeTracking(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        ClientTrackedSubLevel tracked = trackedSubLevels.get(id);
        if (tracked != null) {
            tracked.finalizeTracking();
        }
    }

    @Override
    public synchronized void clientUpdateTransform(SubLevelId id, PhysicsPose pose) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pose, "pose");
        ClientTrackedSubLevel tracked = trackedSubLevels.get(id);
        if (tracked != null) {
            tracked.updatePose(pose);
        }
    }

    @Override
    public synchronized void clientStopTracking(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        cleanupRemovedProjections();
        ClientTrackedSubLevel removed = trackedSubLevels.remove(id);
        if (removed == null) {
            return;
        }
        RemovedClientSubLevelProjection projection = new RemovedClientSubLevelProjection(
                removed.metadata(),
                System.currentTimeMillis() + REMOVED_PROJECTION_TTL_MILLIS
        );
        for (ChunkPos chunkPos : removed.metadata().chunkPositions()) {
            trackedSubLevelsByChunk.remove(chunkPos, id);
            removedProjectionsByChunk.put(chunkPos, projection);
            LevelChunk chunk = plotChunks.remove(chunkPos);
            if (chunk != null) {
                chunk.setLoaded(false);
                level.unload(chunk);
                level.getLightEngine().setLightEnabled(chunkPos, false);
            }
        }
    }

    public synchronized List<ClientTrackedSubLevel> trackedSubLevels() {
        return List.copyOf(trackedSubLevels.values());
    }

    public synchronized Optional<ClientTrackedSubLevel> trackedSubLevel(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(trackedSubLevels.get(id));
    }

    public synchronized Optional<ClientTrackedSubLevel> trackedSubLevelForChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        SubLevelId id = trackedSubLevelsByChunk.get(chunkPos);
        return id == null ? Optional.empty() : Optional.ofNullable(trackedSubLevels.get(id));
    }

    public synchronized void markPlotBlockChanged(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        trackedSubLevelForChunk(new ChunkPos(plotPos))
                .filter(subLevel -> subLevel.plot().containsPlotBlockPos(plotPos))
                .ifPresent(ClientTrackedSubLevel::invalidateCollisionGeometry);
    }

    public synchronized Optional<RemovedClientSubLevelProjection> removedProjectionForChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        cleanupRemovedProjections();
        return Optional.ofNullable(removedProjectionsByChunk.get(chunkPos));
    }

    private void cleanupRemovedProjections() {
        long now = System.currentTimeMillis();
        removedProjectionsByChunk.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    public record RemovedClientSubLevelProjection(SubLevelClientMetadata metadata, long expiresAtMillis) {
        public RemovedClientSubLevelProjection {
            Objects.requireNonNull(metadata, "metadata");
        }

        boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }
}
