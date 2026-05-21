package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Objects;
import java.util.UUID;

public record SubLevelId(UUID value) {
    public SubLevelId {
        Objects.requireNonNull(value, "value");
    }

    public static SubLevelId random() {
        return new SubLevelId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
