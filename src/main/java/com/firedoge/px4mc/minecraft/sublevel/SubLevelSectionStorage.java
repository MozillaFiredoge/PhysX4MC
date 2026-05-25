package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public final class SubLevelSectionStorage {
    public static final int SECTION_SIZE = 16;

    private final BlockPos sourceOrigin;
    private final Map<BlockPos, SubLevelBlock> blocksByLocalPos = new LinkedHashMap<>();
    private final Set<BlockPos> dirtyLocalPositions = new LinkedHashSet<>();

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
        return blocksByLocalPos.size();
    }

    public boolean isEmpty() {
        return blocksByLocalPos.isEmpty();
    }

    public List<SubLevelBlock> blocks() {
        return List.copyOf(blocksByLocalPos.values());
    }

    public Optional<SubLevelBlock> block(BlockPos localPos) {
        requireLocal(localPos);
        return Optional.ofNullable(blocksByLocalPos.get(localPos));
    }

    public BlockState blockState(BlockPos localPos) {
        requireLocal(localPos);
        SubLevelBlock block = blocksByLocalPos.get(localPos);
        return block == null ? Blocks.AIR.defaultBlockState() : block.blockState();
    }

    public Optional<AABB> collisionBounds(BlockPos localPos) {
        return block(localPos).map(SubLevelBlock::localCollisionBounds);
    }

    public boolean hasBlock(BlockPos localPos) {
        requireLocal(localPos);
        return blocksByLocalPos.containsKey(localPos);
    }

    public BlockPos toSourcePos(BlockPos localPos) {
        requireLocal(localPos);
        return sourceOrigin.offset(localPos.getX(), localPos.getY(), localPos.getZ());
    }

    public void putBlock(SubLevelBlock block) {
        Objects.requireNonNull(block, "block");
        requireLocal(block.localPos());
        BlockPos localPos = block.localPos().immutable();
        blocksByLocalPos.put(localPos, block);
        dirtyLocalPositions.add(localPos);
    }

    public boolean updateBlockEntityTag(BlockPos localPos, @Nullable CompoundTag blockEntityTag) {
        requireLocal(localPos);
        BlockPos immutable = localPos.immutable();
        SubLevelBlock block = blocksByLocalPos.get(immutable);
        if (block == null) {
            return false;
        }
        blocksByLocalPos.put(immutable, block.withBlockEntityTag(blockEntityTag));
        return true;
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
        requireLocal(localPos);
        BlockPos immutable = localPos.immutable();
        SubLevelBlock previous = blocksByLocalPos.remove(immutable);
        dirtyLocalPositions.add(immutable);
        return Optional.ofNullable(previous);
    }

    public void markDirty(BlockPos localPos) {
        requireLocal(localPos);
        dirtyLocalPositions.add(localPos.immutable());
    }

    public boolean hasDirtyBlocks() {
        return !dirtyLocalPositions.isEmpty();
    }

    public int dirtyBlockCount() {
        return dirtyLocalPositions.size();
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
        dirtyLocalPositions.clear();
    }

    private void loadInitial(SubLevelBlock block) {
        Objects.requireNonNull(block, "block");
        requireLocal(block.localPos());
        BlockPos localPos = block.localPos().immutable();
        if (blocksByLocalPos.containsKey(localPos)) {
            throw new IllegalArgumentException("Duplicate sublevel block at local position " + describe(block.localPos()));
        }
        blocksByLocalPos.put(localPos, block);
    }

    private List<BlockPos> dirtyLocalPositions(int maxCount, boolean clear) {
        List<BlockPos> result = new ArrayList<>(Math.min(maxCount, dirtyLocalPositions.size()));
        for (BlockPos dirty : dirtyLocalPositions) {
            if (result.size() >= maxCount) {
                break;
            }
            result.add(dirty);
        }
        if (clear) {
            for (BlockPos dirty : result) {
                dirtyLocalPositions.remove(dirty);
            }
        }
        return List.copyOf(result);
    }

    private static void requireLocal(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        if (!isValidLocal(localPos)) {
            throw new IllegalArgumentException("Sublevel local position must be non-negative: " + describe(localPos));
        }
    }

    public static boolean isValidLocal(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        return localPos.getX() >= 0 && localPos.getY() >= 0 && localPos.getZ() >= 0;
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
