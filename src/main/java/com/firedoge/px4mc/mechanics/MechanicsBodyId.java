package com.firedoge.px4mc.mechanics;

import java.util.Objects;
import java.util.UUID;

public record MechanicsBodyId(UUID value) {
    public MechanicsBodyId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
