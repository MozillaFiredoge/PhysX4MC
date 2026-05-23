package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ServerSubLevelContainer implements SubLevelContainer {
    private final ServerLevel level;
    private final Map<SubLevelId, PhysicsSubLevel> subLevels = new LinkedHashMap<>();
    private final Map<ChunkPos, SubLevelId> subLevelsByChunk = new LinkedHashMap<>();
    private final Map<ChunkPos, PlotChunkHolder> plotChunkHolders = new LinkedHashMap<>();
    private final SubLevelPlotAllocator plotAllocator = new SubLevelPlotAllocator();
    private final SubLevelTrackingSystem trackingSystem = new SubLevelTrackingSystem(this);
    private final ThreadLocal<Boolean> rebuildingPlotChunks = ThreadLocal.withInitial(() -> false);

    public ServerSubLevelContainer(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    public synchronized SubLevelPlot allocatePlot(SubLevelBounds bounds) {
        return plotAllocator.allocate(bounds, level.getMinSection(), level.getSectionsCount());
    }

    public void add(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        synchronized (this) {
            if (!subLevel.levelKey().equals(level.dimension())) {
                throw new IllegalArgumentException("Sublevel belongs to " + subLevel.levelKey().location()
                        + ", not " + level.dimension().location());
            }
            for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
                SubLevelId occupant = subLevelsByChunk.get(chunkPos);
                if (occupant != null && !occupant.equals(subLevel.id())) {
                    throw new IllegalArgumentException("Duplicate sublevel plot chunk " + chunkPos);
                }
            }
            PhysicsSubLevel previous = subLevels.putIfAbsent(subLevel.id(), subLevel);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate sublevel id " + subLevel.id());
            }
        }

        try {
            rebuildPlotChunks(subLevel);
            trackingSystem.onSubLevelAdded(subLevel);
        } catch (RuntimeException exception) {
            remove(subLevel.id());
            throw exception;
        }
    }

    public Optional<PhysicsSubLevel> remove(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        PhysicsSubLevel removed;
        List<ChunkPos> removedChunks = List.of();
        synchronized (this) {
            removed = subLevels.remove(id);
            if (removed != null) {
                removedChunks = removePlotChunks(removed);
            }
        }
        if (removed != null) {
            trackingSystem.onSubLevelRemoved(removed, removedChunks);
        }
        return Optional.ofNullable(removed);
    }

    public void clear() {
        List<RemovedSubLevel> removed;
        synchronized (this) {
            removed = subLevels.values().stream()
                    .map(subLevel -> new RemovedSubLevel(
                            subLevel,
                            subLevel.plot().chunkPositions().stream()
                                    .filter(plotChunkHolders::containsKey)
                                    .toList()
                    ))
                    .toList();
            subLevels.clear();
            subLevelsByChunk.clear();
            plotChunkHolders.clear();
        }
        for (RemovedSubLevel entry : removed) {
            trackingSystem.onSubLevelRemoved(entry.subLevel(), entry.removedChunks());
        }
        trackingSystem.clear();
    }

    @Override
    public synchronized List<PhysicsSubLevel> subLevels() {
        return List.copyOf(subLevels.values());
    }

    @Override
    public synchronized Optional<PhysicsSubLevel> subLevel(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(subLevels.get(id));
    }

    @Override
    public synchronized Optional<PhysicsSubLevel> subLevelAtPlotBlock(BlockPos plotPos) {
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsSubLevel> maybeByChunk = subLevelAtChunk(new ChunkPos(plotPos));
        if (maybeByChunk.isPresent()) {
            PhysicsSubLevel subLevel = maybeByChunk.get();
            if (subLevel.plot().containsPlotBlockPos(plotPos)) {
                return Optional.of(subLevel);
            }
            return Optional.empty();
        }
        for (PhysicsSubLevel subLevel : subLevels.values()) {
            if (subLevel.plot().containsPlotBlockPos(plotPos)) {
                return Optional.of(subLevel);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized int size() {
        return subLevels.size();
    }

    public synchronized boolean inPlotBounds(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return plotAllocator.inBounds(chunkPos);
    }

    public synchronized boolean inPlotBounds(int chunkX, int chunkZ) {
        return plotAllocator.inBounds(chunkX, chunkZ);
    }

    public synchronized Optional<PhysicsSubLevel> subLevelAtChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        SubLevelId id = subLevelsByChunk.get(chunkPos);
        return id == null ? Optional.empty() : Optional.ofNullable(subLevels.get(id));
    }

    public synchronized SubLevelTrackingSystem trackingSystem() {
        return trackingSystem;
    }

    public synchronized Optional<LevelChunk> plotChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return plotChunkHolder(chunkPos).map(PlotChunkHolder::chunk);
    }

    public synchronized List<LevelChunk> plotChunks() {
        return plotChunkHolders.values().stream()
                .map(PlotChunkHolder::chunk)
                .toList();
    }

    public synchronized Optional<PlotChunkHolder> plotChunkHolder(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return Optional.ofNullable(plotChunkHolders.get(chunkPos));
    }

    public synchronized List<PlotChunkHolder> plotChunkHolders() {
        return List.copyOf(plotChunkHolders.values());
    }

    public boolean isRebuildingPlotChunks() {
        return rebuildingPlotChunks.get();
    }

    public void rebuildPlotChunks(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        Map<ChunkPos, PlotChunkHolder> rebuilt = buildPlotChunkHolders(subLevel);
        List<LevelChunk> rebuiltChunks = rebuilt.values().stream()
                .map(PlotChunkHolder::chunk)
                .toList();
        installRebuiltPlotChunks(subLevel, rebuilt);
        trackingSystem.onSubLevelChunksRebuilt(subLevel, rebuiltChunks);
    }

    private synchronized void installRebuiltPlotChunks(PhysicsSubLevel subLevel, Map<ChunkPos, PlotChunkHolder> rebuilt) {
        PhysicsSubLevel current = subLevels.get(subLevel.id());
        if (current != subLevel) {
            throw new IllegalArgumentException("Sublevel is not owned by this container: " + subLevel.id());
        }

        removePlotChunks(subLevel);
        for (Map.Entry<ChunkPos, PlotChunkHolder> entry : rebuilt.entrySet()) {
            ChunkPos chunkPos = entry.getKey();
            SubLevelId occupant = subLevelsByChunk.get(chunkPos);
            if (occupant != null && !occupant.equals(subLevel.id())) {
                throw new IllegalStateException("Plot chunk " + chunkPos + " is already owned by " + occupant);
            }
            subLevelsByChunk.put(chunkPos, subLevel.id());
            plotChunkHolders.put(chunkPos, entry.getValue());
        }
    }

    private Map<ChunkPos, PlotChunkHolder> buildPlotChunkHolders(PhysicsSubLevel subLevel) {
        Map<ChunkPos, LevelChunk> chunks = new LinkedHashMap<>();
        for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
            chunks.put(chunkPos, newPlotChunk(chunkPos));
        }

        for (SubLevelBlock block : subLevel.blocks()) {
            BlockPos plotPos = subLevel.plot().toPlotBlockPos(block.localPos());
            ChunkPos chunkPos = new ChunkPos(plotPos);
            if (!subLevel.plot().containsChunk(chunkPos)) {
                throw new IllegalStateException("Block " + plotPos + " is outside plot " + subLevel.plot().describe());
            }
            LevelChunk chunk = chunks.computeIfAbsent(chunkPos, this::newPlotChunk);
            rebuildingPlotChunks.set(true);
            try {
                chunk.setBlockState(plotPos, block.blockState(), false);
                addBlockEntity(chunk, subLevel, block, plotPos);
            } finally {
                rebuildingPlotChunks.set(false);
            }
        }

        Map<ChunkPos, PlotChunkHolder> holders = new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, LevelChunk> entry : chunks.entrySet()) {
            holders.put(entry.getKey(), PlotChunkHolder.create(level, entry.getValue()));
        }
        return holders;
    }

    private LevelChunk newPlotChunk(ChunkPos chunkPos) {
        LevelChunk chunk = new LevelChunk(level, chunkPos);
        chunk.setLoaded(true);
        chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        return chunk;
    }

    private List<ChunkPos> removePlotChunks(PhysicsSubLevel subLevel) {
        List<ChunkPos> removedChunks = new ArrayList<>();
        for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
            SubLevelId occupant = subLevelsByChunk.get(chunkPos);
            if (subLevel.id().equals(occupant)) {
                subLevelsByChunk.remove(chunkPos);
                if (plotChunkHolders.remove(chunkPos) != null) {
                    removedChunks.add(chunkPos);
                }
            }
        }
        return List.copyOf(removedChunks);
    }

    private void addBlockEntity(LevelChunk chunk, PhysicsSubLevel subLevel, SubLevelBlock block, BlockPos plotPos) {
        Optional<BlockEntity> cached = subLevel.blockEntity(block.localPos()).filter(blockEntity -> !blockEntity.isRemoved());
        if (cached.isPresent()) {
            chunk.setBlockEntity(cached.get());
            return;
        }

        BlockEntity blockEntity = createBlockEntity(block, plotPos);
        if (blockEntity != null) {
            subLevel.putBlockEntity(block.localPos(), blockEntity);
            chunk.setBlockEntity(blockEntity);
        }
    }

    private BlockEntity createBlockEntity(SubLevelBlock block, BlockPos plotPos) {
        BlockEntity blockEntity = null;
        CompoundTag tag = block.blockEntityTag();
        if (tag != null) {
            CompoundTag plotTag = tag.copy();
            plotTag.putInt("x", plotPos.getX());
            plotTag.putInt("y", plotPos.getY());
            plotTag.putInt("z", plotPos.getZ());
            blockEntity = BlockEntity.loadStatic(plotPos, block.blockState(), plotTag, level.registryAccess());
        }
        if (blockEntity == null && block.blockState().getBlock() instanceof EntityBlock entityBlock) {
            blockEntity = entityBlock.newBlockEntity(plotPos, block.blockState());
        }
        if (blockEntity != null) {
            blockEntity.setLevel(level);
            blockEntity.clearRemoved();
        }
        return blockEntity;
    }

    private record RemovedSubLevel(PhysicsSubLevel subLevel, List<ChunkPos> removedChunks) {
        private RemovedSubLevel {
            Objects.requireNonNull(subLevel, "subLevel");
            removedChunks = List.copyOf(removedChunks);
        }
    }
}
