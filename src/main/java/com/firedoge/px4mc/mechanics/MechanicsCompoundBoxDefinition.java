package com.firedoge.px4mc.mechanics;

import java.util.List;
import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsBoxCollider;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;

public record MechanicsCompoundBoxDefinition(
        PhysicsPose pose,
        List<PhysicsBoxCollider> boxes,
        PhysicsVector halfExtents,
        float mass,
        MechanicsBodyRole role
) {
    public MechanicsCompoundBoxDefinition {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        Objects.requireNonNull(halfExtents, "halfExtents");
        boxes = List.copyOf(boxes);
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("Compound boxes must not be empty");
        }
        for (PhysicsBoxCollider box : boxes) {
            Objects.requireNonNull(box, "box");
        }
        if (halfExtents.x() <= 0.0D || halfExtents.y() <= 0.0D || halfExtents.z() <= 0.0D) {
            throw new IllegalArgumentException("Compound aggregate half extents must be positive");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Compound box mass must be positive");
        }
        if (role == null) {
            role = MechanicsBodyRole.GAMEPLAY;
        }
    }

    public static MechanicsCompoundBoxDefinition gameplayDynamicCompoundBox(
            PhysicsPose pose,
            List<PhysicsBoxCollider> boxes,
            PhysicsVector halfExtents,
            float mass
    ) {
        return new MechanicsCompoundBoxDefinition(pose, boxes, halfExtents, mass, MechanicsBodyRole.GAMEPLAY);
    }
}
