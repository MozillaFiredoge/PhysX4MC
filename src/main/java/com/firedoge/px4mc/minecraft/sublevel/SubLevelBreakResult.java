package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Objects;

public record SubLevelBreakResult(
        SubLevelPickResult pick,
        boolean removedSubLevel,
        int remainingBlocks,
        int dirtyBlocks,
        int removedVisuals
) {
    public SubLevelBreakResult {
        Objects.requireNonNull(pick, "pick");
        if (remainingBlocks < 0) {
            throw new IllegalArgumentException("remainingBlocks must not be negative");
        }
        if (dirtyBlocks < 0) {
            throw new IllegalArgumentException("dirtyBlocks must not be negative");
        }
        if (removedVisuals < 0) {
            throw new IllegalArgumentException("removedVisuals must not be negative");
        }
    }
}
