package com.firedoge.px4mc.minecraft.scene;

import java.util.Objects;
import java.util.UUID;

public record PhysicsObjectId(UUID value) {
    public PhysicsObjectId {
        Objects.requireNonNull(value, "value");
    }

    public static PhysicsObjectId random() {
        return new PhysicsObjectId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
