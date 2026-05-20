package com.firedoge.px4mc.backend.physx;

public record PhysXHandle(long value) {
    public boolean isValid() {
        return value != 0L;
    }
}
