package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsBoxCollider;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.firedoge.px4mc.mechanics.MechanicsCompoundBoxDefinition;
import com.firedoge.px4mc.mechanics.MechanicsWorld;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

final class SubLevelAssembler {
    private static final int BLOCK_UPDATE_FLAGS = 3;
    private static final int CAPTURE_REMOVE_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_KNOWN_SHAPE;
    private static final int MAX_ASSEMBLY_SCAN_VOLUME = 32768;
    private static final int MAX_ASSEMBLY_BLOCKS = 1024;
    private static final int MAX_ASSEMBLY_HORIZONTAL_SPAN = 64;
    private static final int MAX_ASSEMBLY_VERTICAL_SPAN = 64;
    private static final int MAX_COMPOUND_BOXES = 512;
    private static final double MERGE_EPSILON = 1.0E-7D;

    private SubLevelAssembler() {
    }

    static Result assembleBox(
            ServerLevel level,
            BlockPos first,
            BlockPos second,
            float mass,
            ServerSubLevelContainer container
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(container, "container");
        if (container.level() != level) {
            throw new IllegalArgumentException("Sublevel container must belong to the assembling level");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Mass must be positive");
        }

        SubLevelBounds bounds = framedBounds(level, SubLevelBounds.from(first, second));
        validateBounds(bounds);
        List<SubLevelBlock> blocks = collectBlocks(level, bounds);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("No detachable blocks in " + describeBounds(bounds));
        }
        if (!hasPhysicalCollision(blocks)) {
            throw new IllegalArgumentException("Sublevel has no detachable physical collision blocks in " + describeBounds(bounds));
        }

