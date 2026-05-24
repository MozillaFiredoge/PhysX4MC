package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;

public final class ServerSubLevelContainer implements SubLevelContainer {
    private final ServerLevel level;
    private final Map<SubLevelId, PhysicsSubLevel> subLevels = new LinkedHashMap<>();
    private final Map<ChunkPos, SubLevelId> subLevelsByChunk = new LinkedHashMap<>();
    private final Map<ChunkPos, PlotChunkHolder> plotChunkHolders = new LinkedHashMap<>();
    private final Map<SubLevelId, Integer> pendingBlockUpdatePrimes = new LinkedHashMap<>();
    private final SubLevelPlotAllocator plotAllocator = new SubLevelPlotAllocator();
    private final SubLevelTrackingSystem trackingSystem = new SubLevelTrackingSystem(this);
    private final ThreadLocal<Boolean> rebuildingPlotChunks = ThreadLocal.withInitial(() -> false);
    private static final int DEFAULT_BLOCK_UPDATE_PRIME_TICKS = 8;

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
                pendingBlockUpdatePrimes.remove(id);
                removedChunks = removePlotChunks(removed, true);
            }
        }
        if (removed != null) {
            trackingSystem.onSubLevelRemoved(removed, removedChunks);
        }
        return Optional.ofNullable(removed);
    }

    public void clear() {
        List<RemovedSubLevel> removed;
        List<PlotChunkHolder> removedHolders;
        synchronized (this) {
            removedHolders = List.copyOf(plotChunkHolders.values());
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
            pendingBlockUpdatePrimes.clear();
        }
        for (RemovedSubLevel entry : removed) {
            clearPlotScheduledTicks(entry.subLevel());
        }
        for (PlotChunkHolder holder : removedHolders) {
            unregisterPlotChunk(holder);
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

    public void moveSourceScheduledTicksToPlot(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        requireOwned(subLevel);

        SubLevelBounds bounds = subLevel.bounds();
        BoundingBox sourceArea = BoundingBox.fromCorners(bounds.minSourcePos(), bounds.maxSourcePos());
        BlockPos plotMin = subLevel.plot().toPlotBlockPos(bounds.toLocal(bounds.minSourcePos()));
        Vec3i offset = new Vec3i(
                plotMin.getX() - bounds.minSourcePos().getX(),
                plotMin.getY() - bounds.minSourcePos().getY(),
                plotMin.getZ() - bounds.minSourcePos().getZ()
        );

        level.getBlockTicks().copyArea(sourceArea, offset);
        level.getBlockTicks().clearArea(sourceArea);
        level.getFluidTicks().copyArea(sourceArea, offset);
        level.getFluidTicks().clearArea(sourceArea);
    }

    public void movePlotScheduledTicksToSource(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        requireOwned(subLevel);

        SubLevelBounds bounds = subLevel.bounds();
        BlockPos plotMin = subLevel.plot().toPlotBlockPos(bounds.toLocal(bounds.minSourcePos()));
        BlockPos plotMax = subLevel.plot().toPlotBlockPos(bounds.toLocal(bounds.maxSourcePos()));
        BoundingBox plotArea = BoundingBox.fromCorners(plotMin, plotMax);
        Vec3i offset = new Vec3i(
                bounds.minSourcePos().getX() - plotMin.getX(),
                bounds.minSourcePos().getY() - plotMin.getY(),
                bounds.minSourcePos().getZ() - plotMin.getZ()
        );

        level.getBlockTicks().copyArea(plotArea, offset);
        level.getBlockTicks().clearArea(plotArea);
        level.getFluidTicks().copyArea(plotArea, offset);
        level.getFluidTicks().clearArea(plotArea);
    }

    public void movePlotScheduledTicksToChild(PhysicsSubLevel source, PhysicsSubLevel child) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(child, "child");
        requireOwned(source);
        requireOwned(child);

        for (SubLevelBlock block : child.blocks()) {
            BlockPos oldPlotPos = source.plot().toPlotBlockPos(source.bounds().toLocal(block.sourcePos()));
            BlockPos newPlotPos = child.plot().toPlotBlockPos(block.localPos());
            moveScheduledTickAt(oldPlotPos, newPlotPos);
        }
    }

    public void primePlotBlockUpdates(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        requireOwned(subLevel);

        for (SubLevelBlock block : List.copyOf(subLevel.blocks())) {
            BlockPos plotPos = subLevel.plot().toPlotBlockPos(block.localPos());
            BlockState live = level.getBlockState(plotPos);
            Block currentBlock = level.getBlockState(plotPos).getBlock();
            if (currentBlock != block.blockState().getBlock()) {
                continue;
            }
            level.neighborChanged(plotPos, currentBlock, plotPos);
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = plotPos.relative(direction);
                level.neighborChanged(plotPos, level.getBlockState(neighborPos).getBlock(), neighborPos);
            }
            level.updateNeighborsAt(plotPos, currentBlock);
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(plotPos.relative(direction), currentBlock);
            }
            if (currentBlock == Blocks.REPEATER || currentBlock == Blocks.COMPARATOR) {
                BlockState state = block.blockState();
                BlockPos pos = subLevel.plot().toPlotBlockPos(block.localPos());
                Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                BlockPos outputPos = pos.relative(facing.getOpposite());
                level.neighborChanged(outputPos, state.getBlock(), pos);
                level.updateNeighborsAtExceptFromFacing(outputPos, state.getBlock(), facing);
                level.scheduleTick(plotPos, currentBlock, 1);
                level.getBlockState(plotPos).tick(level, plotPos, level.random);
            }
        }
    }

    public synchronized void requestPlotBlockUpdatePrime(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        requireOwned(subLevel);
        pendingBlockUpdatePrimes.merge(
                subLevel.id(),
                DEFAULT_BLOCK_UPDATE_PRIME_TICKS,
                Math::max
        );
    }

    public void tickPlotBlockUpdatePrimes() {
        List<PhysicsSubLevel> toPrime = new ArrayList<>();
        synchronized (this) {
            pendingBlockUpdatePrimes.entrySet().removeIf(entry -> {
                PhysicsSubLevel subLevel = subLevels.get(entry.getKey());
                if (subLevel == null) {
                    return true;
                }
                toPrime.add(subLevel);
                int remaining = entry.getValue() - 1;
                if (remaining <= 0) {
                    return true;
                }
                entry.setValue(remaining);
                return false;
            });
        }
        for (PhysicsSubLevel subLevel : toPrime) {
            primePlotBlockUpdates(subLevel);
        }
    }

    private void moveScheduledTickAt(BlockPos from, BlockPos to) {
        BoundingBox area = new BoundingBox(from);
        Vec3i offset = new Vec3i(
                to.getX() - from.getX(),
                to.getY() - from.getY(),
                to.getZ() - from.getZ()
        );
        level.getBlockTicks().copyArea(area, offset);
        level.getBlockTicks().clearArea(area);
        level.getFluidTicks().copyArea(area, offset);
        level.getFluidTicks().clearArea(area);
    }

    private void requireOwned(PhysicsSubLevel subLevel) {
        PhysicsSubLevel current;
        synchronized (this) {
            current = subLevels.get(subLevel.id());
        }
        if (current != subLevel) {
            throw new IllegalArgumentException("Sublevel is not owned by this container: " + subLevel.id());
        }
    }

    private synchronized void installRebuiltPlotChunks(PhysicsSubLevel subLevel, Map<ChunkPos, PlotChunkHolder> rebuilt) {
        PhysicsSubLevel current = subLevels.get(subLevel.id());
        if (current != subLevel) {
            throw new IllegalArgumentException("Sublevel is not owned by this container: " + subLevel.id());
        }

        removePlotChunks(subLevel, false);
        for (Map.Entry<ChunkPos, PlotChunkHolder> entry : rebuilt.entrySet()) {
            ChunkPos chunkPos = entry.getKey();
            SubLevelId occupant = subLevelsByChunk.get(chunkPos);
            if (occupant != null && !occupant.equals(subLevel.id())) {
                throw new IllegalStateException("Plot chunk " + chunkPos + " is already owned by " + occupant);
            }
            subLevelsByChunk.put(chunkPos, subLevel.id());
            PlotChunkHolder holder = entry.getValue();
            plotChunkHolders.put(chunkPos, holder);
            registerPlotChunk(holder);
        }
    }

    private Map<ChunkPos, PlotChunkHolder> buildPlotChunkHolders(PhysicsSubLevel subLevel) {
        Map<ChunkPos, PreservedTicks> preservedTicks = preservedPlotTicks(subLevel);
        Map<ChunkPos, LevelChunk> chunks = new LinkedHashMap<>();
        for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
            chunks.put(chunkPos, newPlotChunk(chunkPos, preservedTicks.get(chunkPos)));
        }
        for (SubLevelBlock block : subLevel.blocks()) {
            BlockPos plotPos = subLevel.plot().toPlotBlockPos(block.localPos());
            ChunkPos chunkPos = new ChunkPos(plotPos);
            if (!subLevel.plot().containsChunk(chunkPos)) {
                throw new IllegalStateException("Block " + plotPos + " is outside plot " + subLevel.plot().describe());
            }
            LevelChunk chunk = chunks.computeIfAbsent(chunkPos, pos -> newPlotChunk(pos, preservedTicks.get(pos)));
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

    private LevelChunk newPlotChunk(ChunkPos chunkPos, PreservedTicks preservedTicks) {
        LevelChunk chunk = preservedTicks == null
                ? new LevelChunk(level, chunkPos)
                : new LevelChunk(
                        level,
                        chunkPos,
                        UpgradeData.EMPTY,
                        preservedTicks.blockTicks(),
                        preservedTicks.fluidTicks(),
                        0L,
                        null,
                        null,
                        null
                );
        chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        return chunk;
    }

    private List<ChunkPos> removePlotChunks(PhysicsSubLevel subLevel, boolean clearScheduledTicks) {
        if (clearScheduledTicks) {
            clearPlotScheduledTicks(subLevel);
        }
        List<ChunkPos> removedChunks = new ArrayList<>();
        for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
            SubLevelId occupant = subLevelsByChunk.get(chunkPos);
            if (subLevel.id().equals(occupant)) {
                subLevelsByChunk.remove(chunkPos);
                PlotChunkHolder holder = plotChunkHolders.remove(chunkPos);
                if (holder != null) {
                    unregisterPlotChunk(holder);
                    removedChunks.add(chunkPos);
                }
            }
        }
        return List.copyOf(removedChunks);
    }

    private synchronized Map<ChunkPos, PreservedTicks> preservedPlotTicks(PhysicsSubLevel subLevel) {
        Map<ChunkPos, PreservedTicks> preserved = new LinkedHashMap<>();
        for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
            PlotChunkHolder holder = plotChunkHolders.get(chunkPos);
            if (holder != null && subLevel.id().equals(subLevelsByChunk.get(chunkPos))) {
                preserved.put(chunkPos, copyTicks(holder.chunk()));
            }
        }
        return Map.copyOf(preserved);
    }

    @SuppressWarnings("unchecked")
    private static PreservedTicks copyTicks(LevelChunk chunk) {
        return new PreservedTicks(
                copyTicks((LevelChunkTicks<Block>) chunk.getTicksForSerialization().blocks()),
                copyTicks((LevelChunkTicks<Fluid>) chunk.getTicksForSerialization().fluids())
        );
    }

    private static <T> LevelChunkTicks<T> copyTicks(LevelChunkTicks<T> source) {
        LevelChunkTicks<T> copy = new LevelChunkTicks<>();
        source.getAll().forEach(copy::schedule);
        return copy;
    }

    private void registerPlotChunk(PlotChunkHolder holder) {
        LevelChunk chunk = holder.chunk();
        ChunkPos pos = chunk.getPos();
        ServerChunkCache chunkSource = level.getChunkSource();
        chunkSource.chunkMap.updatingChunkMap.put(pos.toLong(), holder);
        chunkSource.chunkMap.modified = true;

        chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        chunk.runPostLoad();
        chunk.setLoaded(true);
        chunk.getBlockEntities().values().forEach(BlockEntity::clearRemoved);
        chunk.registerAllBlockEntitiesAfterLevelLoad();
        chunk.registerTickContainerInLevel(level);
        level.startTickingChunk(chunk);

        level.entityManager.updateChunkStatus(pos, FullChunkStatus.ENTITY_TICKING);
        chunkSource.chunkMap.onFullChunkStatusChange(pos, FullChunkStatus.ENTITY_TICKING);
    }

    private void unregisterPlotChunk(PlotChunkHolder holder) {
        LevelChunk chunk = holder.chunk();
        ChunkPos pos = chunk.getPos();
        ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource.chunkMap.updatingChunkMap.get(pos.toLong()) == holder) {
            chunkSource.chunkMap.updatingChunkMap.remove(pos.toLong());
            chunkSource.chunkMap.modified = true;
        }

        chunk.setLoaded(false);
        level.unload(chunk);
        level.entityManager.updateChunkStatus(pos, FullChunkStatus.INACCESSIBLE);
    }

    private void clearPlotScheduledTicks(PhysicsSubLevel subLevel) {
        BoundingBox plotArea = new BoundingBox(
                subLevel.plot().minPlotX(),
                subLevel.plot().minPlotY(),
                subLevel.plot().minPlotZ(),
                subLevel.plot().maxPlotX(),
                subLevel.plot().maxPlotY(),
                subLevel.plot().maxPlotZ()
        );
        level.getBlockTicks().clearArea(plotArea);
        level.getFluidTicks().clearArea(plotArea);
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

    private record PreservedTicks(LevelChunkTicks<Block> blockTicks, LevelChunkTicks<Fluid> fluidTicks) {
        private PreservedTicks {
            Objects.requireNonNull(blockTicks, "blockTicks");
            Objects.requireNonNull(fluidTicks, "fluidTicks");
        }
    }
}
