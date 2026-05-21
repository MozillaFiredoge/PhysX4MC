package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;

public final class SubLevelTransform {
    private final PhysicsPose pose;

    private SubLevelTransform(PhysicsPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
    }

    public static SubLevelTransform from(MechanicsBodySnapshot body) {
        Objects.requireNonNull(body, "body");
        return new SubLevelTransform(body.pose());
    }

    public PhysicsPose pose() {
        return pose;
    }

    public PhysicsVector worldToLocal(PhysicsVector worldPosition) {
        Objects.requireNonNull(worldPosition, "worldPosition");
        PhysicsVector position = pose.position();
        return inverseRotate(pose.rotation(), new PhysicsVector(
                worldPosition.x() - position.x(),
                worldPosition.y() - position.y(),
                worldPosition.z() - position.z()
        ));
    }

    public PhysicsVector worldDirectionToLocal(PhysicsVector worldDirection) {
        Objects.requireNonNull(worldDirection, "worldDirection");
        return inverseRotate(pose.rotation(), worldDirection);
    }

    public PhysicsVector localToWorld(PhysicsVector localPosition) {
        Objects.requireNonNull(localPosition, "localPosition");
        PhysicsVector rotated = rotate(pose.rotation(), localPosition);
        PhysicsVector position = pose.position();
        return new PhysicsVector(
                position.x() + rotated.x(),
                position.y() + rotated.y(),
                position.z() + rotated.z()
        );
    }

    private static PhysicsVector inverseRotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        PhysicsQuaternion normalized = normalize(rotation);
        return rotate(new PhysicsQuaternion(-normalized.x(), -normalized.y(), -normalized.z(), normalized.w()), vector);
    }

    private static PhysicsVector rotate(PhysicsQuaternion rotation, PhysicsVector vector) {
        PhysicsQuaternion q = normalize(rotation);
        double ux = q.x();
        double uy = q.y();
        double uz = q.z();
        double s = q.w();
        double vx = vector.x();
        double vy = vector.y();
        double vz = vector.z();

        double dotUv = ux * vx + uy * vy + uz * vz;
        double dotUu = ux * ux + uy * uy + uz * uz;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        double scale = s * s - dotUu;

        return new PhysicsVector(
                2.0D * dotUv * ux + scale * vx + 2.0D * s * crossX,
                2.0D * dotUv * uy + scale * vy + 2.0D * s * crossY,
                2.0D * dotUv * uz + scale * vz + 2.0D * s * crossZ
        );
    }

    private static PhysicsQuaternion normalize(PhysicsQuaternion rotation) {
        Objects.requireNonNull(rotation, "rotation");
        double length = Math.sqrt(
                rotation.x() * rotation.x()
                        + rotation.y() * rotation.y()
                        + rotation.z() * rotation.z()
                        + rotation.w() * rotation.w()
        );
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            return PhysicsQuaternion.IDENTITY;
        }
        return new PhysicsQuaternion(
                rotation.x() / length,
                rotation.y() / length,
                rotation.z() / length,
                rotation.w() / length
        );
    }
}
