package com.firedoge.px4mc.minecraft.sublevel;

public record SubLevelPlotId(long value) {
    public SubLevelPlotId {
        if (value < 0L) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }

    @Override
    public String toString() {
        return "plot-" + value;
    }
}
