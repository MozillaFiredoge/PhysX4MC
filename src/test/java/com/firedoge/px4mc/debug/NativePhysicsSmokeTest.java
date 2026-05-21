package com.firedoge.px4mc.debug;

import com.firedoge.px4mc.api.PhysicsBody;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsShape;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.api.PhysicsWorld;
import com.firedoge.px4mc.api.PhysicsWorldConfig;
import com.firedoge.px4mc.api.RigidBodyDefinition;
import com.firedoge.px4mc.backend.physx.PhysXBackend;
import com.firedoge.px4mc.minecraft.scene.PhysicsObject;
import com.firedoge.px4mc.minecraft.scene.PhysicsObjectId;
import com.firedoge.px4mc.minecraft.scene.PhysicsObjectSnapshot;
import com.firedoge.px4mc.minecraft.scene.PhysicsSceneManager;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsScene;

public final class NativePhysicsSmokeTest {
    private NativePhysicsSmokeTest() {
    }

    public static void main(String[] args) {
        runMinimalLoop();
        runLifecycleChecks();
        runGpuDynamicsRequestCheck();
        runCcdChecks();
        runSceneLayerChecks();
        System.out.println("Native physics lifecycle checks passed");
        System.out.println("Physics scene layer checks passed");
    }

    private static void runMinimalLoop() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1))) {
            try (PhysicsBody ignoredGround = world.createStaticPlane(new PhysicsVector(0.0D, 1.0D, 0.0D), 0.0D);
                 PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
                 PhysicsBody box = world.createBody(RigidBodyDefinition.dynamic(
                         new PhysicsPose(new PhysicsVector(0.0D, 5.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                         boxShape,
                         1.0F
                 ))) {
                double initialY = box.pose().position().y();
                for (int i = 0; i < 240; i++) {
                    world.step(1.0F / 60.0F);
                }

                PhysicsPose finalPose = box.pose();
                double finalY = finalPose.position().y();
                if (finalY >= initialY) {
                    throw new IllegalStateException("Expected the box to fall; initialY=" + initialY + ", finalY=" + finalY);
                }
                if (finalY < 0.45D || finalY > 0.75D) {
                    throw new IllegalStateException("Expected the box to rest near y=0.5; finalY=" + finalY);
                }

                System.out.printf("Native physics smoke test passed: initialY=%.4f finalY=%.4f%n", initialY, finalY);
            }
        }
    }

    private static void runLifecycleChecks() {
        PhysXBackend backend = new PhysXBackend();
        PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1));
        PhysicsBody ground = world.createStaticPlane(new PhysicsVector(0.0D, 1.0D, 0.0D), 0.0D);
        PhysicsShape sharedShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
        PhysicsBody first = world.createBody(RigidBodyDefinition.dynamic(
                new PhysicsPose(new PhysicsVector(-1.0D, 4.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                sharedShape,
                1.0F
        ));
        PhysicsBody second = world.createBody(RigidBodyDefinition.dynamic(
                new PhysicsPose(new PhysicsVector(1.0D, 6.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                sharedShape,
                1.0F
        ));

        sharedShape.close();
        assertTrue(sharedShape.isClosed(), "Shape should report closed after close()");
        for (int i = 0; i < 120; i++) {
            world.step(1.0F / 60.0F);
        }
        assertTrue(first.pose().position().y() < 4.0D, "First body should keep simulating after shared shape close");
        assertTrue(second.pose().position().y() < 6.0D, "Second body should keep simulating after shared shape close");

        expectThrows(() -> world.createBody(RigidBodyDefinition.dynamic(
                new PhysicsPose(new PhysicsVector(0.0D, 10.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                sharedShape,
                1.0F
        )), "Closed shape should not create new bodies");

        first.close();
        first.close();
        assertTrue(first.isClosed(), "Body close should be idempotent");

        world.close();
        world.close();
        assertTrue(world.isClosed(), "World close should be idempotent");
        assertTrue(ground.isClosed(), "World close should close remaining static plane bodies");
        assertTrue(second.isClosed(), "World close should close remaining dynamic bodies");

        expectThrows(() -> world.step(1.0F / 60.0F), "Closed world should reject simulation steps");
    }

    private static void runGpuDynamicsRequestCheck() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1, true))) {
            try (PhysicsShape boxShape = world.createBoxShape(0.5F, 0.5F, 0.5F);
                 PhysicsBody box = world.createBody(RigidBodyDefinition.dynamic(
                         new PhysicsPose(new PhysicsVector(0.0D, 3.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                         boxShape,
                         1.0F
                 ))) {
                double initialY = box.pose().position().y();
                for (int i = 0; i < 10; i++) {
                    world.step(1.0F / 60.0F);
                }
                assertTrue(box.pose().position().y() < initialY, "GPU-requested world should simulate or fall back to CPU");
                System.out.println("GPU dynamics request check passed: enabled=" + world.gpuDynamicsEnabled()
                        + " status=" + world.gpuDynamicsStatus());
            }
        }
    }

    private static void runCcdChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsWorld world = backend.createWorld(new PhysicsWorldConfig(PhysicsVector.ZERO, 1.0F / 20.0F, 1));
             PhysicsShape wallShape = world.createBoxShape(0.05F, 2.0F, 2.0F);
             PhysicsShape projectileShape = world.createBoxShape(0.25F, 0.25F, 0.25F);
             PhysicsBody ignoredWall = world.createBody(RigidBodyDefinition.staticBody(
                     new PhysicsPose(new PhysicsVector(0.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     wallShape
             ));
             PhysicsBody projectile = world.createBody(RigidBodyDefinition.dynamic(
                     new PhysicsPose(new PhysicsVector(-3.0D, 0.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                     projectileShape,
                     1.0F
             ))) {
            projectile.setLinearVelocity(new PhysicsVector(200.0D, 0.0D, 0.0D));
            world.step(1.0F / 20.0F);
            PhysicsVector position = projectile.pose().position();
            assertTrue(position.x() < 0.0D, "CCD should prevent the projectile from crossing the thin wall; x=" + position.x());
            System.out.printf("Native CCD check passed: projectileX=%.4f%n", position.x());
        }
    }

    private static void runSceneLayerChecks() {
        PhysXBackend backend = new PhysXBackend();
        try (PhysicsSceneManager manager = new PhysicsSceneManager()) {
            ServerPhysicsScene scene = manager.createScene(
                    "minecraft:overworld",
                    backend,
                    new PhysicsWorldConfig(PhysicsVector.MC_GRAVITY, 1.0F / 60.0F, 1)
            );
            PhysicsObject ground = scene.createStaticPlane(new PhysicsVector(0.0D, 1.0D, 0.0D), 0.0D);
            PhysicsObject box = scene.createDynamicBox(
                    0.5F,
                    0.5F,
                    0.5F,
                    new PhysicsPose(new PhysicsVector(0.0D, 4.0D, 0.0D), PhysicsQuaternion.IDENTITY),
                    1.0F
            );
            PhysicsObjectId boxId = box.id();

            assertTrue(scene.objectCount() == 2, "Scene should track the static plane and dynamic box");
            assertTrue(scene.object(boxId).isPresent(), "Scene should look up objects by stable id");

            double initialY = box.snapshot().pose().position().y();
            for (int i = 0; i < 180; i++) {
                scene.step(1.0F / 60.0F);
            }
            PhysicsObjectSnapshot snapshot = scene.object(boxId)
                    .map(PhysicsObject::snapshot)
                    .orElseThrow();
            assertTrue(snapshot.pose().position().y() < initialY, "Scene object snapshot should reflect simulated pose");
            assertTrue(snapshot.pose().position().y() > 0.45D, "Scene object should rest above the ground plane");

            assertTrue(scene.removeObject(boxId), "Scene should remove object by stable id");
            assertTrue(scene.object(boxId).isEmpty(), "Removed object id should no longer resolve");
            assertTrue(box.isClosed(), "Removing a scene object should close the object");

            manager.closeScene(scene.sceneKey());
            assertTrue(scene.isClosed(), "Closing scene through manager should close the scene");
            assertTrue(ground.isClosed(), "Closing scene should close remaining objects");
            expectThrows(() -> scene.step(1.0F / 60.0F), "Closed scene should reject steps");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void expectThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }
}
