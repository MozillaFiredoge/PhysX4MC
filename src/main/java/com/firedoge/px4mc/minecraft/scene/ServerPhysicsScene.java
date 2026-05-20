package com.firedoge.px4mc.minecraft.scene;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.api.PhysicsBackend;
import com.firedoge.px4mc.api.PhysicsBody;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsShape;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.api.PhysicsWorld;
import com.firedoge.px4mc.api.PhysicsWorldConfig;
import com.firedoge.px4mc.api.RigidBodyDefinition;

public final class ServerPhysicsScene implements AutoCloseable {
    private final String sceneKey;
    private final PhysicsWorld world;
    private final Map<PhysicsObjectId, RigidPhysicsObject> objects = new LinkedHashMap<>();
    private boolean closed;

    public ServerPhysicsScene(String sceneKey, PhysicsBackend backend, PhysicsWorldConfig config) {
        this.sceneKey = Objects.requireNonNull(sceneKey, "sceneKey");
        Objects.requireNonNull(backend, "backend");
        this.world = backend.createWorld(Objects.requireNonNull(config, "config"));
    }

    public String sceneKey() {
        return sceneKey;
    }

    public int objectCount() {
        return objects.size();
    }

    public PhysicsObject createStaticPlane(PhysicsVector normal, double distance) {
        ensureOpen();
        PhysicsBody body = world.createStaticPlane(normal, distance);
        return addObject(PhysicsObjectType.STATIC_PLANE, body, null);
    }

    public PhysicsObject createStaticBox(float halfExtentX, float halfExtentY, float halfExtentZ, PhysicsPose pose) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        PhysicsShape shape = world.createBoxShape(halfExtentX, halfExtentY, halfExtentZ);
        try {
            PhysicsBody body = world.createBody(RigidBodyDefinition.staticBody(pose, shape));
            return addObject(PhysicsObjectType.STATIC_BLOCK, body, shape);
        } catch (RuntimeException exception) {
            shape.close();
            throw exception;
        }
    }

    public PhysicsObject createDynamicBox(float halfExtentX, float halfExtentY, float halfExtentZ, PhysicsPose pose, float mass) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        PhysicsShape shape = world.createBoxShape(halfExtentX, halfExtentY, halfExtentZ);
        try {
            PhysicsBody body = world.createBody(RigidBodyDefinition.dynamic(pose, shape, mass));
            return addObject(PhysicsObjectType.DYNAMIC_BOX, body, shape);
        } catch (RuntimeException exception) {
            shape.close();
            throw exception;
        }
    }

    public Optional<PhysicsObject> object(PhysicsObjectId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(objects.get(id));
    }

    public Collection<PhysicsObjectSnapshot> snapshots() {
        return objects.values().stream()
                .map(PhysicsObject::snapshot)
                .toList();
    }

    public void clearObjects() {
        for (RigidPhysicsObject object : List.copyOf(objects.values())) {
            object.close();
        }
        objects.clear();
    }

    public boolean removeObject(PhysicsObjectId id) {
        Objects.requireNonNull(id, "id");
        RigidPhysicsObject object = objects.get(id);
        if (object == null) {
            return false;
        }
        object.close();
        return true;
    }

    public void step(float deltaSeconds) {
        ensureOpen();
        world.step(deltaSeconds);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearObjects();
        world.close();
    }

    void forgetObject(PhysicsObjectId id) {
        objects.remove(id);
    }

    private PhysicsObject addObject(PhysicsObjectType type, PhysicsBody body, PhysicsShape ownedShape) {
        PhysicsObjectId id = PhysicsObjectId.random();
        RigidPhysicsObject object = new RigidPhysicsObject(this, id, type, body, ownedShape);
        objects.put(id, object);
        return object;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Physics scene is closed: " + sceneKey);
        }
    }
}
