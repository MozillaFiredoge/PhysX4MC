package com.firedoge.px4mc.backend.physx;

import java.util.Objects;

public record PhysXMaterial(long nativeHandle, com.firedoge.px4mc.api.PhysicsMaterial definition) {
    public PhysXMaterial {
        Objects.requireNonNull(definition, "definition");
    }
}