        AggregateShape aggregate = aggregateShape(blocks);
        List<SubLevelBlock> subLevelBlocks = withVisualOrigins(blocks, aggregate.center());
        MechanicsWorld world = PhysX4mc.api().world(level);
        List<SubLevelBlock> removedBlocks = new ArrayList<>(blocks.size());
        MechanicsBodySnapshot body = null;
        try {
            for (SubLevelBlock block : captureRemovalOrder(blocks)) {
                if (!removeSourceBlockForCapture(level, block)) {
                    throw new IllegalStateException("Failed to remove block at " + describePos(block.sourcePos()));
                }
                removedBlocks.add(block);
            }
            refreshTerrainAround(level, removedBlocks);

            body = world.createDynamicCompoundBox(compoundDefinition(
                    new PhysicsPose(aggregate.center(), PhysicsQuaternion.IDENTITY),
                    subLevelBlocks,
                    mass
            ));
            SubLevelPlot plot = container.allocatePlot(bounds);
            PhysicsSubLevel subLevel = new PhysicsSubLevel(
                    SubLevelId.random(),
                    level.dimension(),
                    plot,
                    body.id(),
                    bounds,
                    subLevelBlocks
            );
            return new Result(subLevel, body);
        } catch (RuntimeException exception) {
            if (body != null) {
                world.removeBody(body.id());
            }
            for (int i = removedBlocks.size() - 1; i >= 0; i--) {
                SubLevelBlock block = removedBlocks.get(i);
                level.setBlock(block.sourcePos(), block.blockState(), BLOCK_UPDATE_FLAGS);
                restoreSourceBlockEntity(level, block);
            }
            refreshTerrainAround(level, removedBlocks);
            throw exception;
        }
    }

    private static List<SubLevelBlock> collectBlocks(ServerLevel level, SubLevelBounds bounds) {
        List<SubLevelBlock> blocks = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bounds.minSourcePos().getY(); y <= bounds.maxSourcePos().getY(); y++) {
            for (int z = bounds.minSourcePos().getZ(); z <= bounds.maxSourcePos().getZ(); z++) {
                for (int x = bounds.minSourcePos().getX(); x <= bounds.maxSourcePos().getX(); x++) {
                    pos.set(x, y, z);
                    BlockState blockState = level.getBlockState(pos);
                    if (blockState.isAir()) {
                        continue;
                    }
                    List<AABB> localCollisionBoxes = physicalCollisionBoxes(blockState, level, pos);
                    AABB localBounds = localGeometryBounds(blockState, level, pos);
                    if (blocks.size() >= MAX_ASSEMBLY_BLOCKS) {
                        throw new IllegalArgumentException("Sublevels are limited to " + MAX_ASSEMBLY_BLOCKS + " blocks");
                    }
                    BlockPos sourcePos = pos.immutable();
                    blocks.add(new SubLevelBlock(
                            sourcePos,
                            bounds.toLocal(sourcePos),
                            blockState,
                            localBounds,
                            localCollisionBoxes,
                            PhysicsVector.ZERO,
                            blockEntityTag(level, sourcePos)
                    ));
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static List<SubLevelBlock> captureRemovalOrder(List<SubLevelBlock> blocks) {
        return blocks.stream()
                .sorted(Comparator
                        .comparingInt(SubLevelAssembler::captureRemovalPriority).reversed()
                        .thenComparing((SubLevelBlock block) -> block.sourcePos().getY(), Comparator.reverseOrder()))
                .toList();
    }

    private static int captureRemovalPriority(SubLevelBlock block) {
        BlockState state = block.blockState();
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return 2;
        }
        return block.blockState().getCollisionShape(EmptyBlockGetter.INSTANCE, block.sourcePos()).isEmpty() ? 1 : 0;
    }

    private static CompoundTag blockEntityTag(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    private static boolean removeSourceBlockForCapture(ServerLevel level, SubLevelBlock block) {
        if (block.blockState().hasBlockEntity()) {
            level.removeBlockEntity(block.sourcePos());
        }
        boolean removed = level.setBlock(block.sourcePos(), Blocks.AIR.defaultBlockState(), CAPTURE_REMOVE_FLAGS);
        if (!removed) {
            restoreSourceBlockEntity(level, block);
        }
        return removed;
    }

    private static void restoreSourceBlockEntity(ServerLevel level, SubLevelBlock block) {
        CompoundTag tag = block.blockEntityTag();
        if (tag == null || !block.blockState().hasBlockEntity()) {
            return;
        }

        CompoundTag sourceTag = tag.copy();
        sourceTag.putInt("x", block.sourcePos().getX());
        sourceTag.putInt("y", block.sourcePos().getY());
        sourceTag.putInt("z", block.sourcePos().getZ());
        BlockEntity blockEntity = BlockEntity.loadStatic(block.sourcePos(), block.blockState(), sourceTag, level.registryAccess());
        if (blockEntity == null) {
            return;
        }

        blockEntity.setLevel(level);
        blockEntity.clearRemoved();
        level.setBlockEntity(blockEntity);
        blockEntity.setChanged();
    }

    static boolean hasPhysicalCollision(List<SubLevelBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        return blocks.stream().anyMatch(SubLevelBlock::hasPhysicalCollision);
    }

    static MechanicsCompoundBoxDefinition compoundDefinition(PhysicsPose pose, List<SubLevelBlock> blocks, float mass) {
        Objects.requireNonNull(pose, "pose");
        CollisionGeometry geometry = collisionGeometry(blocks);
        return MechanicsCompoundBoxDefinition.gameplayDynamicCompoundBox(
                pose,
                geometry.boxes(),
                geometry.halfExtents(),
                mass
        );
    }

    private static void validateBounds(SubLevelBounds bounds) {
        if (bounds.width() > MAX_ASSEMBLY_HORIZONTAL_SPAN || bounds.depth() > MAX_ASSEMBLY_HORIZONTAL_SPAN) {
            throw new IllegalArgumentException("Sublevel horizontal assembly span is limited to " + MAX_ASSEMBLY_HORIZONTAL_SPAN + " blocks per axis");
        }
        if (bounds.height() > MAX_ASSEMBLY_VERTICAL_SPAN) {
            throw new IllegalArgumentException("Sublevel vertical assembly span is limited to " + MAX_ASSEMBLY_VERTICAL_SPAN + " blocks");
        }
        if (bounds.volume() > MAX_ASSEMBLY_SCAN_VOLUME) {
            throw new IllegalArgumentException("Sublevel assembly scan volume is limited to " + MAX_ASSEMBLY_SCAN_VOLUME + " blocks");
        }
    }

    static SubLevelBounds framedBounds(ServerLevel level, SubLevelBounds actualBounds) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(actualBounds, "actualBounds");
        int slotBlockSpan = SubLevelPlotAllocator.plotSlotBlockSpan();
        if (actualBounds.width() > slotBlockSpan || actualBounds.depth() > slotBlockSpan) {
            throw new IllegalArgumentException("Sublevel horizontal span does not fit in the plot slot");
        }
        int xPadding = (slotBlockSpan - actualBounds.width()) / 2;
        int zPadding = (slotBlockSpan - actualBounds.depth()) / 2;
        BlockPos sourceOrigin = new BlockPos(
                actualBounds.minSourcePos().getX() - xPadding,
                level.getMinBuildHeight(),
                actualBounds.minSourcePos().getZ() - zPadding
        );
        return new SubLevelBounds(sourceOrigin, actualBounds.minSourcePos(), actualBounds.maxSourcePos());
    }

    private static CollisionGeometry collisionGeometry(List<SubLevelBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        List<AABB> bodyLocalBoxes = new ArrayList<>();
        for (SubLevelBlock block : blocks) {
            Objects.requireNonNull(block, "block");
            bodyLocalBoxes.addAll(block.bodyLocalCollisionBoxes());
        }

        List<AABB> mergedBodyLocalBoxes = mergeAlignedBoxes(bodyLocalBoxes);
        if (mergedBodyLocalBoxes.isEmpty()) {
            throw new IllegalArgumentException("Sublevel has no valid collision boxes");
        }
        if (mergedBodyLocalBoxes.size() > MAX_COMPOUND_BOXES) {
            throw new IllegalArgumentException("Sublevel compound collision is limited to " + MAX_COMPOUND_BOXES + " boxes after merge");
        }

        List<PhysicsBoxCollider> boxes = new ArrayList<>(mergedBodyLocalBoxes.size());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (AABB bounds : mergedBodyLocalBoxes) {
            PhysicsVector halfExtents = halfExtents(bounds);
            if (!isPositive(halfExtents)) {
                continue;
            }
            PhysicsVector center = new PhysicsVector(
                    (bounds.minX + bounds.maxX) * 0.5D,
                    (bounds.minY + bounds.maxY) * 0.5D,
                    (bounds.minZ + bounds.maxZ) * 0.5D
            );
            boxes.add(new PhysicsBoxCollider(center, halfExtents));
            minX = Math.min(minX, bounds.minX);
            minY = Math.min(minY, bounds.minY);
            minZ = Math.min(minZ, bounds.minZ);
            maxX = Math.max(maxX, bounds.maxX);
            maxY = Math.max(maxY, bounds.maxY);
            maxZ = Math.max(maxZ, bounds.maxZ);
        }
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("Sublevel has no valid collision boxes");
        }

        PhysicsVector halfExtents = new PhysicsVector(
                Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(Math.abs(minY), Math.abs(maxY)),
                Math.max(Math.abs(minZ), Math.abs(maxZ))
        );
        if (!isPositive(halfExtents)) {
            throw new IllegalArgumentException("Sublevel collision bounds are too small");
        }
        return new CollisionGeometry(List.copyOf(boxes), halfExtents);
    }

    static List<AABB> collisionBoxes(VoxelShape shape) {
        Objects.requireNonNull(shape, "shape");
        if (shape.isEmpty()) {
            return List.of();
        }
        List<AABB> boxes = new ArrayList<>();
        for (AABB bounds : shape.toAabbs()) {
            if (isPositive(halfExtents(bounds))) {
                boxes.add(copy(bounds));
            }
        }
        return List.copyOf(boxes);
    }

    static List<AABB> geometryBoxes(BlockState blockState, BlockGetter level, BlockPos pos) {
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        List<AABB> collisionBoxes = collisionBoxes(blockState.getCollisionShape(level, pos));
        if (!collisionBoxes.isEmpty()) {
            return collisionBoxes;
        }
        return collisionBoxes(blockState.getShape(level, pos));
    }

    static List<AABB> physicalCollisionBoxes(BlockState blockState, BlockGetter level, BlockPos pos) {
        Objects.requireNonNull(blockState, "blockState");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        return collisionBoxes(blockState.getCollisionShape(level, pos));
    }

    static AABB localGeometryBounds(BlockState blockState, BlockGetter level, BlockPos pos) {
        List<AABB> boxes = geometryBoxes(blockState, level, pos);
        if (!boxes.isEmpty()) {
            return aggregateBounds(boxes);
        }
        return unitBlockBounds();
    }

    static AABB aggregateBounds(List<AABB> boxes) {
        Objects.requireNonNull(boxes, "boxes");
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("boxes must not be empty");
        }
        AABB aggregate = null;
        for (AABB box : boxes) {
            aggregate = aggregate == null ? copy(box) : aggregate.minmax(box);
        }
        return aggregate;
    }

    private static List<AABB> mergeAlignedBoxes(List<AABB> boxes) {
        List<AABB> merged = boxes.stream()
                .filter(bounds -> isPositive(halfExtents(bounds)))
                .map(SubLevelAssembler::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean changed;
        do {
            changed = false;
            outer:
            for (int i = 0; i < merged.size(); i++) {
                for (int j = i + 1; j < merged.size(); j++) {
                    AABB union = tryMerge(merged.get(i), merged.get(j));
                    if (union == null) {
                        continue;
                    }
                    merged.set(i, union);
                    merged.remove(j);
                    changed = true;
                    break outer;
                }
            }
        } while (changed);
        return List.copyOf(merged);
    }

    private static AABB tryMerge(AABB first, AABB second) {
        if (sameInterval(first.minY, first.maxY, second.minY, second.maxY)
                && sameInterval(first.minZ, first.maxZ, second.minZ, second.maxZ)
                && touchesOrOverlaps(first.minX, first.maxX, second.minX, second.maxX)) {
            return new AABB(
                    Math.min(first.minX, second.minX),
                    first.minY,
                    first.minZ,
                    Math.max(first.maxX, second.maxX),
                    first.maxY,
                    first.maxZ
            );
        }
        if (sameInterval(first.minX, first.maxX, second.minX, second.maxX)
                && sameInterval(first.minZ, first.maxZ, second.minZ, second.maxZ)
                && touchesOrOverlaps(first.minY, first.maxY, second.minY, second.maxY)) {
            return new AABB(
                    first.minX,
                    Math.min(first.minY, second.minY),
                    first.minZ,
                    first.maxX,
                    Math.max(first.maxY, second.maxY),
                    first.maxZ
            );
        }
        if (sameInterval(first.minX, first.maxX, second.minX, second.maxX)
                && sameInterval(first.minY, first.maxY, second.minY, second.maxY)
                && touchesOrOverlaps(first.minZ, first.maxZ, second.minZ, second.maxZ)) {
            return new AABB(
                    first.minX,
                    first.minY,
                    Math.min(first.minZ, second.minZ),
                    first.maxX,
                    first.maxY,
                    Math.max(first.maxZ, second.maxZ)
            );
        }
        return null;
    }

    private static boolean sameInterval(double firstMin, double firstMax, double secondMin, double secondMax) {
        return nearlyEqual(firstMin, secondMin) && nearlyEqual(firstMax, secondMax);
    }

    private static boolean touchesOrOverlaps(double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax + MERGE_EPSILON >= secondMin && secondMax + MERGE_EPSILON >= firstMin;
    }

    private static boolean nearlyEqual(double first, double second) {
        return Math.abs(first - second) <= MERGE_EPSILON;
    }

    private static AABB copy(AABB bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    private static AABB unitBlockBounds() {
        return new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    }

    private static AggregateShape aggregateShape(List<SubLevelBlock> blocks) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (SubLevelBlock block : blocks) {
            if (!block.hasPhysicalCollision()) {
                continue;
            }
            BlockPos pos = block.sourcePos();
            for (AABB bounds : block.localCollisionBoxes()) {
                minX = Math.min(minX, pos.getX() + bounds.minX);
                minY = Math.min(minY, pos.getY() + bounds.minY);
                minZ = Math.min(minZ, pos.getZ() + bounds.minZ);
                maxX = Math.max(maxX, pos.getX() + bounds.maxX);
                maxY = Math.max(maxY, pos.getY() + bounds.maxY);
                maxZ = Math.max(maxZ, pos.getZ() + bounds.maxZ);
            }
        }
        if (!Double.isFinite(minX)) {
            throw new IllegalArgumentException("Sublevel has no valid collision boxes");
        }

        PhysicsVector center = new PhysicsVector(
                (minX + maxX) * 0.5D,
                (minY + maxY) * 0.5D,
                (minZ + maxZ) * 0.5D
        );
        PhysicsVector halfExtents = new PhysicsVector(
                (maxX - minX) * 0.5D,
                (maxY - minY) * 0.5D,
                (maxZ - minZ) * 0.5D
        );
        if (!isPositive(halfExtents)) {
            throw new IllegalArgumentException("Sublevel collision bounds are too small");
        }
        return new AggregateShape(center, halfExtents);
    }

    private static List<SubLevelBlock> withVisualOrigins(List<SubLevelBlock> blocks, PhysicsVector bodyCenter) {
        List<SubLevelBlock> visualBlocks = new ArrayList<>(blocks.size());
        for (SubLevelBlock block : blocks) {
            BlockPos pos = block.sourcePos();
            visualBlocks.add(block.withVisualLocalOrigin(new PhysicsVector(
                    pos.getX() - bodyCenter.x(),
                    pos.getY() - bodyCenter.y(),
                    pos.getZ() - bodyCenter.z()
            )));
        }
        return List.copyOf(visualBlocks);
    }

    private static PhysicsVector halfExtents(AABB bounds) {
        return new PhysicsVector(
                (bounds.maxX - bounds.minX) * 0.5D,
                (bounds.maxY - bounds.minY) * 0.5D,
                (bounds.maxZ - bounds.minZ) * 0.5D
        );
    }

    private static boolean isPositive(PhysicsVector vector) {
        return vector.x() > 0.0D && vector.y() > 0.0D && vector.z() > 0.0D;
    }

    static void refreshTerrainAround(ServerLevel level, List<SubLevelBlock> blocks) {
        for (SubLevelBlock block : blocks) {
            ServerPhysicsRuntime.INSTANCE.refreshTerrainCollisionAt(level, block.sourcePos());
            for (Direction direction : Direction.values()) {
                ServerPhysicsRuntime.INSTANCE.refreshTerrainCollisionAt(level, block.sourcePos().relative(direction));
            }
        }
    }

    private static String describePos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String describeBounds(SubLevelBounds bounds) {
        return describePos(bounds.minSourcePos()) + " to " + describePos(bounds.maxSourcePos());
    }

    record Result(PhysicsSubLevel subLevel, MechanicsBodySnapshot body) {
        Result {
            Objects.requireNonNull(subLevel, "subLevel");
            Objects.requireNonNull(body, "body");
        }
    }

    private record AggregateShape(PhysicsVector center, PhysicsVector halfExtents) {
        private AggregateShape {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(halfExtents, "halfExtents");
        }
    }

    private record CollisionGeometry(List<PhysicsBoxCollider> boxes, PhysicsVector halfExtents) {
        private CollisionGeometry {
            boxes = List.copyOf(boxes);
            Objects.requireNonNull(halfExtents, "halfExtents");
        }
    }
}
