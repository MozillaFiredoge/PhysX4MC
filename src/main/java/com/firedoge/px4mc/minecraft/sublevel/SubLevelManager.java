package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodyId;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.firedoge.px4mc.mechanics.MechanicsWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SubLevelManager {
    public static final SubLevelManager INSTANCE = new SubLevelManager();
    private static final int BLOCK_UPDATE_FLAGS = 3;

    private long vanillaBreakActions;
    private long vanillaUseActions;
    private long vanillaBreakAccepted;
    private long vanillaUseAccepted;
    private long vanillaBreakRejected;
    private long vanillaUseRejected;
    private long plotBlockWrites;
    private long splitEvents;
    private long splitCreatedSubLevels;
    private final Map<RemovedPlotProjectionKey, RemovedPlotProjection> removedPlotProjections = new LinkedHashMap<>();

    private SubLevelManager() {
    }

    public SubLevelSnapshot assembleBlock(ServerLevel level, BlockPos pos, float mass, boolean debugProxy) {
        return assembleBox(level, pos, pos, mass, debugProxy);
    }

    public SubLevelSnapshot assembleBox(ServerLevel level, BlockPos first, BlockPos second, float mass, boolean debugProxy) {
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        SubLevelAssembler.Result result = SubLevelAssembler.assembleBox(level, first, second, mass, container);
        PhysicsSubLevel subLevel = result.subLevel();
        container.add(subLevel);
        container.moveSourceScheduledTicksToPlot(subLevel);
        container.primePlotBlockUpdates(subLevel);
        container.requestPlotBlockUpdatePrime(subLevel);
        subLevel.activate();
        try {
            if (debugProxy) {
                createVisuals(level, result.body(), subLevel);
            }
            return snapshot(result.body(), subLevel);
        } catch (RuntimeException exception) {
            discardVisuals(level, result.body(), subLevel);
            subLevel.markRemoving();
            container.remove(subLevel.id());
            PhysX4mc.api().existingWorld(level).ifPresent(world -> world.removeBody(subLevel.bodyId()));
            throw exception;
        }
    }

    public void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        removedPlotProjections.clear();

        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainers.server(level).orElse(null);
            if (container == null || container.isEmpty()) {
                continue;
            }
            for (PhysicsSubLevel subLevel : container.subLevels()) {
                Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                        .flatMap(world -> world.snapshot(subLevel.bodyId()));
                if (maybeBody.isEmpty()) {
                    discardVisuals(level, subLevel);
                    subLevel.markRemoving();
                    subLevel.clearBlockEntities();
                    container.remove(subLevel.id());
                    continue;
                }
                syncVisuals(level, maybeBody.get(), subLevel);
            }
        }
    }

    public List<SubLevelSnapshot> snapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            removeStaleLevelEntries(level);
            return List.of();
        }

        MechanicsWorld world = maybeWorld.get();
        List<SubLevelSnapshot> snapshots = new ArrayList<>();
        for (PhysicsSubLevel subLevel : container.subLevels()) {
            Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(subLevel.bodyId());
            if (maybeBody.isEmpty()) {
                discardVisuals(level, subLevel);
                subLevel.clearBlockEntities();
                container.remove(subLevel.id());
                continue;
            }
            snapshots.add(snapshot(maybeBody.get(), subLevel));
        }
        return List.copyOf(snapshots);
    }

    public Optional<SubLevelSnapshot> snapshot(ServerLevel level, SubLevelId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        Optional<PhysicsSubLevel> maybeSubLevel = container.subLevel(id);
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }
        PhysicsSubLevel subLevel = maybeSubLevel.get();

        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            discardVisuals(level, subLevel);
            subLevel.clearBlockEntities();
            container.remove(id);
            return Optional.empty();
        }
        return Optional.of(snapshot(maybeBody.get(), subLevel));
    }

    public Optional<SubLevelBlock> blockAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return subLevelAtPlotBlock(level, plotPos)
                .flatMap(subLevel -> subLevel.section().block(subLevel.plot().toSectionLocalPos(plotPos)));
    }

    public Optional<BlockState> blockStateAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return subLevelAtPlotBlock(level, plotPos)
                .map(subLevel -> subLevel.section()
                        .block(subLevel.plot().toSectionLocalPos(plotPos))
                        .map(SubLevelBlock::blockState)
                        .orElseGet(() -> Blocks.AIR.defaultBlockState()));
    }

    public Optional<BlockEntity> blockEntityAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
        Optional<SubLevelBlock> maybeBlock = subLevel.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        Optional<BlockEntity> cached = subLevel.blockEntity(localPos);
        if (cached.isPresent() && !cached.get().isRemoved()) {
            return cached;
        }

        BlockEntity blockEntity = createBlockEntity(level, plotPos, maybeBlock.get());
        if (blockEntity == null) {
            return Optional.empty();
        }
        subLevel.putBlockEntity(localPos, blockEntity);
        SubLevelContainers.requireServer(level).rebuildPlotChunks(subLevel);
        return Optional.of(blockEntity);
    }

    public boolean setPlotBlockEntity(ServerLevel level, BlockEntity blockEntity) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(blockEntity, "blockEntity");
        BlockPos plotPos = blockEntity.getBlockPos();
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return false;
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
        if (subLevel.section().block(localPos).isEmpty()) {
            return false;
        }

        blockEntity.setLevel(level);
        blockEntity.clearRemoved();
        subLevel.putBlockEntity(localPos, blockEntity);
        SubLevelContainers.requireServer(level).rebuildPlotChunks(subLevel);
        return true;
    }

    public boolean removePlotBlockEntity(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return false;
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
        if (subLevel.section().block(localPos).isEmpty()) {
            return false;
        }
        subLevel.removeBlockEntity(localPos);
        SubLevelContainers.requireServer(level).rebuildPlotChunks(subLevel);
        return true;
    }

    public Optional<SubLevelPlotTarget> plotTargetAtBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return subLevelAtPlotBlock(level, plotPos).flatMap(subLevel -> {
            BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
            return subLevel.section()
                    .block(localPos)
                    .map(block -> new SubLevelPlotTarget(subLevel.id(), localPos, block.blockState()));
        });
    }

    public boolean setPlotBlockState(ServerLevel level, BlockPos plotPos, BlockState blockState) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(blockState, "blockState");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return false;
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
        Optional<SubLevelBlock> maybePreviousBlock = subLevel.section().block(localPos);
        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            return false;
        }

        if (maybePreviousBlock.isEmpty()) {
            if (blockState.isAir()) {
                return true;
            }
            if (!hasAdjacentBlock(subLevel, localPos)) {
                return false;
            }
            List<AABB> localCollisionBoxes = SubLevelAssembler.physicalCollisionBoxes(blockState, level, plotPos);
            if (localCollisionBoxes.isEmpty() && !SubLevelAssembler.hasPhysicalCollision(subLevel.blocks())) {
                return false;
            }
            SubLevelBlock addedBlock = new SubLevelBlock(
                    subLevel.section().toSourcePos(localPos),
                    localPos.immutable(),
                    blockState,
                    SubLevelAssembler.localGeometryBounds(blockState, level, plotPos),
                    localCollisionBoxes,
                    visualLocalOriginFor(subLevel, localPos)
            );
            subLevel.section().putBlock(addedBlock);
            subLevel.refreshBoundsFromBlocks();
            subLevel.markDirty();
            rebuildSubLevelBody(level, subLevel, maybeBody.get());
            plotBlockWrites++;
            return true;
        }

        SubLevelBlock previousBlock = maybePreviousBlock.get();
        if (previousBlock.blockState().equals(blockState)) {
            return true;
        }

        if (blockState.isAir()) {
            recordRemovedPlotProjection(level, subLevel, maybeBody.get(), plotPos);
            RemovedBlocks removedBlocks = removeBlockAndDependents(level, subLevel, maybeBody.get(), localPos, true);
            if (removedBlocks.isEmpty()) {
                return false;
            }
            if (subLevel.section().isEmpty()) {
                subLevel.markRemoving();
                discardVisuals(level, maybeBody.get(), subLevel);
                subLevel.clearBlockEntities();
                PhysX4mc.api().existingWorld(level).ifPresent(world -> world.removeBody(subLevel.bodyId()));
                SubLevelContainers.requireServer(level).remove(subLevel.id());
            } else {
                rebuildAfterBlockRemoval(level, SubLevelContainers.requireServer(level), subLevel, maybeBody.get());
            }
            plotBlockWrites++;
            return true;
        }

        List<AABB> localCollisionBoxes = SubLevelAssembler.physicalCollisionBoxes(blockState, level, plotPos);
        if (localCollisionBoxes.isEmpty() && !hasOtherPhysicalCollision(subLevel, localPos)) {
            return false;
        }
        AABB localCollisionBounds = SubLevelAssembler.localGeometryBounds(blockState, level, plotPos);
        if (previousBlock.blockState().getBlock() != blockState.getBlock()) {
            subLevel.removeBlockEntity(localPos);
        }
        SubLevelBlock updatedBlock = new SubLevelBlock(
                previousBlock.sourcePos(),
                localPos.immutable(),
                blockState,
                localCollisionBounds,
                localCollisionBoxes,
                previousBlock.visualLocalOrigin(),
                previousBlock.blockState().getBlock() == blockState.getBlock() ? previousBlock.blockEntityTag() : null
        );
        subLevel.section().putBlock(updatedBlock);
        subLevel.refreshBoundsFromBlocks();
        subLevel.markDirty();
        rebuildSubLevelBody(level, subLevel, maybeBody.get());
        plotBlockWrites++;
        return true;
    }

    private static boolean hasOtherPhysicalCollision(PhysicsSubLevel subLevel, BlockPos localPos) {
        for (SubLevelBlock block : subLevel.blocks()) {
            if (!block.localPos().equals(localPos) && block.hasPhysicalCollision()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAdjacentBlock(PhysicsSubLevel subLevel, BlockPos localPos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = localPos.relative(direction);
            if (SubLevelSectionStorage.isValidLocal(neighbor) && subLevel.section().hasBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private RemovedBlocks removeBlockAndDependents(
            ServerLevel level,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot body,
            BlockPos primaryLocalPos,
            boolean dropDependents
    ) {
        Map<BlockPos, SubLevelBlock> removed = new LinkedHashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        removeSubLevelBlock(level, subLevel, body, primaryLocalPos, false)
                .ifPresent(block -> {
                    removed.put(primaryLocalPos.immutable(), block);
                    queue.add(primaryLocalPos.immutable());
                });
        if (removed.isEmpty()) {
            return RemovedBlocks.EMPTY;
        }

        while (!queue.isEmpty()) {
            BlockPos removedLocalPos = queue.removeFirst();
            for (BlockPos candidateLocalPos : dependentCandidatePositions(removedLocalPos)) {
                if (removed.containsKey(candidateLocalPos) || !SubLevelSectionStorage.isValidLocal(candidateLocalPos)) {
                    continue;
                }
                Optional<SubLevelBlock> maybeCandidate = subLevel.section().block(candidateLocalPos);
                if (maybeCandidate.isEmpty()) {
                    continue;
                }
                SubLevelBlock candidate = maybeCandidate.get();
                if (!shouldRemoveDependentBlock(subLevel, candidate, removedLocalPos)) {
                    continue;
                }
                removeSubLevelBlock(level, subLevel, body, candidateLocalPos, dropDependents && shouldDropDependentBlock(candidate))
                        .ifPresent(block -> {
                            removed.put(candidateLocalPos.immutable(), block);
                            queue.add(candidateLocalPos.immutable());
                        });
            }
        }

        return new RemovedBlocks(removed);
    }

    private Optional<SubLevelBlock> removeSubLevelBlock(
            ServerLevel level,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot body,
            BlockPos localPos,
            boolean dropResources
    ) {
        Optional<SubLevelBlock> maybeBlock = subLevel.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        BlockPos plotPos = subLevel.plot().toPlotBlockPos(localPos);
        recordRemovedPlotProjection(level, subLevel, body, plotPos);
        SubLevelBlock block = maybeBlock.get();
        if (dropResources) {
            dropDependentResources(level, subLevel, localPos, block, plotPos);
        }
        subLevel.removeBlockEntity(localPos);
        return subLevel.section().removeBlock(localPos);
    }

    private void dropDependentResources(
            ServerLevel level,
            PhysicsSubLevel subLevel,
            BlockPos localPos,
            SubLevelBlock block,
            BlockPos plotPos
    ) {
        BlockEntity blockEntity = subLevel.blockEntity(localPos).filter(entity -> !entity.isRemoved()).orElse(null);
        Block.dropResources(block.blockState(), level, plotPos, blockEntity, null, ItemStack.EMPTY);
    }

    private static List<BlockPos> dependentCandidatePositions(BlockPos localPos) {
        List<BlockPos> candidates = new ArrayList<>(8);
        for (Direction direction : Direction.values()) {
            candidates.add(localPos.relative(direction));
        }
        return List.copyOf(candidates);
    }

    private static boolean shouldRemoveDependentBlock(PhysicsSubLevel subLevel, SubLevelBlock candidate, BlockPos removedLocalPos) {
        BlockState state = candidate.blockState();
        BlockPos localPos = candidate.localPos();
        BlockPos pair = pairedLocalPos(state, localPos);
        if (pair != null && pair.equals(removedLocalPos)) {
            return true;
        }

        Direction supportDirection = supportDirection(state);
        if (supportDirection != null && localPos.relative(supportDirection).equals(removedLocalPos)) {
            return true;
        }

        if (localPos.below().equals(removedLocalPos) && isFragileAttachment(subLevel, candidate)) {
            return true;
        }
        return false;
    }

    private static boolean shouldDropDependentBlock(SubLevelBlock block) {
        return pairedLocalPos(block.blockState(), block.localPos()) == null;
    }

    private static BlockPos pairedLocalPos(BlockState state, BlockPos localPos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            return localPos.relative(half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return localPos.relative(part == BedPart.FOOT ? facing : facing.getOpposite());
        }
        if ((state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON))
                && state.hasProperty(BlockStateProperties.EXTENDED)
                && state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.EXTENDED)) {
            return localPos.relative(state.getValue(BlockStateProperties.FACING));
        }
        if ((state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON))
                && state.hasProperty(BlockStateProperties.FACING)) {
            return localPos.relative(state.getValue(BlockStateProperties.FACING).getOpposite());
        }
        return null;
    }

    private static Direction supportDirection(BlockState state) {
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            AttachFace attachFace = state.getValue(BlockStateProperties.ATTACH_FACE);
            return switch (attachFace) {
                case FLOOR -> Direction.DOWN;
                case CEILING -> Direction.UP;
                case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            };
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        return null;
    }

    private static boolean isFragileAttachment(PhysicsSubLevel subLevel, SubLevelBlock block) {
        BlockPos plotPos = subLevel.plot().toPlotBlockPos(block.localPos());
        return block.blockState().getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, plotPos).isEmpty();
    }

    public void recordVanillaBreakAction() {
        vanillaBreakActions++;
    }

    public void recordVanillaUseAction() {
        vanillaUseActions++;
    }

    public void recordVanillaBreakAccepted() {
        vanillaBreakAccepted++;
    }

    public void recordVanillaUseAccepted() {
        vanillaUseAccepted++;
    }

    public void recordVanillaBreakRejected() {
        vanillaBreakRejected++;
    }

    public void recordVanillaUseRejected() {
        vanillaUseRejected++;
    }

    public BridgeStats bridgeStats() {
        return new BridgeStats(
                vanillaBreakActions,
                vanillaUseActions,
                vanillaBreakAccepted,
                vanillaUseAccepted,
                vanillaBreakRejected,
                vanillaUseRejected,
                plotBlockWrites,
                splitEvents,
                splitCreatedSubLevels
        );
    }

    public Optional<PhysicsVector> plotBlockWorldCenter(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
        Optional<SubLevelBlock> maybeBlock = subLevel.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            return Optional.empty();
        }

        AABB bounds = maybeBlock.get().localCollisionBounds();
        PhysicsVector localCenter = new PhysicsVector(
                maybeBlock.get().visualLocalOrigin().x() + (bounds.minX + bounds.maxX) * 0.5D,
                maybeBlock.get().visualLocalOrigin().y() + (bounds.minY + bounds.maxY) * 0.5D,
                maybeBlock.get().visualLocalOrigin().z() + (bounds.minZ + bounds.maxZ) * 0.5D
        );
        return Optional.of(SubLevelTransform.from(maybeBody.get()).localToWorld(localCenter));
    }

    public Optional<PhysicsVector> plotPositionToWorld(ServerLevel level, Vec3 plotPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPosition, "plotPosition");
        BlockPos plotPos = BlockPos.containing(plotPosition);
        return plotProjection(level, plotPos)
                .map(projection -> projection.toWorld(plotPosition));
    }

    public Optional<PhysicsVector> plotDirectionToWorld(ServerLevel level, BlockPos plotPos, Vec3 plotDirection) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(plotDirection, "plotDirection");
        return plotProjection(level, plotPos)
                .map(projection -> projection.directionToWorld(plotDirection));
    }

    public Optional<PlotProjection> plotProjection(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return removedPlotProjection(level, plotPos);
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ActivePlotProjection(subLevel, SubLevelTransform.from(maybeBody.get())));
    }

    public boolean playerCanReachPlotBlock(ServerLevel level, ServerPlayer player, BlockPos plotPos) {
        Objects.requireNonNull(player, "player");
        Optional<PhysicsVector> maybeCenter = plotBlockWorldCenter(level, plotPos);
        if (maybeCenter.isEmpty()) {
            return false;
        }

        Vec3 eye = player.getEyePosition();
        PhysicsVector center = maybeCenter.get();
        double dx = center.x() - eye.x();
        double dy = center.y() - eye.y();
        double dz = center.z() - eye.z();
        double maxDistance = Math.max(16.0D, player.blockInteractionRange()) + 2.0D;
        return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
    }

    public boolean playerCanReachPlotBlock(ServerLevel level, ServerPlayer player, BlockPos plotPos, double distanceBuffer) {
        Objects.requireNonNull(player, "player");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return false;
        }

        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(maybeSubLevel.get().bodyId()));
        if (maybeBody.isEmpty()) {
            return false;
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        PhysicsVector localEye = SubLevelTransform.from(maybeBody.get()).worldToLocal(new PhysicsVector(
                player.getEyePosition().x(),
                player.getEyePosition().y(),
                player.getEyePosition().z()
        ));
        PhysicsVector bodyToPlotOrigin = bodyToPlotOrigin(subLevel);
        Vec3 plotEye = new Vec3(
                localEye.x() - bodyToPlotOrigin.x() + subLevel.plot().minPlotX(),
                localEye.y() - bodyToPlotOrigin.y() + subLevel.plot().minPlotY(),
                localEye.z() - bodyToPlotOrigin.z() + subLevel.plot().minPlotZ()
        );
        double maxDistance = Math.max(0.0D, player.blockInteractionRange() + distanceBuffer);
        return new AABB(plotPos).distanceToSqr(plotEye) < maxDistance * maxDistance;
    }

    public Optional<SubLevelPickResult> pickBlock(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldOrigin, "worldOrigin");
        Objects.requireNonNull(worldDirection, "worldDirection");
        if (maxDistance <= 0.0D || Double.isNaN(maxDistance)) {
            throw new IllegalArgumentException("maxDistance must be positive");
        }

        PhysicsVector direction = normalize(worldDirection);
        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            removeStaleLevelEntries(level);
            return Optional.empty();
        }

        MechanicsWorld world = maybeWorld.get();
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        SubLevelPickResult best = null;
        for (PhysicsSubLevel subLevel : container.subLevels()) {
            Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(subLevel.bodyId());
            if (maybeBody.isEmpty()) {
                discardVisuals(level, subLevel);
                container.remove(subLevel.id());
                continue;
            }
            SubLevelTransform transform = SubLevelTransform.from(maybeBody.get());
            PhysicsVector localOrigin = transform.worldToLocal(worldOrigin);
            PhysicsVector localDirection = transform.worldDirectionToLocal(direction);
            for (SubLevelBlock block : subLevel.blocks()) {
                Optional<Double> maybeDistance = intersectBlock(localOrigin, localDirection, block, maxDistance);
                if (maybeDistance.isEmpty()) {
                    continue;
                }
                double distance = maybeDistance.get();
                if (best != null && distance >= best.distance()) {
                    continue;
                }
                PhysicsVector localHit = add(localOrigin, scale(localDirection, distance));
                PhysicsVector worldHit = transform.localToWorld(localHit);
                best = new SubLevelPickResult(
                        subLevel.id(),
                        maybeBody.get(),
                        block,
                        block.localPos(),
                        block.blockState(),
                        worldHit,
                        localHit,
                        distance
                );
            }
        }
        return Optional.ofNullable(best);
    }

    public Optional<SubLevelBreakResult> breakPickedBlock(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance
    ) {
        return breakPickedBlockIfMatches(level, worldOrigin, worldDirection, maxDistance, null, null);
    }

    public Optional<SubLevelBreakResult> breakPickedBlockIfMatches(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance,
            SubLevelId expectedId,
            BlockPos expectedLocalPos
    ) {
        Objects.requireNonNull(level, "level");
        Optional<SubLevelPickResult> maybePick = pickBlock(level, worldOrigin, worldDirection, maxDistance);
        if (maybePick.isEmpty()) {
            return Optional.empty();
        }

        SubLevelPickResult pick = maybePick.get();
        if (expectedId != null && !expectedId.equals(pick.id())) {
            return Optional.empty();
        }
        if (expectedLocalPos != null && !expectedLocalPos.equals(pick.localPos())) {
            return Optional.empty();
        }

        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        Optional<PhysicsSubLevel> maybeSubLevel = container.subLevel(pick.id());
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }
        PhysicsSubLevel subLevel = maybeSubLevel.get();

        recordRemovedPlotProjection(level, subLevel, pick.body(), subLevel.plot().toPlotBlockPos(pick.localPos()));
        RemovedBlocks removedBlocks = removeBlockAndDependents(level, subLevel, pick.body(), pick.localPos(), true);
        if (removedBlocks.isEmpty()) {
            return Optional.empty();
        }

        int removedVisuals = countVisualsForBlocks(subLevel, removedBlocks.localPositions());
        boolean removedSubLevel = false;
        SplitResult splitResult = SplitResult.notSplit(subLevel.section().blockCount());
        if (subLevel.section().isEmpty()) {
            subLevel.markRemoving();
            discardVisuals(level, pick.body(), subLevel);
            subLevel.clearBlockEntities();
            PhysX4mc.api().existingWorld(level).ifPresent(world -> world.removeBody(subLevel.bodyId()));
            container.remove(subLevel.id());
            removedSubLevel = true;
        } else {
            splitResult = rebuildAfterBlockRemoval(level, container, subLevel, pick.body());
            removedSubLevel = splitResult.removedOriginal();
        }

        SubLevelBreakResult result = new SubLevelBreakResult(
                pick,
                removedSubLevel,
                subLevel.section().blockCount(),
                subLevel.section().dirtyBlockCount(),
                removedVisuals,
                splitResult.components(),
                splitResult.createdSubLevels()
        );
        return Optional.of(result);
    }

    public Optional<SubLevelBreakResult> breakPlotBlock(ServerLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        Optional<PhysicsSubLevel> maybeSubLevel = subLevelAtPlotBlock(level, plotPos);
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }

        PhysicsSubLevel subLevel = maybeSubLevel.get();
        BlockPos localPos = subLevel.plot().toSectionLocalPos(plotPos);
        Optional<SubLevelBlock> maybeBlock = subLevel.section().block(localPos);
        if (maybeBlock.isEmpty()) {
            return Optional.empty();
        }

        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            return Optional.empty();
        }

        SubLevelBlock block = maybeBlock.get();
        AABB bounds = block.localCollisionBounds();
        PhysicsVector localHit = new PhysicsVector(
                block.visualLocalOrigin().x() + (bounds.minX + bounds.maxX) * 0.5D,
                block.visualLocalOrigin().y() + (bounds.minY + bounds.maxY) * 0.5D,
                block.visualLocalOrigin().z() + (bounds.minZ + bounds.maxZ) * 0.5D
        );
        PhysicsVector worldHit = SubLevelTransform.from(maybeBody.get()).localToWorld(localHit);
        SubLevelPickResult pick = new SubLevelPickResult(
                subLevel.id(),
                maybeBody.get(),
                block,
                localPos,
                block.blockState(),
                worldHit,
                localHit,
                0.0D
        );
        recordRemovedPlotProjection(level, subLevel, maybeBody.get(), plotPos);
        RemovedBlocks removedBlocks = removeBlockAndDependents(level, subLevel, maybeBody.get(), localPos, true);
        if (removedBlocks.isEmpty()) {
            return Optional.empty();
        }

        int removedVisuals = countVisualsForBlocks(subLevel, removedBlocks.localPositions());
        boolean removedSubLevel = false;
        SplitResult splitResult = SplitResult.notSplit(subLevel.section().blockCount());
        if (subLevel.section().isEmpty()) {
            subLevel.markRemoving();
            discardVisuals(level, maybeBody.get(), subLevel);
            subLevel.clearBlockEntities();
            PhysX4mc.api().existingWorld(level).ifPresent(world -> world.removeBody(subLevel.bodyId()));
            SubLevelContainers.requireServer(level).remove(subLevel.id());
            removedSubLevel = true;
        } else {
            splitResult = rebuildAfterBlockRemoval(level, SubLevelContainers.requireServer(level), subLevel, maybeBody.get());
            removedSubLevel = splitResult.removedOriginal();
        }

        SubLevelBreakResult result = new SubLevelBreakResult(
                pick,
                removedSubLevel,
                subLevel.section().blockCount(),
                subLevel.section().dirtyBlockCount(),
                removedVisuals,
                splitResult.components(),
                splitResult.createdSubLevels()
        );
        return Optional.of(result);
    }

    public Optional<SubLevelSnapshot> restoreOriginal(ServerLevel level, SubLevelId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        Optional<PhysicsSubLevel> maybeSubLevel = container.subLevel(id);
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }
        PhysicsSubLevel subLevel = maybeSubLevel.get();

        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        Optional<MechanicsBodySnapshot> maybeBody = maybeWorld.flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            discardVisuals(level, subLevel);
            subLevel.markRemoving();
            subLevel.clearBlockEntities();
            container.remove(id);
            return Optional.empty();
        }

        for (SubLevelBlock block : subLevel.blocks()) {
            BlockState currentState = level.getBlockState(block.sourcePos());
            if (!currentState.isAir()) {
                throw new IllegalStateException("Cannot restore " + id + "; source position " + describePos(block.sourcePos()) + " is occupied");
            }
        }
        for (SubLevelBlock block : subLevel.blocks()) {
            if (!level.setBlock(block.sourcePos(), block.blockState(), BLOCK_UPDATE_FLAGS)) {
                throw new IllegalStateException("Failed to restore block at " + describePos(block.sourcePos()));
            }
        }
        container.movePlotScheduledTicksToSource(subLevel);
        SubLevelAssembler.refreshTerrainAround(level, subLevel.blocks());

        discardVisuals(level, maybeBody.get(), subLevel);
        subLevel.markRemoving();
        subLevel.clearBlockEntities();
        maybeWorld.ifPresent(world -> world.removeBody(subLevel.bodyId()));
        container.remove(id);
        SubLevelSnapshot snapshot = snapshot(maybeBody.get(), subLevel);
        return Optional.of(snapshot);
    }

    public Optional<SubLevelSnapshot> remove(ServerLevel level, SubLevelId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        Optional<PhysicsSubLevel> maybeSubLevel = container.subLevel(id);
        if (maybeSubLevel.isEmpty()) {
            return Optional.empty();
        }
        PhysicsSubLevel subLevel = maybeSubLevel.get();

        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        Optional<MechanicsBodySnapshot> maybeBody = maybeWorld.flatMap(world -> world.snapshot(subLevel.bodyId()));
        maybeBody.ifPresentOrElse(
                body -> discardVisuals(level, body, subLevel),
                () -> discardVisuals(level, subLevel)
        );
        subLevel.markRemoving();
        subLevel.clearBlockEntities();
        maybeWorld.ifPresent(world -> world.removeBody(subLevel.bodyId()));
        container.remove(id);
        Optional<SubLevelSnapshot> snapshot = maybeBody.map(body -> snapshot(body, subLevel));
        return snapshot;
    }

    public int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        int removed = 0;
        for (PhysicsSubLevel subLevel : container.subLevels()) {
            discardVisuals(level, subLevel);
            subLevel.markRemoving();
            subLevel.clearBlockEntities();
            container.remove(subLevel.id());
            removed++;
        }
        return removed;
    }

    public void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainers.server(level).orElse(null);
            if (container == null) {
                continue;
            }
            for (PhysicsSubLevel subLevel : container.subLevels()) {
                discardVisuals(level, subLevel);
                subLevel.markRemoving();
                subLevel.clearBlockEntities();
            }
            container.clear();
        }
    }

    private void createVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        subLevel.setDebugVisualsEnabled(true);
        SubLevelDebugVisuals.create(level, body, subLevel);
    }

    private void syncVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        SubLevelDebugVisuals.sync(level, body, subLevel);
    }

    private void discardVisuals(ServerLevel level, PhysicsSubLevel subLevel) {
        SubLevelDebugVisuals.discard(level, subLevel);
        subLevel.setDebugVisualsEnabled(false);
    }

    private void discardVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        SubLevelDebugVisuals.discard(level, body, subLevel);
        subLevel.setDebugVisualsEnabled(false);
    }

    private void refreshVisualsAfterBodyReplacement(
            ServerLevel level,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot previousBody,
            MechanicsBodySnapshot replacementBody
    ) {
        if (!subLevel.debugVisualsEnabled() && subLevel.visuals().isEmpty()) {
            return;
        }
        discardVisuals(level, previousBody, subLevel);
        discardVisuals(level, replacementBody, subLevel);
        createVisuals(level, replacementBody, subLevel);
    }

    private static int countVisualsForBlocks(PhysicsSubLevel subLevel, List<BlockPos> localPositions) {
        int count = 0;
        for (PhysicsSubLevel.VisualBinding visual : subLevel.visuals()) {
            if (localPositions.contains(visual.block().localPos())) {
                count++;
            }
        }
        return count;
    }

    private void removeStaleLevelEntries(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        for (PhysicsSubLevel subLevel : container.subLevels()) {
            discardVisuals(level, subLevel);
            subLevel.clearBlockEntities();
            container.remove(subLevel.id());
        }
    }

    private void rebuildSubLevelBody(ServerLevel level, PhysicsSubLevel subLevel, MechanicsBodySnapshot previousBody) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(subLevel, "subLevel");
        Objects.requireNonNull(previousBody, "previousBody");
        if (subLevel.section().isEmpty()) {
            return;
        }
        if (!SubLevelAssembler.hasPhysicalCollision(subLevel.blocks())) {
            throw new IllegalArgumentException("Sublevel has no valid collision boxes");
        }

        MechanicsWorld world = PhysX4mc.api().world(level);
        MechanicsBodySnapshot replacement = null;
        try {
            replacement = world.createDynamicCompoundBox(SubLevelAssembler.compoundDefinition(
                    previousBody.pose(),
                    subLevel.blocks(),
                    previousBody.mass()
            ));
            world.setLinearVelocity(replacement.id(), previousBody.linearVelocity());
            SubLevelContainers.requireServer(level).rebuildPlotChunks(subLevel);
        } catch (RuntimeException exception) {
            if (replacement != null) {
                world.removeBody(replacement.id());
            }
            throw exception;
        }

        MechanicsBodyId previousId = subLevel.bodyId();
        subLevel.replaceBody(replacement.id());
        if (!world.removeBody(previousId)) {
            PhysX4mc.LOGGER.warn("Sublevel {} replaced body {}, but old body could not be removed", subLevel.id(), previousId);
        }
        try {
            refreshVisualsAfterBodyReplacement(level, subLevel, previousBody, replacement);
        } catch (RuntimeException exception) {
            PhysX4mc.LOGGER.warn("Failed to refresh debug visuals after replacing sublevel body {}", subLevel.id(), exception);
        }
    }

    private SplitResult rebuildAfterBlockRemoval(
            ServerLevel level,
            ServerSubLevelContainer container,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot previousBody
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(subLevel, "subLevel");
        Objects.requireNonNull(previousBody, "previousBody");
        if (subLevel.section().isEmpty()) {
            return SplitResult.notSplit(0);
        }
        if (!SubLevelAssembler.hasPhysicalCollision(subLevel.blocks())) {
            subLevel.markRemoving();
            discardVisuals(level, previousBody, subLevel);
            subLevel.clearBlockEntities();
            PhysX4mc.api().existingWorld(level).ifPresent(world -> world.removeBody(subLevel.bodyId()));
            container.remove(subLevel.id());
            return new SplitResult(true, 0, 0);
        }

        List<List<SubLevelBlock>> components = connectedComponents(subLevel);
        if (components.size() <= 1) {
            subLevel.refreshBoundsFromBlocks();
            subLevel.markDirty();
            rebuildSubLevelBody(level, subLevel, previousBody);
            return SplitResult.notSplit(1);
        }

        List<List<SubLevelBlock>> physicalComponents = components.stream()
                .filter(SubLevelAssembler::hasPhysicalCollision)
                .toList();
        if (physicalComponents.size() <= 1) {
            subLevel.refreshBoundsFromBlocks();
            subLevel.markDirty();
            rebuildSubLevelBody(level, subLevel, previousBody);
            return SplitResult.notSplit(1);
        }

        int created = splitSubLevel(level, container, subLevel, previousBody, physicalComponents);
        splitEvents++;
        splitCreatedSubLevels += created;
        return new SplitResult(true, physicalComponents.size(), created);
    }

    private int splitSubLevel(
            ServerLevel level,
            ServerSubLevelContainer container,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot previousBody,
            List<List<SubLevelBlock>> components
    ) {
        MechanicsWorld world = PhysX4mc.api().world(level);
        List<SplitSubLevel> created = new ArrayList<>(components.size());
        try {
            int totalBlocks = components.stream().mapToInt(List::size).sum();
            for (List<SubLevelBlock> component : components) {
                SplitComponent splitComponent = splitComponent(level, subLevel, previousBody, component);
                float componentMass = splitMass(previousBody.mass(), component.size(), totalBlocks);
                MechanicsBodySnapshot body = world.createDynamicCompoundBox(SubLevelAssembler.compoundDefinition(
                        splitComponent.pose(),
                        splitComponent.blocks(),
                        componentMass
                ));
                world.setLinearVelocity(body.id(), previousBody.linearVelocity());
                PhysicsSubLevel child = new PhysicsSubLevel(
                        SubLevelId.random(),
                        level.dimension(),
                        container.allocatePlot(splitComponent.bounds()),
                        body.id(),
                        splitComponent.bounds(),
                        splitComponent.blocks()
                );
                created.add(new SplitSubLevel(child, body));
                container.add(child);
                container.movePlotScheduledTicksToChild(subLevel, child);
                child.activate();
                if (subLevel.debugVisualsEnabled() || !subLevel.visuals().isEmpty()) {
                    createVisuals(level, body, child);
                }
            }
        } catch (RuntimeException exception) {
            for (SplitSubLevel split : created) {
                discardVisuals(level, split.body(), split.subLevel());
                split.subLevel().markRemoving();
                split.subLevel().clearBlockEntities();
                container.remove(split.subLevel().id());
                world.removeBody(split.body().id());
            }
            throw exception;
        }

        subLevel.markRemoving();
        discardVisuals(level, previousBody, subLevel);
        subLevel.clearBlockEntities();
        world.removeBody(subLevel.bodyId());
        container.remove(subLevel.id());
        return created.size();
    }

    private SplitComponent splitComponent(
            ServerLevel level,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot previousBody,
            List<SubLevelBlock> component
    ) {
        SubLevelBounds actualBounds = boundsForComponent(subLevel, component);
        SubLevelBounds bounds = SubLevelAssembler.framedBounds(level, actualBounds);
        PhysicsVector oldBodyToPlotOrigin = bodyToPlotOrigin(subLevel);
        BlockPos oldLocalOrigin = actualBounds.toLocalIn(subLevel.bounds());
        PhysicsVector minBodyLocal = add(oldBodyToPlotOrigin, oldLocalOrigin);
        PhysicsVector newWorldPosition = SubLevelTransform.from(previousBody).localToWorld(minBodyLocal);
        PhysicsPose pose = new PhysicsPose(newWorldPosition, previousBody.pose().rotation());

        List<SubLevelBlock> splitBlocks = component.stream()
                .map(block -> {
                    BlockPos newLocalPos = bounds.toLocal(block.sourcePos());
                    PhysicsVector visualLocalOrigin = new PhysicsVector(
                            block.sourcePos().getX() - actualBounds.minSourcePos().getX(),
                            block.sourcePos().getY() - actualBounds.minSourcePos().getY(),
                            block.sourcePos().getZ() - actualBounds.minSourcePos().getZ()
                    );
                    return new SubLevelBlock(
                            block.sourcePos(),
                            newLocalPos.immutable(),
                            block.blockState(),
                            block.localCollisionBounds(),
                            block.localCollisionBoxes(),
                            visualLocalOrigin,
                            splitBlockEntityTag(level, subLevel, block)
                    );
                })
                .toList();
        return new SplitComponent(bounds, splitBlocks, pose);
    }

    private static List<List<SubLevelBlock>> connectedComponents(PhysicsSubLevel subLevel) {
        Map<BlockPos, SubLevelBlock> blocksByLocalPos = new LinkedHashMap<>();
        for (SubLevelBlock block : subLevel.blocks()) {
            blocksByLocalPos.put(block.localPos(), block);
        }

        List<List<SubLevelBlock>> components = new ArrayList<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        for (SubLevelBlock start : subLevel.blocks()) {
            if (!visited.add(start.localPos())) {
                continue;
            }

            List<SubLevelBlock> component = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start.localPos());
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                SubLevelBlock block = blocksByLocalPos.get(current);
                if (block == null) {
                    continue;
                }
                component.add(block);
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = current.relative(direction);
                    if (visited.contains(neighbor) || !blocksByLocalPos.containsKey(neighbor)) {
                        continue;
                    }
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
            components.add(List.copyOf(component));
        }
        return List.copyOf(components);
    }

    private static SubLevelBounds boundsForComponent(PhysicsSubLevel subLevel, List<SubLevelBlock> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("component must not be empty");
        }

        BlockPos minLocal = blocks.getFirst().localPos();
        int minX = minLocal.getX();
        int minY = minLocal.getY();
        int minZ = minLocal.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        for (SubLevelBlock block : blocks) {
            BlockPos localPos = block.localPos();
            minX = Math.min(minX, localPos.getX());
            minY = Math.min(minY, localPos.getY());
            minZ = Math.min(minZ, localPos.getZ());
            maxX = Math.max(maxX, localPos.getX());
            maxY = Math.max(maxY, localPos.getY());
            maxZ = Math.max(maxZ, localPos.getZ());
        }

        BlockPos sourceOrigin = subLevel.section().toSourcePos(new BlockPos(minX, minY, minZ));
        return new SubLevelBounds(
                sourceOrigin,
                sourceOrigin,
                subLevel.section().toSourcePos(new BlockPos(maxX, maxY, maxZ))
        );
    }

    private static CompoundTag splitBlockEntityTag(ServerLevel level, PhysicsSubLevel subLevel, SubLevelBlock block) {
        BlockPos oldPlotPos = subLevel.plot().toPlotBlockPos(block.localPos());
        BlockEntity blockEntity = level.getBlockEntity(oldPlotPos);
        if (blockEntity != null && !blockEntity.isRemoved()) {
            return blockEntity.saveWithFullMetadata(level.registryAccess());
        }
        return block.blockEntityTag();
    }

    private static float splitMass(float previousMass, int componentBlocks, int totalBlocks) {
        if (totalBlocks <= 0) {
            return previousMass;
        }
        return Math.max(0.001F, previousMass * ((float) componentBlocks / (float) totalBlocks));
    }

    private void recordRemovedPlotProjection(
            ServerLevel level,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot body,
            BlockPos plotPos
    ) {
        RemovedPlotProjectionKey key = new RemovedPlotProjectionKey(level.dimension(), plotPos.immutable());
        removedPlotProjections.put(key, new RemovedPlotProjection(
                subLevel.plot(),
                bodyToPlotOrigin(subLevel),
                SubLevelTransform.from(body)
        ));
    }

    private Optional<PlotProjection> removedPlotProjection(ServerLevel level, BlockPos plotPos) {
        RemovedPlotProjection projection = removedPlotProjections.get(new RemovedPlotProjectionKey(level.dimension(), plotPos));
        return projection == null ? Optional.empty() : Optional.of(projection);
    }

    private Optional<PhysicsSubLevel> subLevelAtPlotBlock(ServerLevel level, BlockPos plotPos) {
        return SubLevelContainers.requireServer(level).subLevelAtPlotBlock(plotPos);
    }

    private static PhysicsVector plotPositionToBodyLocal(PhysicsSubLevel subLevel, Vec3 plotPosition) {
        return plotPositionToBodyLocal(subLevel.plot(), bodyToPlotOrigin(subLevel), plotPosition);
    }

    private static PhysicsVector plotPositionToBodyLocal(SubLevelPlot plot, PhysicsVector bodyToPlotOrigin, Vec3 plotPosition) {
        return new PhysicsVector(
                bodyToPlotOrigin.x() + plotPosition.x() - plot.minPlotX(),
                bodyToPlotOrigin.y() + plotPosition.y() - plot.minPlotY(),
                bodyToPlotOrigin.z() + plotPosition.z() - plot.minPlotZ()
        );
    }

    private static PhysicsVector visualLocalOriginFor(PhysicsSubLevel subLevel, BlockPos localPos) {
        List<SubLevelBlock> blocks = subLevel.blocks();
        if (blocks.isEmpty()) {
            return new PhysicsVector(localPos.getX(), localPos.getY(), localPos.getZ());
        }
        SubLevelBlock first = blocks.getFirst();
        return new PhysicsVector(
                first.visualLocalOrigin().x() + localPos.getX() - first.localPos().getX(),
                first.visualLocalOrigin().y() + localPos.getY() - first.localPos().getY(),
                first.visualLocalOrigin().z() + localPos.getZ() - first.localPos().getZ()
        );
    }

    private static PhysicsVector bodyToPlotOrigin(PhysicsSubLevel subLevel) {
        return subLevel.blocks().stream()
                .findFirst()
                .map(block -> new PhysicsVector(
                        block.visualLocalOrigin().x() - block.localPos().getX(),
                        block.visualLocalOrigin().y() - block.localPos().getY(),
                        block.visualLocalOrigin().z() - block.localPos().getZ()
                ))
                .orElse(PhysicsVector.ZERO);
    }

    private static BlockEntity createBlockEntity(ServerLevel level, BlockPos plotPos, SubLevelBlock block) {
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

    private static SubLevelSnapshot snapshot(MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        return new SubLevelSnapshot(
                subLevel.id(),
                subLevel.levelKey(),
                subLevel.state(),
                subLevel.plot(),
                body,
                subLevel.bounds(),
                subLevel.blocks(),
                subLevel.visuals().size(),
                subLevel.section().dirtyBlockCount()
        );
    }

    private static Optional<Double> intersectBlock(PhysicsVector origin, PhysicsVector direction, SubLevelBlock block, double maxDistance) {
        Optional<Double> best = Optional.empty();
        List<AABB> pickBoxes = block.hasPhysicalCollision()
                ? block.bodyLocalCollisionBoxes()
                : List.of(block.bodyLocalBounds());
        for (AABB bounds : pickBoxes) {
            Optional<Double> maybeDistance = intersectAabb(origin, direction, bounds, maxDistance);
            if (maybeDistance.isEmpty()) {
                continue;
            }
            if (best.isEmpty() || maybeDistance.get() < best.get()) {
                best = maybeDistance;
            }
        }
        return best;
    }

    private static Optional<Double> intersectAabb(PhysicsVector origin, PhysicsVector direction, AABB bounds, double maxDistance) {
        RayInterval x = clipAxis(origin.x(), direction.x(), bounds.minX, bounds.maxX, 0.0D, maxDistance);
        if (x == null) {
            return Optional.empty();
        }
        RayInterval y = clipAxis(origin.y(), direction.y(), bounds.minY, bounds.maxY, x.min(), x.max());
        if (y == null) {
            return Optional.empty();
        }
        RayInterval z = clipAxis(origin.z(), direction.z(), bounds.minZ, bounds.maxZ, y.min(), y.max());
        if (z == null) {
            return Optional.empty();
        }
        return Optional.of(z.min());
    }

    private static RayInterval clipAxis(double origin, double direction, double min, double max, double tMin, double tMax) {
        if (Math.abs(direction) < 1.0E-12D) {
            return origin >= min && origin <= max ? new RayInterval(tMin, tMax) : null;
        }
        double invDirection = 1.0D / direction;
        double first = (min - origin) * invDirection;
        double second = (max - origin) * invDirection;
        if (first > second) {
            double tmp = first;
            first = second;
            second = tmp;
        }
        double clippedMin = Math.max(tMin, first);
        double clippedMax = Math.min(tMax, second);
        return clippedMin <= clippedMax ? new RayInterval(clippedMin, clippedMax) : null;
    }

    private static PhysicsVector normalize(PhysicsVector vector) {
        double length = Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            throw new IllegalArgumentException("Ray direction must be non-zero");
        }
        return new PhysicsVector(vector.x() / length, vector.y() / length, vector.z() / length);
    }

    private static PhysicsVector add(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static PhysicsVector add(PhysicsVector first, BlockPos second) {
        return new PhysicsVector(first.x() + second.getX(), first.y() + second.getY(), first.z() + second.getZ());
    }

    private static PhysicsVector scale(PhysicsVector vector, double scalar) {
        return new PhysicsVector(vector.x() * scalar, vector.y() * scalar, vector.z() * scalar);
    }

    private static String describePos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record RayInterval(double min, double max) {
    }

    private record SplitResult(boolean removedOriginal, int components, int createdSubLevels) {
        private SplitResult {
            if (components < 0) {
                throw new IllegalArgumentException("components must not be negative");
            }
            if (createdSubLevels < 0) {
                throw new IllegalArgumentException("createdSubLevels must not be negative");
            }
        }

        private static SplitResult notSplit(int components) {
            return new SplitResult(false, components, 0);
        }
    }

    private record SplitComponent(SubLevelBounds bounds, List<SubLevelBlock> blocks, PhysicsPose pose) {
        private SplitComponent {
            Objects.requireNonNull(bounds, "bounds");
            blocks = List.copyOf(blocks);
            Objects.requireNonNull(pose, "pose");
        }
    }

    private record SplitSubLevel(PhysicsSubLevel subLevel, MechanicsBodySnapshot body) {
        private SplitSubLevel {
            Objects.requireNonNull(subLevel, "subLevel");
            Objects.requireNonNull(body, "body");
        }
    }

    private record RemovedBlocks(Map<BlockPos, SubLevelBlock> blocksByLocalPos) {
        private static final RemovedBlocks EMPTY = new RemovedBlocks(Map.of());

        private RemovedBlocks {
            blocksByLocalPos = Map.copyOf(blocksByLocalPos);
        }

        private boolean isEmpty() {
            return blocksByLocalPos.isEmpty();
        }

        private List<BlockPos> localPositions() {
            return List.copyOf(blocksByLocalPos.keySet());
        }
    }

    private record RemovedPlotProjectionKey(ResourceKey<Level> levelKey, BlockPos plotPos) {
        private RemovedPlotProjectionKey {
            Objects.requireNonNull(levelKey, "levelKey");
            Objects.requireNonNull(plotPos, "plotPos");
            plotPos = plotPos.immutable();
        }
    }

    public record BridgeStats(
            long vanillaBreakActions,
            long vanillaUseActions,
            long vanillaBreakAccepted,
            long vanillaUseAccepted,
            long vanillaBreakRejected,
            long vanillaUseRejected,
            long plotBlockWrites,
            long splitEvents,
            long splitCreatedSubLevels
    ) {
    }

    public interface PlotProjection {
        PhysicsVector toWorld(Vec3 plotPosition);

        PhysicsVector directionToWorld(Vec3 plotDirection);
    }

    private record ActivePlotProjection(PhysicsSubLevel subLevel, SubLevelTransform transform) implements PlotProjection {
        private ActivePlotProjection {
            Objects.requireNonNull(subLevel, "subLevel");
            Objects.requireNonNull(transform, "transform");
        }

        @Override
        public PhysicsVector toWorld(Vec3 plotPosition) {
            Objects.requireNonNull(plotPosition, "plotPosition");
            return transform.localToWorld(plotPositionToBodyLocal(subLevel, plotPosition));
        }

        @Override
        public PhysicsVector directionToWorld(Vec3 plotDirection) {
            Objects.requireNonNull(plotDirection, "plotDirection");
            return transform.localDirectionToWorld(new PhysicsVector(plotDirection.x(), plotDirection.y(), plotDirection.z()));
        }
    }

    private record RemovedPlotProjection(
            SubLevelPlot plot,
            PhysicsVector bodyToPlotOrigin,
            SubLevelTransform transform
    ) implements PlotProjection {
        private RemovedPlotProjection {
            Objects.requireNonNull(plot, "plot");
            Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
            Objects.requireNonNull(transform, "transform");
        }

        @Override
        public PhysicsVector toWorld(Vec3 plotPosition) {
            Objects.requireNonNull(plotPosition, "plotPosition");
            return transform.localToWorld(plotPositionToBodyLocal(plot, bodyToPlotOrigin, plotPosition));
        }

        @Override
        public PhysicsVector directionToWorld(Vec3 plotDirection) {
            Objects.requireNonNull(plotDirection, "plotDirection");
            return transform.localDirectionToWorld(new PhysicsVector(plotDirection.x(), plotDirection.y(), plotDirection.z()));
        }
    }
}
