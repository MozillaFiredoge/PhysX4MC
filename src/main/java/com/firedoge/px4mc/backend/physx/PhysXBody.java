package com.firedoge.px4mc.backend.physx;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsBody;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;

public final class PhysXBody implements PhysicsBody {
    private final PhysXWorld world;
    private final long nativeHandle;
    private PhysicsPose pose;
    private PhysicsVector linearVelocity = PhysicsVector.ZERO;
    private boolean closed;

    PhysXBody(PhysXWorld world, long nativeHandle, PhysicsPose pose) {
        this.world = Objects.requireNonNull(world, "world");
        this.nativeHandle = nativeHandle;
        this.pose = Objects.requireNonNull(pose, "pose");
    }

    @Override
    public long nativeHandle() {
        return nativeHandle;
    }

    @Override
    public PhysicsPose pose() {
        if (!closed && nativeHandle != 0L && PhysXNative.isLoaded()) {
            double[] poseData = new double[7];
            if (PhysXNative.nativeGetBodyPose(nativeHandle, poseData)) {
                pose = new PhysicsPose(
                        new PhysicsVector(poseData[0], poseData[1], poseData[2]),
                        new PhysicsQuaternion(poseData[3], poseData[4], poseData[5], poseData[6])
                );
            }
        }
        return pose;
    }

    @Override
    public void setPose(PhysicsPose pose) {
        ensureOpen();
        this.pose = Objects.requireNonNull(pose, "pose");
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.nativeSetBodyPose(
                    nativeHandle,
                    pose.position().x(),
                    pose.position().y(),
                    pose.position().z(),
                    pose.rotation().x(),
                    pose.rotation().y(),
                    pose.rotation().z(),
                    pose.rotation().w()
            );
        }
    }

    @Override
    public PhysicsVector linearVelocity() {
        return linearVelocity;
    }

    @Override
    public void setLinearVelocity(PhysicsVector velocity) {
        ensureOpen();
        this.linearVelocity = Objects.requireNonNull(velocity, "velocity");
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.nativeSetLinearVelocity(nativeHandle, velocity.x(), velocity.y(), velocity.z());
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (nativeHandle != 0L && PhysXNative.isLoaded()) {
            PhysXNative.nativeDestroyBody(nativeHandle);
        }
        world.forgetBody(this);
    }

    boolean belongsTo(PhysXWorld world) {
        return this.world == world;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics body is closed");
        }
    }
}
