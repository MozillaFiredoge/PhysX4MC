package com.firedoge.px4mc.render;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.ClientTrackedSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelId;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class ClientSubLevelBreakingProgress {
    private static final int STALE_TICKS = 400;
    private static final Map<Integer, Entry> ENTRIES_BY_BREAKER = new LinkedHashMap<>();

    private ClientSubLevelBreakingProgress() {
    }

    public static boolean update(ClientLevel level, int breakerId, BlockPos plotPos, int progress) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotPos, "plotPos");
        ClientTrackedSubLevel subLevel = plotSubLevel(level, plotPos);
        if (subLevel == null) {
            return false;
        }

        if (progress >= 0 && progress < 10) {
            ENTRIES_BY_BREAKER.put(
                    breakerId,
                    new Entry(subLevel.id(), plotPos.immutable(), progress, level.getGameTime())
            );
        } else {
            ENTRIES_BY_BREAKER.remove(breakerId);
        }
        return true;
    }

    public static boolean removeIfTracked(int breakerId) {
        return ENTRIES_BY_BREAKER.remove(breakerId) != null;
    }

    public static List<Entry> entries(ClientSubLevelContainer container) {
        Objects.requireNonNull(container, "container");
        long gameTime = container.level().getGameTime();
        ENTRIES_BY_BREAKER.values().removeIf(entry -> {
            if (gameTime - entry.updatedGameTime() > STALE_TICKS) {
                return true;
            }
            return container.trackedSubLevel(entry.id())
                    .filter(ClientTrackedSubLevel::finalized)
                    .filter(subLevel -> subLevel.plot().containsPlotBlockPos(entry.plotPos()))
                    .isEmpty();
        });
        return List.copyOf(ENTRIES_BY_BREAKER.values());
    }

    private static ClientTrackedSubLevel plotSubLevel(ClientLevel level, BlockPos plotPos) {
        return SubLevelContainers.container(level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .flatMap(container -> container.trackedSubLevelForChunk(new ChunkPos(plotPos)))
                .filter(ClientTrackedSubLevel::finalized)
                .filter(subLevel -> subLevel.plot().containsPlotBlockPos(plotPos))
                .orElse(null);
    }

    public record Entry(SubLevelId id, BlockPos plotPos, int progress, long updatedGameTime) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plotPos, "plotPos");
            if (progress < 0 || progress >= 10) {
                throw new IllegalArgumentException("progress must be in [0, 10)");
            }
        }
    }
}
