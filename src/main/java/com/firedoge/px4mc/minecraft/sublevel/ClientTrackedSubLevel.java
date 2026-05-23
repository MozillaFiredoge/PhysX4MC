package com.firedoge.px4mc.minecraft.sublevel;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.render.ClientSubLevelBlockView;

import net.minecraft.world.level.ChunkPos;

public final class ClientTrackedSubLevel {
    private final SubLevelClientMetadata metadata;
    private final Set<ChunkPos> loadedChunks = new LinkedHashSet<>();
    private ClientSubLevelBlockView levelView;
    private PhysicsPose pose;
    private boolean finalized;

    ClientTrackedSubLevel(SubLevelClientMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.pose = metadata.pose();
    }

    public SubLevelClientMetadata metadata() {
        return metadata;
    }

    public SubLevelId id() {
        return metadata.id();
    }

    public SubLevelPlot plot() {
        return metadata.plot();
    }

    public PhysicsPose pose() {
        return pose;
    }

    public ClientSubLevelBlockView levelView() {
        if (levelView == null) {
            throw new IllegalStateException("Client sublevel block view has not been attached");
        }
        return levelView;
    }

    void attachLevelView(ClientSubLevelBlockView levelView) {
        this.levelView = Objects.requireNonNull(levelView, "levelView");
    }

    void updatePose(PhysicsPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
    }

    public boolean finalized() {
        return finalized;
    }

    void finalizeTracking() {
        finalized = true;
    }

    void markChunkLoaded(ChunkPos chunkPos) {
        loadedChunks.add(Objects.requireNonNull(chunkPos, "chunkPos"));
    }

    void markChunkDropped(ChunkPos chunkPos) {
        loadedChunks.remove(chunkPos);
    }

    public Set<ChunkPos> loadedChunks() {
        return Set.copyOf(loadedChunks);
    }
}
