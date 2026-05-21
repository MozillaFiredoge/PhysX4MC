package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class SubLevelSectionStorage {
    public static final int SECTION_SIZE = 16;
    public static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private final BlockPos sourceOrigin;
    private final SubLevelBlock[] blocks = new SubLevelBlock[SECTION_VOLUME];
    private final BitSet dirtyIndexes = new BitSet(SECTION_VOLUME);
    private int blockCount;

    private SubLevelSectionStorage(BlockPos sourceOrigin) {
        this.sourceOrigin = Objects.requireNonNull(sourceOrigin, "sourceOrigin").immutable();
    }

    public static SubLevelSectionStorage empty(BlockPos sourceOrigin) {
        return new SubLevelSectionStorage(sourceOrigin);
    }

    public static SubLevelSectionStorage fromBlocks(BlockPos sourceOrigin, List<SubLevelBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        SubLevelSectionStorage storage = new SubLevelSectionStorage(sourceOrigin);
        for (SubLevelBlock block : blocks) {
            storage.loadInitial(block);
        }
        return storage;
    }

    public BlockPos sourceOrigin() {
        return sourceOrigin;
    }

    public int blockCount() {
        return blockCount;
    }

    public boolean isEmpty() {
        return blockCount == 0;
    }

    public List<SubLevelBlock> blocks() {
        List<SubLevelBlock> result = new ArrayList<>(blockCount);
        for (SubLevelBlock block : blocks) {
            if (block != null) {
                result.add(block);
            }
        }
        return List.copyOf(result);
    }

    public Optional<SubLevelBlock> block(BlockPos localPos) {
        return Optional.ofNullable(blocks[index(localPos)]);
    }

    public BlockState blockState(BlockPos localPos) {
        SubLevelBlock block = blocks[index(localPos)];
        return block == null ? Blocks.AIR.defaultBlockState() : block.blockState();
    }

    public Optional<AABB> collisionBounds(BlockPos localPos) {
        return block(localPos).map(SubLevelBlock::localCollisionBounds);
    }

    public boolean hasBlock(BlockPos localPos) {
        return blocks[index(localPos)] != null;
    }

    public BlockPos toSourcePos(BlockPos localPos) {
        requireSectionLocal(localPos);
        return sourceOrigin.offset(localPos.getX(), localPos.getY(), localPos.getZ());
    }

    public void putBlock(SubLevelBlock block) {
        Objects.requireNonNull(block, "block");
        requireSectionLocal(block.localPos());
        int index = index(block.localPos());
        if (blocks[index] == null) {
            blockCount++;
        }
        blocks[index] = block;
        dirtyIndexes.set(index);
    }

    public void setBlockState(BlockPos localPos, BlockState blockState, AABB localCollisionBounds) {
        setBlockState(localPos, blockState, localCollisionBounds, PhysicsVector.ZERO);
    }

    public void setBlockState(BlockPos localPos, BlockState blockState, AABB localCollisionBounds, PhysicsVector visualLocalOrigin) {
        Objects.requireNonNull(blockState, "blockState");
        if (blockState.isAir()) {
            removeBlock(localPos);
            return;
        }
        Objects.requireNonNull(localCollisionBounds, "localCollisionBounds");
        Objects.requireNonNull(visualLocalOrigin, "visualLocalOrigin");
        putBlock(new SubLevelBlock(
                toSourcePos(localPos),
                localPos.immutable(),
                blockState,
                localCollisionBounds,
                visualLocalOrigin
        ));
    }

    public Optional<SubLevelBlock> removeBlock(BlockPos localPos) {
        int index = index(localPos);
        SubLevelBlock previous = blocks[index];
        if (previous != null) {
            blocks[index] = null;
            blockCount--;
        }
        dirtyIndexes.set(index);
        return Optional.ofNullable(previous);
    }

    public void markDirty(BlockPos localPos) {
        dirtyIndexes.set(index(localPos));
    }

    public boolean hasDirtyBlocks() {
        return !dirtyIndexes.isEmpty();
    }

    public int dirtyBlockCount() {
        return dirtyIndexes.cardinality();
    }

    public List<BlockPos> dirtyLocalPositions() {
        return dirtyLocalPositions(Integer.MAX_VALUE, false);
    }

    public List<BlockPos> drainDirtyLocalPositions(int maxCount) {
        if (maxCount <= 0) {
            return List.of();
        }
        return dirtyLocalPositions(maxCount, true);
    }

    public void clearDirty() {
        dirtyIndexes.clear();
    }

    private void loadInitial(SubLevelBlock block) {
        Objects.requireNonNull(block, "block");
        requireSectionLocal(block.localPos());
        int index = index(block.localPos());
        if (blocks[index] != null) {
            throw new IllegalArgumentException("Duplicate sublevel block at local position " + describe(block.localPos()));
        }
        blocks[index] = block;
        blockCount++;
    }

    private List<BlockPos> dirtyLocalPositions(int maxCount, boolean clear) {
        List<BlockPos> result = new ArrayList<>(Math.min(maxCount, dirtyIndexes.cardinality()));
        for (int index = dirtyIndexes.nextSetBit(0); index >= 0 && result.size() < maxCount; index = dirtyIndexes.nextSetBit(index + 1)) {
            result.add(localPos(index));
            if (clear) {
                dirtyIndexes.clear(index);
            }
        }
        return List.copyOf(result);
    }

    private static int index(BlockPos localPos) {
        requireSectionLocal(localPos);
        return (localPos.getY() << 8) | (localPos.getZ() << 4) | localPos.getX();
    }

    private static BlockPos localPos(int index) {
        int x = index & 15;
        int z = (index >> 4) & 15;
        int y = (index >> 8) & 15;
        return new BlockPos(x, y, z);
    }

    private static void requireSectionLocal(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        if (!isSectionLocal(localPos)) {
            throw new IllegalArgumentException("Sublevel local position must be inside one 16x16x16 section: " + describe(localPos));
        }
    }

    public static boolean isSectionLocal(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        return localPos.getX() >= 0 && localPos.getX() < SECTION_SIZE
                && localPos.getY() >= 0 && localPos.getY() < SECTION_SIZE
                && localPos.getZ() >= 0 && localPos.getZ() < SECTION_SIZE;
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
