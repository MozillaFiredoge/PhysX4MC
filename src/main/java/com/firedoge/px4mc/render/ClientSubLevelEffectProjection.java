package com.firedoge.px4mc.render;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.ClientTrackedSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelClientMetadata;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class ClientSubLevelEffectProjection {
    private ClientSubLevelEffectProjection() {
    }

    public static Optional<Projection> projection(ClientLevel level, BlockPos plotPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        return SubLevelContainers.container(level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .flatMap(container -> {
                    ChunkPos chunkPos = new ChunkPos(plotPos);
                    Optional<Projection> active = container.trackedSubLevelForChunk(chunkPos)
                            .filter(ClientTrackedSubLevel::finalized)
                            .filter(subLevel -> subLevel.plot().containsPlotBlockPos(plotPos))
                            .map(Projection::active);
                    if (active.isPresent()) {
                        return active;
                    }
                    return container.removedProjectionForChunk(chunkPos)
                            .map(ClientSubLevelContainer.RemovedClientSubLevelProjection::metadata)
                            .filter(metadata -> metadata.plot().containsPlotBlockPos(plotPos))
                            .map(Projection::removed);
                });
    }

    public static Optional<Vec3> plotToWorld(ClientLevel level, Vec3 plotPosition) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        return projection(level, BlockPos.containing(plotPosition))
                .map(projection -> projection.toWorld(plotPosition));
    }

    public record Projection(ClientTrackedSubLevel subLevel, SubLevelClientMetadata metadata) {
        public Projection {
            if (subLevel == null && metadata == null) {
                throw new IllegalArgumentException("Projection needs either an active sublevel or removed metadata");
            }
        }

        static Projection active(ClientTrackedSubLevel subLevel) {
            return new Projection(Objects.requireNonNull(subLevel, "subLevel"), null);
        }

        static Projection removed(SubLevelClientMetadata metadata) {
            return new Projection(null, Objects.requireNonNull(metadata, "metadata"));
        }

        public Vec3 toWorld(Vec3 plotPosition) {
            return subLevel != null
                    ? ClientSubLevelSelection.plotToWorld(plotPosition, subLevel)
                    : ClientSubLevelSelection.plotToWorld(plotPosition, metadata);
        }

        public Vec3 directionToWorld(Vec3 plotDirection) {
            return subLevel != null
                    ? ClientSubLevelSelection.plotDirectionToWorld(plotDirection, subLevel)
                    : ClientSubLevelSelection.plotDirectionToWorld(plotDirection, metadata);
        }
    }
}
