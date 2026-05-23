package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public record SubLevelPlot(
        SubLevelPlotId id,
        ChunkPos originChunk,
        int sectionY,
        int chunkSpan,
        int sectionSpan
) {
    public SubLevelPlot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(originChunk, "originChunk");
        if (chunkSpan <= 0) {
            throw new IllegalArgumentException("chunkSpan must be positive");
        }
        if (sectionSpan <= 0) {
            throw new IllegalArgumentException("sectionSpan must be positive");
        }
    }

    public static SubLevelPlot singleSection(SubLevelPlotId id, ChunkPos originChunk, int sectionY) {
        return new SubLevelPlot(id, originChunk, sectionY, 1, 1);
    }

    public static SubLevelPlot sections(SubLevelPlotId id, ChunkPos originChunk, int sectionY, int chunkSpan, int sectionSpan) {
        return new SubLevelPlot(id, originChunk, sectionY, chunkSpan, sectionSpan);
    }

    public BlockPos toPlotBlockPos(BlockPos sectionLocalPos) {
        requireLocal(sectionLocalPos);
        return new BlockPos(
                originChunk.getMinBlockX() + sectionLocalPos.getX(),
                minPlotY() + sectionLocalPos.getY(),
                originChunk.getMinBlockZ() + sectionLocalPos.getZ()
        );
    }

    public BlockPos toSectionLocalPos(BlockPos plotBlockPos) {
        Objects.requireNonNull(plotBlockPos, "plotBlockPos");
        BlockPos local = new BlockPos(
                plotBlockPos.getX() - originChunk.getMinBlockX(),
                plotBlockPos.getY() - minPlotY(),
                plotBlockPos.getZ() - originChunk.getMinBlockZ()
        );
        requireLocal(local);
        if (!containsLocalPos(local)) {
            throw new IllegalArgumentException("plot block position is outside plot " + describe() + ": " + describeBlockPos(plotBlockPos));
        }
        return local;
    }

    public boolean containsPlotBlockPos(BlockPos plotBlockPos) {
        Objects.requireNonNull(plotBlockPos, "plotBlockPos");
        return plotBlockPos.getX() >= minPlotX() && plotBlockPos.getX() <= maxPlotX()
                && plotBlockPos.getY() >= minPlotY() && plotBlockPos.getY() <= maxPlotY()
                && plotBlockPos.getZ() >= minPlotZ() && plotBlockPos.getZ() <= maxPlotZ();
    }

    public boolean containsChunk(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return chunkPos.x >= originChunk.x
                && chunkPos.x < originChunk.x + chunkSpan
                && chunkPos.z >= originChunk.z
                && chunkPos.z < originChunk.z + chunkSpan;
    }

    public List<ChunkPos> chunkPositions() {
        List<ChunkPos> chunks = new ArrayList<>(chunkSpan * chunkSpan);
        for (int z = 0; z < chunkSpan; z++) {
            for (int x = 0; x < chunkSpan; x++) {
                chunks.add(new ChunkPos(originChunk.x + x, originChunk.z + z));
            }
        }
        return List.copyOf(chunks);
    }

    public int minPlotX() {
        return originChunk.getMinBlockX();
    }

    public int minPlotY() {
        return sectionY * SubLevelSectionStorage.SECTION_SIZE;
    }

    public int minPlotZ() {
        return originChunk.getMinBlockZ();
    }

    public int maxPlotX() {
        return minPlotX() + chunkSpan * SubLevelSectionStorage.SECTION_SIZE - 1;
    }

    public int maxPlotY() {
        return minPlotY() + sectionSpan * SubLevelSectionStorage.SECTION_SIZE - 1;
    }

    public int maxPlotZ() {
        return minPlotZ() + chunkSpan * SubLevelSectionStorage.SECTION_SIZE - 1;
    }

    public boolean containsLocalPos(BlockPos localPos) {
        Objects.requireNonNull(localPos, "localPos");
        return localPos.getX() >= 0 && localPos.getX() < chunkSpan * SubLevelSectionStorage.SECTION_SIZE
                && localPos.getY() >= 0 && localPos.getY() < sectionSpan * SubLevelSectionStorage.SECTION_SIZE
                && localPos.getZ() >= 0 && localPos.getZ() < chunkSpan * SubLevelSectionStorage.SECTION_SIZE;
    }

    public String describe() {
        return id + "@chunk=" + originChunk.x + "," + originChunk.z
                + ",sectionY=" + sectionY
                + ",chunkSpan=" + chunkSpan
                + ",sectionSpan=" + sectionSpan;
    }

    private static void requireLocal(BlockPos pos) {
        if (!SubLevelSectionStorage.isValidLocal(pos)) {
            throw new IllegalArgumentException("plot local position must be non-negative: " + describeBlockPos(pos));
        }
    }

    private static String describeBlockPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
