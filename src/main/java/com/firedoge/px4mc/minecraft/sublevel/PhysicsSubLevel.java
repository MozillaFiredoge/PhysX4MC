package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.firedoge.px4mc.mechanics.MechanicsBodyId;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class PhysicsSubLevel {
    private final SubLevelId id;
    private final ResourceKey<Level> levelKey;
    private final SubLevelPlot plot;
    private MechanicsBodyId bodyId;
    private SubLevelBounds bounds;
    private final SubLevelSectionStorage section;
    private final List<VisualBinding> visuals = new ArrayList<>();
    private final Map<BlockPos, BlockEntity> blockEntitiesByLocalPos = new LinkedHashMap<>();
    private boolean debugVisualsEnabled;
    private SubLevelLifecycleState state = SubLevelLifecycleState.CAPTURED;

    public PhysicsSubLevel(
            SubLevelId id,
            ResourceKey<Level> levelKey,
            SubLevelPlot plot,
            MechanicsBodyId bodyId,
            SubLevelBounds bounds,
            List<SubLevelBlock> blocks
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.plot = Objects.requireNonNull(plot, "plot");
        this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.section = SubLevelSectionStorage.fromBlocks(bounds.sourceOrigin(), blocks);
        if (this.section.isEmpty()) {
            throw new IllegalArgumentException("blocks must not be empty");
        }
    }

    public SubLevelId id() {
        return id;
    }

    public ResourceKey<Level> levelKey() {
        return levelKey;
    }

    public SubLevelPlot plot() {
        return plot;
    }

    public MechanicsBodyId bodyId() {
        return bodyId;
    }

    public void replaceBody(MechanicsBodyId bodyId) {
        this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
    }

    public SubLevelBounds bounds() {
        return bounds;
    }

    public void refreshBoundsFromBlocks() {
        if (section.isEmpty()) {
            return;
        }
        List<SubLevelBlock> blocks = section.blocks();
        BlockPos first = blocks.getFirst().sourcePos();
        int minX = first.getX();
        int minY = first.getY();
        int minZ = first.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        for (SubLevelBlock block : blocks) {
            BlockPos sourcePos = block.sourcePos();
            minX = Math.min(minX, sourcePos.getX());
            minY = Math.min(minY, sourcePos.getY());
            minZ = Math.min(minZ, sourcePos.getZ());
            maxX = Math.max(maxX, sourcePos.getX());
            maxY = Math.max(maxY, sourcePos.getY());
            maxZ = Math.max(maxZ, sourcePos.getZ());
        }
        bounds = new SubLevelBounds(
                bounds.sourceOrigin(),
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ)
        );
    }

    public SubLevelLifecycleState state() {
        return state;
    }

    public void activate() {
        if (state != SubLevelLifecycleState.REMOVING) {
            state = SubLevelLifecycleState.ACTIVE;
        }
    }

    public void markDirty() {
        if (state != SubLevelLifecycleState.REMOVING) {
            state = SubLevelLifecycleState.DIRTY;
        }
    }

    public void markRemoving() {
        state = SubLevelLifecycleState.REMOVING;
    }

    public SubLevelSectionStorage section() {
        return section;
    }

    public List<SubLevelBlock> blocks() {
        return section.blocks();
    }

    public List<VisualBinding> visuals() {
        return visuals;
    }

    public boolean debugVisualsEnabled() {
        return debugVisualsEnabled;
    }

    public void setDebugVisualsEnabled(boolean debugVisualsEnabled) {
        this.debugVisualsEnabled = debugVisualsEnabled;
    }

    public Optional<BlockEntity> blockEntity(BlockPos localPos) {
        return Optional.ofNullable(blockEntitiesByLocalPos.get(localPos));
    }

    public void putBlockEntity(BlockPos localPos, BlockEntity blockEntity) {
        blockEntitiesByLocalPos.put(localPos.immutable(), Objects.requireNonNull(blockEntity, "blockEntity"));
    }

    public void removeBlockEntity(BlockPos localPos) {
        BlockEntity blockEntity = blockEntitiesByLocalPos.remove(localPos);
        if (blockEntity != null) {
            blockEntity.setRemoved();
        }
    }

    public void clearBlockEntities() {
        for (BlockEntity blockEntity : blockEntitiesByLocalPos.values()) {
            blockEntity.setRemoved();
        }
        blockEntitiesByLocalPos.clear();
    }

    public record VisualBinding(SubLevelBlock block, UUID entityId) {
        public VisualBinding {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(entityId, "entityId");
        }
    }
}
