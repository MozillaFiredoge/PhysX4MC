package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.firedoge.px4mc.mechanics.MechanicsBodyId;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PhysicsSubLevel {
    private final SubLevelId id;
    private final ResourceKey<Level> levelKey;
    private final MechanicsBodyId bodyId;
    private final SubLevelBounds bounds;
    private final SubLevelSectionStorage section;
    private final List<VisualBinding> visuals = new ArrayList<>();

    public PhysicsSubLevel(
            SubLevelId id,
            ResourceKey<Level> levelKey,
            MechanicsBodyId bodyId,
            SubLevelBounds bounds,
            List<SubLevelBlock> blocks
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
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

    public MechanicsBodyId bodyId() {
        return bodyId;
    }

    public SubLevelBounds bounds() {
        return bounds;
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

    public record VisualBinding(SubLevelBlock block, UUID entityId) {
        public VisualBinding {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(entityId, "entityId");
        }
    }
}
