package com.firedoge.px4mc.mechanics;

import java.util.Objects;
import java.util.UUID;

public record MechanicsDebugProxy(MechanicsBodyId bodyId, UUID entityId, boolean created) {
    public MechanicsDebugProxy {
        Objects.requireNonNull(bodyId, "bodyId");
        Objects.requireNonNull(entityId, "entityId");
    }
}
