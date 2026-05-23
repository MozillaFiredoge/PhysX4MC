package com.firedoge.px4mc.minecraft.sublevel;

import net.minecraft.world.level.ChunkPos;

public final class SubLevelPlotAllocator {
    static final int PLOT_CHUNK_ORIGIN_X = 1_000_000;
    static final int PLOT_CHUNK_ORIGIN_Z = 1_000_000;
    static final int PLOT_GRID_WIDTH = 1024;
    static final int PLOT_SLOT_CHUNK_SPAN = 4;

    private long nextPlotId;

    public synchronized SubLevelPlot allocate(SubLevelBounds bounds, int minSection, int sectionCount) {
        if (nextPlotId >= (long) PLOT_GRID_WIDTH * PLOT_GRID_WIDTH) {
            throw new IllegalStateException("Sublevel plot grid is full");
        }
        if (sectionCount <= 0) {
            throw new IllegalArgumentException("sectionCount must be positive");
        }

        long id = nextPlotId++;
        int requiredX = bounds.maxSourcePos().getX() - bounds.sourceOrigin().getX() + 1;
        int requiredY = bounds.maxSourcePos().getY() - bounds.sourceOrigin().getY() + 1;
        int requiredZ = bounds.maxSourcePos().getZ() - bounds.sourceOrigin().getZ() + 1;
        int slotBlockSpan = plotSlotBlockSpan();
        int sectionBlockSpan = sectionCount * SubLevelSectionStorage.SECTION_SIZE;
        if (requiredX <= 0 || requiredX > slotBlockSpan
                || requiredZ <= 0 || requiredZ > slotBlockSpan
                || requiredY <= 0 || requiredY > sectionBlockSpan) {
            throw new IllegalArgumentException("Sublevel bounds do not fit in the reserved plot slot");
        }
        int localX = (int) (id % PLOT_GRID_WIDTH);
        int localZ = (int) (id / PLOT_GRID_WIDTH);
        int plotX = PLOT_CHUNK_ORIGIN_X + localX * PLOT_SLOT_CHUNK_SPAN;
        int plotZ = PLOT_CHUNK_ORIGIN_Z + localZ * PLOT_SLOT_CHUNK_SPAN;
        ChunkPos originChunk = new ChunkPos(plotX, plotZ);
        return SubLevelPlot.sections(new SubLevelPlotId(id), originChunk, minSection, PLOT_SLOT_CHUNK_SPAN, sectionCount);
    }

    public boolean inBounds(ChunkPos chunkPos) {
        return inBounds(chunkPos.x, chunkPos.z);
    }

    public boolean inBounds(int chunkX, int chunkZ) {
        return chunkX >= PLOT_CHUNK_ORIGIN_X
                && chunkX < PLOT_CHUNK_ORIGIN_X + PLOT_GRID_WIDTH * PLOT_SLOT_CHUNK_SPAN
                && chunkZ >= PLOT_CHUNK_ORIGIN_Z
                && chunkZ < PLOT_CHUNK_ORIGIN_Z + PLOT_GRID_WIDTH * PLOT_SLOT_CHUNK_SPAN;
    }

    static int plotSlotBlockSpan() {
        return PLOT_SLOT_CHUNK_SPAN * SubLevelSectionStorage.SECTION_SIZE;
    }
}
