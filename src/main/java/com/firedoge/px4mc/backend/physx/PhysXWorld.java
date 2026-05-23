package com.firedoge.px4mc.backend.physx;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.firedoge.px4mc.api.PhysicsBody;
import com.firedoge.px4mc.api.PhysicsBoxCollider;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsShape;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.api.PhysicsWorld;
import com.firedoge.px4mc.api.PhysicsWorldConfig;
import com.firedoge.px4mc.api.RigidBodyDefinition;

public final class PhysXWorld implements PhysicsWorld {
    private final long nativeHandle;
    private final PhysicsWorldConfig config;
    private final List<PhysXBody> bodies = new CopyOnWriteArrayList<>();
    private final List<PhysXShape> shapes = new CopyOnWriteArrayList<>();
    private boolean closed;

    PhysXWorld(long nativeHandle, PhysicsWorldConfig config) {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("PhysX returned a null world handle");
        }
        this.nativeHandle = nativeHandle;
        this.config = Objects.requireNonNull(config, "config");
    }

    public long nativeHandle() {
        return nativeHandle;
    }

    public PhysicsWorldConfig config() {
        return config;
    }

    @Override
    public String backendId() {
        return PhysXBackend.ID;
    }

    @Override
    public PhysicsShape createBoxShape(float halfExtentX, float halfExtentY, float halfExtentZ) {
        ensureOpen();
        if (halfExtentX <= 0.0F || halfExtentY <= 0.0F || halfExtentZ <= 0.0F) {
            throw new IllegalArgumentException("Box half extents must be positive");
        }
        long handle = PhysXNative.nativeCreateBoxShape(nativeHandle, halfExtentX, halfExtentY, halfExtentZ);
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a box shape");
        }
        PhysXShape shape = new PhysXShape(this, PhysicsShape.ShapeType.BOX, handle);
        shapes.add(shape);
        return shape;
    }

    @Override
    public PhysicsBody createStaticPlane(PhysicsVector normal, double distance) {
        ensureOpen();
        Objects.requireNonNull(normal, "normal");
        long handle = PhysXNative.nativeCreateStaticPlane(nativeHandle, normal.x(), normal.y(), normal.z(), distance);
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a static plane");
        }
        PhysXBody body = new PhysXBody(this, handle, PhysicsPose.IDENTITY);
        bodies.add(body);
        return body;
    }

    @Override
    public PhysicsBody createBody(RigidBodyDefinition definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "definition");
        if (!(definition.shape() instanceof PhysXShape shape)) {
            throw new IllegalArgumentException("PhysX worlds can only create bodies from PhysX shapes");
        }
        if (!shape.belongsTo(this)) {
            throw new IllegalArgumentException("PhysX shapes cannot be shared across worlds");
        }
        shape.ensureOpen();

        PhysicsPose pose = definition.initialPose();
        long handle = switch (definition.type()) {
            case STATIC -> PhysXNative.nativeCreateStaticBody(
                    nativeHandle,
                    shape.nativeHandle(),
                    pose.position().x(),
                    pose.position().y(),
                    pose.position().z(),
                    pose.rotation().x(),
                    pose.rotation().y(),
                    pose.rotation().z(),
                    pose.rotation().w()
            );
            case DYNAMIC -> PhysXNative.nativeCreateDynamicBody(
                    nativeHandle,
                    shape.nativeHandle(),
                    pose.position().x(),
                    pose.position().y(),
                    pose.position().z(),
                    pose.rotation().x(),
                    pose.rotation().y(),
                    pose.rotation().z(),
                    pose.rotation().w(),
                    definition.mass()
            );
            case KINEMATIC -> throw new UnsupportedOperationException("Kinematic PhysX bodies are not implemented yet");
        };
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a " + definition.type() + " body");
        }
        PhysXBody body = new PhysXBody(this, handle, pose);
        bodies.add(body);
        return body;
    }

    @Override
    public PhysicsBody createDynamicCompoundBoxBody(PhysicsPose pose, List<PhysicsBoxCollider> boxes, float mass) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(boxes, "boxes");
        if (boxes.isEmpty()) {
            throw new IllegalArgumentException("Compound boxes must not be empty");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Mass must be positive");
        }

        double[] packedBoxes = new double[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            PhysicsBoxCollider box = Objects.requireNonNull(boxes.get(i), "box");
            int offset = i * 6;
            packedBoxes[offset] = box.center().x();
            packedBoxes[offset + 1] = box.center().y();
            packedBoxes[offset + 2] = box.center().z();
            packedBoxes[offset + 3] = box.halfExtents().x();
            packedBoxes[offset + 4] = box.halfExtents().y();
            packedBoxes[offset + 5] = box.halfExtents().z();
        }

        long handle = PhysXNative.nativeCreateDynamicCompoundBoxBody(
                nativeHandle,
                packedBoxes,
                boxes.size(),
                pose.position().x(),
                pose.position().y(),
                pose.position().z(),
                pose.rotation().x(),
                pose.rotation().y(),
                pose.rotation().z(),
                pose.rotation().w(),
                mass
        );
        if (handle == 0L) {
            throw new IllegalStateException("PhysX failed to create a dynamic compound box body");
        }
        PhysXBody body = new PhysXBody(this, handle, pose);
        bodies.add(body);
        return body;
    }

    @Override
    public void destroyBody(PhysicsBody body) {
        Objects.requireNonNull(body, "body");
        if (!(body instanceof PhysXBody physXBody) || !physXBody.belongsTo(this)) {
            throw new IllegalArgumentException("Body does not belong to this PhysX world");
        }
        physXBody.close();
    }

    @Override
    public void step(float deltaSeconds) {
        ensureOpen();
        if (deltaSeconds <= 0.0F) {
            return;
        }
        PhysXNative.nativeStepWorld(nativeHandle, deltaSeconds);
    }

    @Override
    public boolean gpuDynamicsEnabled() {
        return !closed && PhysXNative.nativeIsWorldGpuDynamicsEnabled(nativeHandle);
    }

    @Override
    public String gpuDynamicsStatus() {
        return closed ? "closed" : PhysXNative.nativeGetWorldGpuDynamicsStatus(nativeHandle);
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
        for (PhysXBody body : bodies) {
            body.close();
        }
        for (PhysXShape shape : shapes) {
            shape.close();
        }
        PhysXNative.nativeDestroyWorld(nativeHandle);
    }

    void forgetBody(PhysXBody body) {
        bodies.remove(body);
    }

    void forgetShape(PhysXShape shape) {
        shapes.remove(shape);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics world is closed");
        }
    }
}
