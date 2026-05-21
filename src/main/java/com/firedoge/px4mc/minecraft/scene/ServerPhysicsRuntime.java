package com.firedoge.px4mc.minecraft.scene;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.px4mc.api.PhysicsBackend;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.api.PhysicsWorldConfig;
import com.firedoge.px4mc.config.PhysXConfig;
import com.firedoge.px4mc.physics.PhysicsManager;
import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ServerPhysicsRuntime implements AutoCloseable {
    public static final ServerPhysicsRuntime INSTANCE = new ServerPhysicsRuntime();
    private static final int ACTIVE_OBJECT_TERRAIN_CHUNK_RADIUS = 1;
    private static final int SPAWN_TERRAIN_CHUNK_RADIUS = 1;
    private static final int MAX_TERRAIN_CHUNK_BUILDS_PER_TICK = 1;
    private static final int MAX_STRESS_GRID_OBJECTS = 20_000;
    private static final BlockState DEBUG_PROXY_BLOCK = Blocks.LIME_STAINED_GLASS.defaultBlockState();
    private static final MethodHandle DISPLAY_SET_TRANSFORMATION = findDisplaySetTransformation();

    private final PhysicsSceneManager scenes = new PhysicsSceneManager();
    private final Map<String, SceneState> states = new LinkedHashMap<>();
    private final Map<PhysicsObjectId, EntityBinding> entityBindings = new LinkedHashMap<>();
    private final Map<String, Map<Long, TerrainCollider>> terrainColliders = new LinkedHashMap<>();
    private final Map<String, Map<Long, Set<Long>>> terrainChunks = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<Long>> terrainBuildQueues = new LinkedHashMap<>();
    private final Map<String, Map<Long, TerrainChunkBuildState>> terrainChunkBuildStates = new LinkedHashMap<>();
    private final Map<String, Integer> activeTerrainScanCursors = new LinkedHashMap<>();
    private long terrainColliderSequence = 1L;
    private int lastTerrainChunkBuildCount;
    private int lastTerrainColliderBuildCount;
    private int lastTerrainPartialColliderBuildCount;
    private long lastTerrainBuildNanos;
    private int debugProxyRecreateCount;
    private int lastDebugProxyRecreateCount;
    private long lastRuntimeTickNanos;
    private long lastQueueActiveNanos;
    private long lastTerrainProcessNanos;
    private long lastStepPhaseNanos;
    private long lastSyncEntitiesNanos;
    private long lastSyncObjectLookupNanos;
    private long lastSyncEntityLookupNanos;
    private long lastSyncRecreateNanos;
    private long lastSyncPoseReadNanos;
    private long lastSyncApplyNanos;
    private int lastActiveSnapshotCount;
    private int lastActiveDynamicCount;
    private int lastActiveTerrainQueuedCount;
    private int lastActiveTerrainSkippedHeightCount;
    private int lastSyncedEntityCount;
    private int lastEntityPoseSyncCount;
    private int lastSyncRemovedBindingCount;
    private int lastSyncMissingEntityCount;
    private int debugProxySyncCursor;

    private ServerPhysicsRuntime() {
    }

    public synchronized ServerPhysicsScene sceneFor(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        String sceneKey = sceneKey(level);
        SceneState state = states.get(sceneKey);
        if (state != null && !state.scene().isClosed()) {
            return state.scene();
        }

        PhysicsBackend backend = PhysicsManager.INSTANCE.backend(PhysXConfig.DEFAULT_BACKEND.get())
                .orElseThrow(() -> new IllegalStateException("Unknown physics backend: " + PhysXConfig.DEFAULT_BACKEND.get()));
        PhysicsWorldConfig config = new PhysicsWorldConfig(
                com.firedoge.px4mc.api.PhysicsVector.MC_GRAVITY,
                PhysXConfig.FIXED_TIME_STEP.get().floatValue(),
                PhysXConfig.MAX_SUB_STEPS.get(),
                PhysXConfig.ENABLE_GPU_DYNAMICS.getAsBoolean()
        );
        ServerPhysicsScene scene = scenes.createScene(sceneKey, backend, config);
        states.put(sceneKey, new SceneState(scene, config.fixedTimeStep(), config.maxSubSteps()));
        return scene;
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (states.isEmpty()) {
            clearLastTickProfiling();
            return;
        }

        long tickStart = System.nanoTime();
        long queueStart = tickStart;
        ActiveObjectTerrainQueueResult activeQueueResult = queueTerrainAroundActiveObjects(server);
        lastQueueActiveNanos = System.nanoTime() - queueStart;
        lastActiveSnapshotCount = activeQueueResult.snapshotCount();
        lastActiveDynamicCount = activeQueueResult.dynamicCount();
        lastActiveTerrainQueuedCount = activeQueueResult.queuedTerrainChunks();
        lastActiveTerrainSkippedHeightCount = activeQueueResult.skippedByHeight();

        long terrainStart = System.nanoTime();
        processTerrainBuildQueue(server);
        lastTerrainProcessNanos = System.nanoTime() - terrainStart;

        long stepStart = System.nanoTime();
        for (SceneState state : List.copyOf(states.values())) {
            state.advance(1.0F / 20.0F);
        }
        lastStepPhaseNanos = System.nanoTime() - stepStart;

        long syncStart = System.nanoTime();
        EntitySyncResult entitySyncResult = syncBoundEntities(server);
        lastSyncEntitiesNanos = System.nanoTime() - syncStart;
        lastSyncedEntityCount = entitySyncResult.processedBindings();
        lastEntityPoseSyncCount = entitySyncResult.poseSyncs();
        lastSyncObjectLookupNanos = entitySyncResult.objectLookupNanos();
        lastSyncEntityLookupNanos = entitySyncResult.entityLookupNanos();
        lastSyncRecreateNanos = entitySyncResult.recreateNanos();
        lastSyncPoseReadNanos = entitySyncResult.poseReadNanos();
        lastSyncApplyNanos = entitySyncResult.applyNanos();
        lastSyncRemovedBindingCount = entitySyncResult.removedBindings();
        lastSyncMissingEntityCount = entitySyncResult.missingEntities();
        lastRuntimeTickNanos = System.nanoTime() - tickStart;
    }

    public synchronized RuntimeStatus status() {
        int objectCount = 0;
        int dynamicBoxCount = 0;
        int gpuDynamicsSceneCount = 0;
        LinkedHashSet<String> gpuDynamicsStatuses = new LinkedHashSet<>();
        long lastStepNanos = 0L;
        for (SceneState state : states.values()) {
            objectCount += state.scene().objectCount();
            if (state.scene().gpuDynamicsEnabled()) {
                gpuDynamicsSceneCount++;
            }
            gpuDynamicsStatuses.add(state.scene().gpuDynamicsStatus());
            for (PhysicsObjectSnapshot snapshot : state.scene().snapshots()) {
                if (snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed()) {
                    dynamicBoxCount++;
                }
            }
            lastStepNanos = Math.max(lastStepNanos, state.lastStepNanos());
        }
        boolean nativeLinked = PhysicsManager.INSTANCE.backend(PhysXConfig.DEFAULT_BACKEND.get())
                .map(PhysicsBackend::isAvailable)
                .orElse(false);
        return new RuntimeStatus(
                states.size(),
                objectCount,
                dynamicBoxCount,
                terrainColliderCount(),
                terrainChunkCount(),
                terrainQueuedChunkCount(),
                terrainChunkStateCount(TerrainChunkBuildStatus.BUILT),
                terrainChunkStateCount(TerrainChunkBuildStatus.DIRTY),
                entityBindings.size(),
                nativeLinked,
                PhysXConfig.ENABLE_GPU_DYNAMICS.getAsBoolean(),
                gpuDynamicsSceneCount,
                describeGpuDynamicsStatuses(gpuDynamicsStatuses),
                lastStepNanos,
                lastTerrainChunkBuildCount,
                lastTerrainColliderBuildCount,
                lastTerrainPartialColliderBuildCount,
                lastTerrainBuildNanos,
                debugProxyRecreateCount,
                lastDebugProxyRecreateCount,
                lastRuntimeTickNanos,
                lastQueueActiveNanos,
                lastTerrainProcessNanos,
                lastStepPhaseNanos,
                lastSyncEntitiesNanos,
                lastSyncObjectLookupNanos,
                lastSyncEntityLookupNanos,
                lastSyncRecreateNanos,
                lastSyncPoseReadNanos,
                lastSyncApplyNanos,
                lastActiveSnapshotCount,
                lastActiveDynamicCount,
                lastActiveTerrainQueuedCount,
                lastActiveTerrainSkippedHeightCount,
                lastSyncedEntityCount,
                lastEntityPoseSyncCount,
                lastSyncRemovedBindingCount,
                lastSyncMissingEntityCount,
                debugProxySyncTransform(),
                debugProxySyncLimit(),
                activeTerrainScanLimit()
        );
    }

    public synchronized List<PhysicsObjectSnapshot> snapshotsFor(ServerLevel level) {
        SceneState state = states.get(sceneKey(level));
        if (state == null) {
            return List.of();
        }
        return List.copyOf(state.scene().snapshots());
    }

    public synchronized int clearAll() {
        int removed = 0;
        entityBindings.clear();
        terrainColliders.clear();
        terrainChunks.clear();
        terrainBuildQueues.clear();
        terrainChunkBuildStates.clear();
        activeTerrainScanCursors.clear();
        terrainColliderSequence = 1L;
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        debugProxySyncCursor = 0;
        clearLastTickProfiling();
        for (SceneState state : states.values()) {
            removed += state.scene().objectCount();
            state.scene().clearObjects();
        }
        return removed;
    }

    public synchronized int clearLevel(ServerLevel level) {
        String sceneKey = sceneKey(level);
        removeBindingsForScene(level, sceneKey);
        terrainColliders.remove(sceneKey);
        terrainChunks.remove(sceneKey);
        terrainBuildQueues.remove(sceneKey);
        terrainChunkBuildStates.remove(sceneKey);
        activeTerrainScanCursors.remove(sceneKey);
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        debugProxySyncCursor = 0;
        clearLastTickProfiling();
        SceneState state = states.get(sceneKey);
        if (state == null) {
            return 0;
        }
        int removed = state.scene().objectCount();
        state.scene().clearObjects();
        return removed;
    }

    public synchronized Optional<ServerPhysicsScene> existingScene(ServerLevel level) {
        SceneState state = states.get(sceneKey(level));
        return state == null ? Optional.empty() : Optional.of(state.scene());
    }

    public synchronized SpawnedDebugBox spawnDebugBox(ServerLevel level, Vec3 position) {
        return spawnDebugBox(level, position, 1.0F, 1.0F);
    }

    public synchronized SpawnedDebugBox spawnDebugBox(ServerLevel level, Vec3 position, float size, float mass) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (size <= 0.0F) {
            throw new IllegalArgumentException("Box size must be positive");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Box mass must be positive");
        }
        ServerPhysicsScene scene = sceneFor(level);
        int queuedTerrainChunks = queueTerrainAround(level, BlockPos.containing(position), SPAWN_TERRAIN_CHUNK_RADIUS);
        float halfExtent = size * 0.5F;
        PhysicsVector halfExtents = new PhysicsVector(halfExtent, halfExtent, halfExtent);
        PhysicsObject box;
        try {
            box = scene.createDynamicBox(
                    halfExtent,
                    halfExtent,
                    halfExtent,
                    new PhysicsPose(new PhysicsVector(position.x(), position.y() + halfExtent + 1.5D, position.z()), PhysicsQuaternion.IDENTITY),
                    mass
            );
        } catch (RuntimeException exception) {
            throw exception;
        }

        Display.BlockDisplay entity = createDebugEntity(level, box, halfExtents);
        if (!level.addFreshEntity(entity)) {
            box.close();
            throw new IllegalStateException("Failed to spawn debug entity for physics object " + box.id());
        }

        EntityBinding binding = new EntityBinding(sceneKey(level), level.dimension(), box.id(), entity.getUUID(), halfExtents);
        entityBindings.put(box.id(), binding);
        return new SpawnedDebugBox(box, entity.getUUID(), queuedTerrainChunks, halfExtents, mass);
    }

    public synchronized StressGridResult spawnStressGrid(
            ServerLevel level,
            Vec3 position,
            int countX,
            int countY,
            int countZ,
            float spacing,
            float size,
            float mass
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (countX <= 0 || countY <= 0 || countZ <= 0) {
            throw new IllegalArgumentException("Stress grid dimensions must be positive");
        }
        int requested = Math.toIntExact((long) countX * countY * countZ);
        if (requested > MAX_STRESS_GRID_OBJECTS) {
            throw new IllegalArgumentException("Stress grid is limited to " + MAX_STRESS_GRID_OBJECTS + " objects");
        }
        if (spacing <= 0.0F) {
            throw new IllegalArgumentException("Stress grid spacing must be positive");
        }
        if (size <= 0.0F) {
            throw new IllegalArgumentException("Box size must be positive");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Box mass must be positive");
        }

        ServerPhysicsScene scene = sceneFor(level);
        int queuedTerrainChunks = queueTerrainAround(level, BlockPos.containing(position), SPAWN_TERRAIN_CHUNK_RADIUS);
        float halfExtent = size * 0.5F;
        PhysicsVector halfExtents = new PhysicsVector(halfExtent, halfExtent, halfExtent);
        List<PhysicsObject> createdObjects = new ArrayList<>(requested);

        double baseX = position.x() - (countX - 1) * spacing * 0.5D;
        double baseY = position.y() + halfExtent + 1.5D;
        double baseZ = position.z() - (countZ - 1) * spacing * 0.5D;
        try {
            for (int y = 0; y < countY; y++) {
                for (int z = 0; z < countZ; z++) {
                    for (int x = 0; x < countX; x++) {
                        PhysicsObject box = scene.createDynamicBox(
                                halfExtent,
                                halfExtent,
                                halfExtent,
                                new PhysicsPose(
                                        new PhysicsVector(
                                                baseX + x * spacing,
                                                baseY + y * spacing,
                                                baseZ + z * spacing
                                        ),
                                        PhysicsQuaternion.IDENTITY
                                ),
                                mass
                        );
                        createdObjects.add(box);
                    }
                }
            }
        } catch (RuntimeException exception) {
            for (PhysicsObject object : createdObjects) {
                object.close();
            }
            throw exception;
        }

        return new StressGridResult(createdObjects.size(), requested, queuedTerrainChunks, halfExtents, spacing, mass);
    }

    public synchronized Optional<VelocityControlResult> setNearestDynamicBoxVelocity(ServerLevel level, Vec3 origin, PhysicsVector velocity, double maxDistance) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(velocity, "velocity");
        if (maxDistance <= 0.0D) {
            throw new IllegalArgumentException("Max distance must be positive");
        }

        SceneState state = states.get(sceneKey(level));
        if (state == null || state.scene().isClosed()) {
            return Optional.empty();
        }

        PhysicsObject nearest = null;
        double nearestDistanceSqr = maxDistance * maxDistance;
        for (PhysicsObjectSnapshot snapshot : state.scene().snapshots()) {
            if (snapshot.type() != PhysicsObjectType.DYNAMIC_BOX || snapshot.closed()) {
                continue;
            }
            PhysicsVector position = snapshot.pose().position();
            double dx = position.x() - origin.x();
            double dy = position.y() - origin.y();
            double dz = position.z() - origin.z();
            double distanceSqr = dx * dx + dy * dy + dz * dz;
            if (distanceSqr <= nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearest = state.scene().object(snapshot.id()).orElse(null);
            }
        }

        if (nearest == null) {
            return Optional.empty();
        }

        PhysicsVector previousVelocity = nearest.linearVelocity();
        nearest.setLinearVelocity(velocity);
        return Optional.of(new VelocityControlResult(
                nearest.id(),
                previousVelocity,
                velocity,
                Math.sqrt(nearestDistanceSqr)
        ));
    }

    public synchronized Optional<RemovedDebugBox> removeDynamicBox(ServerLevel level, PhysicsObjectId objectId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(objectId, "objectId");

        SceneState state = states.get(sceneKey(level));
        if (state == null || state.scene().isClosed()) {
            return Optional.empty();
        }

        Optional<PhysicsObject> maybeObject = state.scene().object(objectId);
        if (maybeObject.isEmpty() || maybeObject.get().type() != PhysicsObjectType.DYNAMIC_BOX) {
            return Optional.empty();
        }

        PhysicsObject object = maybeObject.get();
        PhysicsObjectSnapshot snapshot = object.snapshot();
        UUID entityId = discardBoundEntity(level, objectId);
        if (!state.scene().removeObject(objectId)) {
            return Optional.empty();
        }

        return Optional.of(new RemovedDebugBox(
                snapshot.id(),
                entityId,
                snapshot.pose().position(),
                snapshot.linearVelocity()
        ));
    }

    @Override
    public synchronized void close() {
        entityBindings.clear();
        terrainColliders.clear();
        terrainChunks.clear();
        terrainBuildQueues.clear();
        terrainChunkBuildStates.clear();
        activeTerrainScanCursors.clear();
        terrainColliderSequence = 1L;
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        debugProxySyncCursor = 0;
        clearLastTickProfiling();
        scenes.close();
        states.clear();
    }

    public synchronized void close(MinecraftServer server) {
        discardBoundEntities(server);
        close();
    }

    private static String sceneKey(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        return dimension.location().toString();
    }

    private static int debugProxySyncLimit() {
        return PhysXConfig.DEBUG_PROXY_MAX_SYNCS_PER_TICK.get();
    }

    private static boolean debugProxySyncTransform() {
        return PhysXConfig.DEBUG_PROXY_SYNC_TRANSFORM.getAsBoolean();
    }

    private static int activeTerrainScanLimit() {
        return PhysXConfig.ACTIVE_TERRAIN_MAX_SCANS_PER_TICK.get();
    }

    private static int activeTerrainVerticalMargin() {
        return PhysXConfig.ACTIVE_TERRAIN_VERTICAL_MARGIN.get();
    }

    private void clearLastTickProfiling() {
        lastRuntimeTickNanos = 0L;
        lastQueueActiveNanos = 0L;
        lastTerrainProcessNanos = 0L;
        lastStepPhaseNanos = 0L;
        lastSyncEntitiesNanos = 0L;
        lastSyncObjectLookupNanos = 0L;
        lastSyncEntityLookupNanos = 0L;
        lastSyncRecreateNanos = 0L;
        lastSyncPoseReadNanos = 0L;
        lastSyncApplyNanos = 0L;
        lastActiveSnapshotCount = 0;
        lastActiveDynamicCount = 0;
        lastActiveTerrainQueuedCount = 0;
        lastActiveTerrainSkippedHeightCount = 0;
        lastSyncedEntityCount = 0;
        lastEntityPoseSyncCount = 0;
        lastSyncRemovedBindingCount = 0;
        lastSyncMissingEntityCount = 0;
    }

    private int terrainColliderCount() {
        int count = 0;
        for (Map<Long, TerrainCollider> colliders : terrainColliders.values()) {
            count += colliders.size();
        }
        return count;
    }

    private static String describeGpuDynamicsStatuses(Set<String> statuses) {
        if (statuses.isEmpty()) {
            return "no_scenes";
        }
        return String.join("|", statuses);
    }

    private int terrainQueuedChunkCount() {
        int count = 0;
        for (Set<Long> queue : terrainBuildQueues.values()) {
            count += queue.size();
        }
        return count;
    }

    private int terrainChunkStateCount(TerrainChunkBuildStatus status) {
        int count = 0;
        for (Map<Long, TerrainChunkBuildState> states : terrainChunkBuildStates.values()) {
            for (TerrainChunkBuildState state : states.values()) {
                if (state.status() == status) {
                    count++;
                }
            }
        }
        return count;
    }

    private int terrainChunkCount() {
        int count = 0;
        for (Map<Long, Set<Long>> chunks : terrainChunks.values()) {
            count += chunks.size();
        }
        return count;
    }

    private ActiveObjectTerrainQueueResult queueTerrainAroundActiveObjects(MinecraftServer server) {
        int snapshotCount = 0;
        int dynamicCount = 0;
        int skippedByHeight = 0;
        int queuedTerrainChunks = 0;
        int remainingScans = activeTerrainScanLimit();
        for (ServerLevel level : server.getAllLevels()) {
            SceneState state = states.get(sceneKey(level));
            if (state == null || state.scene().isClosed()) {
                continue;
            }
            String sceneKey = state.scene().sceneKey();
            List<PhysicsObject> objects = state.scene().objectsOfType(PhysicsObjectType.DYNAMIC_BOX);
            snapshotCount += objects.size();
            if (objects.isEmpty()) {
                activeTerrainScanCursors.remove(sceneKey);
                continue;
            }
            if (remainingScans <= 0) {
                continue;
            }

            int cursor = activeTerrainScanCursors.getOrDefault(sceneKey, 0);
            if (cursor >= objects.size()) {
                cursor = 0;
            }
            int scanCount = Math.min(remainingScans, objects.size());
            for (int i = 0; i < scanCount; i++) {
                PhysicsObject object = objects.get((cursor + i) % objects.size());
                if (object.isClosed()) {
                    continue;
                }
                PhysicsVector position = object.pose().position();
                dynamicCount++;
                if (!isWithinActiveTerrainHeight(level, position)) {
                    skippedByHeight++;
                    continue;
                }
                queuedTerrainChunks += queueTerrainAround(level, BlockPos.containing(position.x(), position.y(), position.z()), ACTIVE_OBJECT_TERRAIN_CHUNK_RADIUS);
            }
            activeTerrainScanCursors.put(sceneKey, (cursor + scanCount) % objects.size());
            remainingScans -= scanCount;
            if (remainingScans <= 0) {
                break;
            }
        }
        return new ActiveObjectTerrainQueueResult(snapshotCount, dynamicCount, skippedByHeight, queuedTerrainChunks);
    }

    private int queueTerrainAround(ServerLevel level, BlockPos center, int chunkRadius) {
        int centerChunkX = Math.floorDiv(center.getX(), 16);
        int centerChunkZ = Math.floorDiv(center.getZ(), 16);
        int queued = queueLoadedTerrainChunk(level, ChunkPos.asLong(centerChunkX, centerChunkZ), false);

        for (int radius = 1; radius <= chunkRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    queued += queueLoadedTerrainChunk(level, ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz), false);
                }
            }
        }
        return queued;
    }

    private int queueLoadedTerrainChunk(ServerLevel level, long chunkKey, boolean dirty) {
        if (!isChunkSafeToInspect(level, chunkKey)) {
            return 0;
        }
        return queueTerrainChunk(level, chunkKey, dirty);
    }

    private static boolean isWithinActiveTerrainHeight(ServerLevel level, PhysicsVector position) {
        int margin = activeTerrainVerticalMargin();
        return position.y() >= level.getMinBuildHeight() - margin
                && position.y() <= level.getMaxBuildHeight() + margin;
    }

    private int queueTerrainChunk(ServerLevel level, long chunkKey, boolean dirty) {
        String sceneKey = sceneKey(level);
        TerrainChunkBuildState state = terrainChunkBuildStates
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new TerrainChunkBuildState());

        if (!dirty && state.status() != TerrainChunkBuildStatus.UNSEEN) {
            return 0;
        }
        if (dirty && state.status() == TerrainChunkBuildStatus.PENDING) {
            return 0;
        }

        state.mark(dirty ? TerrainChunkBuildStatus.DIRTY : TerrainChunkBuildStatus.PENDING);
        boolean added = terrainBuildQueues
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashSet<>())
                .add(chunkKey);
        return added ? 1 : 0;
    }

    private void processTerrainBuildQueue(MinecraftServer server) {
        int chunksBuilt = 0;
        int collidersBuilt = 0;
        int partialCollidersBuilt = 0;
        long buildNanos = 0L;

        for (ServerLevel level : server.getAllLevels()) {
            String sceneKey = sceneKey(level);
            LinkedHashSet<Long> queue = terrainBuildQueues.get(sceneKey);
            SceneState sceneState = states.get(sceneKey);
            if (queue == null || queue.isEmpty() || sceneState == null || sceneState.scene().isClosed()) {
                continue;
            }

            while (chunksBuilt < MAX_TERRAIN_CHUNK_BUILDS_PER_TICK && !queue.isEmpty()) {
                long chunkKey = pollFirst(queue);
                if (!isChunkSafeToInspect(level, chunkKey)) {
                    removeTerrainChunkTracking(sceneKey, chunkKey);
                    continue;
                }

                TerrainChunkBuildResult result = buildTerrainChunk(level, sceneState.scene(), chunkKey);
                TerrainChunkBuildState buildState = terrainChunkBuildStates
                        .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(chunkKey, ignored -> new TerrainChunkBuildState());
                buildState.markBuilt(result.created(), result.buildNanos());
                chunksBuilt++;
                collidersBuilt += result.created();
                partialCollidersBuilt += result.partialCreated();
                buildNanos += result.buildNanos();
            }

            if (queue.isEmpty()) {
                terrainBuildQueues.remove(sceneKey);
            }
            if (chunksBuilt >= MAX_TERRAIN_CHUNK_BUILDS_PER_TICK) {
                break;
            }
        }

        if (chunksBuilt > 0) {
            lastTerrainChunkBuildCount = chunksBuilt;
            lastTerrainColliderBuildCount = collidersBuilt;
            lastTerrainPartialColliderBuildCount = partialCollidersBuilt;
            lastTerrainBuildNanos = buildNanos;
        }
    }

    private TerrainChunkBuildResult buildTerrainChunk(ServerLevel level, ServerPhysicsScene scene, long chunkKey) {
        long start = System.nanoTime();
        int removed = removeTerrainCollisionForChunk(scene.sceneKey(), chunkKey);
        int created = 0;

        int chunkX = chunkX(chunkKey);
        int chunkZ = chunkZ(chunkKey);
        int minX = chunkX << 4;
        int minY = level.getMinBuildHeight();
        int height = level.getMaxBuildHeight() - minY;
        int minZ = chunkZ << 4;
        boolean[] solid = new boolean[16 * height * 16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int partialCreated = 0;

        for (int y = 0; y < height; y++) {
            int worldY = minY + y;
            for (int z = 0; z < 16; z++) {
                int worldZ = minZ + z;
                for (int x = 0; x < 16; x++) {
                    int worldX = minX + x;
                    pos.set(worldX, worldY, worldZ);
                    BlockState state = level.getBlockState(pos);
                    if (state.isCollisionShapeFullBlock(level, pos)) {
                        solid[terrainIndex(x, y, z)] = true;
                    } else {
                        partialCreated += createPartialTerrainColliders(level, scene, chunkKey, pos.immutable(), state);
                    }
                }
            }
        }

        created = partialCreated + createBatchedTerrainColliders(scene, chunkKey, minX, minY, minZ, height, solid);
        return new TerrainChunkBuildResult(created, removed, partialCreated, System.nanoTime() - start);
    }

    private int createPartialTerrainColliders(ServerLevel level, ServerPhysicsScene scene, long chunkKey, BlockPos pos, BlockState state) {
        VoxelShape collisionShape = state.getCollisionShape(level, pos);
        if (collisionShape.isEmpty()) {
            return 0;
        }

        int[] created = new int[1];
        collisionShape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double width = maxX - minX;
            double height = maxY - minY;
            double depth = maxZ - minZ;
            if (width <= 0.0D || height <= 0.0D || depth <= 0.0D) {
                return;
            }

            created[0] += createTerrainCollider(
                    scene,
                    chunkKey,
                    pos.getX() + minX + width * 0.5D,
                    pos.getY() + minY + height * 0.5D,
                    pos.getZ() + minZ + depth * 0.5D,
                    (float) (width * 0.5D),
                    (float) (height * 0.5D),
                    (float) (depth * 0.5D)
            );
        });
        return created[0];
    }

    private int createBatchedTerrainColliders(ServerPhysicsScene scene, long chunkKey, int minX, int minY, int minZ, int height, boolean[] solid) {
        boolean[] consumed = new boolean[solid.length];
        int created = 0;

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!isUnconsumedSolid(solid, consumed, x, y, z)) {
                        continue;
                    }

                    int width = terrainBatchWidth(solid, consumed, x, y, z);
                    int depth = terrainBatchDepth(solid, consumed, x, y, z, width);
                    int boxHeight = terrainBatchHeight(solid, consumed, x, y, z, width, depth, height);
                    markTerrainBatchConsumed(consumed, x, y, z, width, depth, boxHeight);

                    created += createTerrainCollider(
                            scene,
                            chunkKey,
                            minX + x + width * 0.5D,
                            minY + y + boxHeight * 0.5D,
                            minZ + z + depth * 0.5D,
                            width * 0.5F,
                            boxHeight * 0.5F,
                            depth * 0.5F
                    );
                }
            }
        }
        return created;
    }

    private static int terrainBatchWidth(boolean[] solid, boolean[] consumed, int startX, int y, int z) {
        int width = 0;
        while (startX + width < 16 && isUnconsumedSolid(solid, consumed, startX + width, y, z)) {
            width++;
        }
        return width;
    }

    private static int terrainBatchDepth(boolean[] solid, boolean[] consumed, int startX, int y, int startZ, int width) {
        int depth = 1;
        while (startZ + depth < 16) {
            for (int x = startX; x < startX + width; x++) {
                if (!isUnconsumedSolid(solid, consumed, x, y, startZ + depth)) {
                    return depth;
                }
            }
            depth++;
        }
        return depth;
    }

    private static int terrainBatchHeight(boolean[] solid, boolean[] consumed, int startX, int startY, int startZ, int width, int depth, int maxHeight) {
        int height = 1;
        while (startY + height < maxHeight) {
            for (int z = startZ; z < startZ + depth; z++) {
                for (int x = startX; x < startX + width; x++) {
                    if (!isUnconsumedSolid(solid, consumed, x, startY + height, z)) {
                        return height;
                    }
                }
            }
            height++;
        }
        return height;
    }

    private static void markTerrainBatchConsumed(boolean[] consumed, int startX, int startY, int startZ, int width, int depth, int height) {
        for (int y = startY; y < startY + height; y++) {
            for (int z = startZ; z < startZ + depth; z++) {
                for (int x = startX; x < startX + width; x++) {
                    consumed[terrainIndex(x, y, z)] = true;
                }
            }
        }
    }

    private static boolean isUnconsumedSolid(boolean[] solid, boolean[] consumed, int x, int y, int z) {
        int index = terrainIndex(x, y, z);
        return solid[index] && !consumed[index];
    }

    private static int terrainIndex(int x, int y, int z) {
        return ((y * 16) + z) * 16 + x;
    }

    public synchronized int unloadChunkCollision(ServerLevel level, long chunkKey) {
        String sceneKey = sceneKey(level);
        removeTerrainChunkTracking(sceneKey, chunkKey);
        return removeTerrainCollisionForChunk(sceneKey, chunkKey);
    }

    public synchronized void updateTerrainCollisionAt(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        String sceneKey = sceneKey(level);
        long chunkKey = ChunkPos.asLong(pos);
        if (!isKnownTerrainChunk(sceneKey, chunkKey)) {
            return;
        }
        queueTerrainChunk(level, chunkKey, true);
    }

    public synchronized void removeTerrainCollisionAt(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        String sceneKey = sceneKey(level);
        long chunkKey = ChunkPos.asLong(pos);
        if (isKnownTerrainChunk(sceneKey, chunkKey)) {
            queueTerrainChunk(level, chunkKey, true);
        }
    }

    private int createTerrainCollider(
            ServerPhysicsScene scene,
            long chunkKey,
            double centerX,
            double centerY,
            double centerZ,
            float halfExtentX,
            float halfExtentY,
            float halfExtentZ
    ) {
        String sceneKey = scene.sceneKey();
        long colliderKey = terrainColliderSequence++;
        Map<Long, TerrainCollider> colliders = terrainColliders.computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>());

        PhysicsObject collider = scene.createStaticBox(
                halfExtentX,
                halfExtentY,
                halfExtentZ,
                new PhysicsPose(
                        new PhysicsVector(centerX, centerY, centerZ),
                        PhysicsQuaternion.IDENTITY
                )
        );
        colliders.put(colliderKey, new TerrainCollider(collider, chunkKey));
        terrainChunks
                .computeIfAbsent(sceneKey, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>())
                .add(colliderKey);
        return 1;
    }

    private boolean removeTerrainCollider(String sceneKey, long colliderKey) {
        Map<Long, TerrainCollider> colliders = terrainColliders.get(sceneKey);
        if (colliders == null) {
            return false;
        }

        TerrainCollider collider = colliders.remove(colliderKey);
        if (collider == null) {
            return false;
        }

        collider.object().close();
        Map<Long, Set<Long>> chunks = terrainChunks.get(sceneKey);
        if (chunks != null) {
            Set<Long> colliderKeys = chunks.get(collider.chunkKey());
            if (colliderKeys != null) {
                colliderKeys.remove(colliderKey);
                if (colliderKeys.isEmpty()) {
                    chunks.remove(collider.chunkKey());
                }
            }
            if (chunks.isEmpty()) {
                terrainChunks.remove(sceneKey);
            }
        }
        if (colliders.isEmpty()) {
            terrainColliders.remove(sceneKey);
        }
        return true;
    }

    private int removeTerrainCollisionForChunk(String sceneKey, long chunkKey) {
        Map<Long, Set<Long>> chunks = terrainChunks.get(sceneKey);
        if (chunks == null) {
            return 0;
        }
        Set<Long> colliderKeys = chunks.get(chunkKey);
        if (colliderKeys == null || colliderKeys.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (long colliderKey : List.copyOf(colliderKeys)) {
            if (removeTerrainCollider(sceneKey, colliderKey)) {
                removed++;
            }
        }
        return removed;
    }

    private void removeTerrainChunkTracking(String sceneKey, long chunkKey) {
        LinkedHashSet<Long> queue = terrainBuildQueues.get(sceneKey);
        if (queue != null) {
            queue.remove(chunkKey);
            if (queue.isEmpty()) {
                terrainBuildQueues.remove(sceneKey);
            }
        }

        Map<Long, TerrainChunkBuildState> states = terrainChunkBuildStates.get(sceneKey);
        if (states != null) {
            states.remove(chunkKey);
            if (states.isEmpty()) {
                terrainChunkBuildStates.remove(sceneKey);
            }
        }
    }

    private boolean isKnownTerrainChunk(String sceneKey, long chunkKey) {
        Map<Long, TerrainChunkBuildState> states = terrainChunkBuildStates.get(sceneKey);
        return states != null && states.containsKey(chunkKey);
    }

    private boolean isChunkSafeToInspect(ServerLevel level, long chunkKey) {
        return level.hasChunk(chunkX(chunkKey), chunkZ(chunkKey));
    }

    private static long pollFirst(LinkedHashSet<Long> queue) {
        Iterator<Long> iterator = queue.iterator();
        long value = iterator.next();
        iterator.remove();
        return value;
    }

    private static int chunkX(long chunkKey) {
        return (int) chunkKey;
    }

    private static int chunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    private EntitySyncResult syncBoundEntities(MinecraftServer server) {
        int recreated = 0;
        int processedBindings = 0;
        int poseSyncs = 0;
        int removedBindings = 0;
        int missingEntities = 0;
        long objectLookupNanos = 0L;
        long entityLookupNanos = 0L;
        long recreateNanos = 0L;
        long poseReadNanos = 0L;
        long applyNanos = 0L;
        List<EntityBinding> bindings = List.copyOf(entityBindings.values());
        if (bindings.isEmpty()) {
            debugProxySyncCursor = 0;
            lastDebugProxyRecreateCount = 0;
            return EntitySyncResult.EMPTY;
        }

        if (debugProxySyncCursor >= bindings.size()) {
            debugProxySyncCursor = 0;
        }
        int syncLimit = debugProxySyncLimit();
        if (syncLimit <= 0) {
            lastDebugProxyRecreateCount = 0;
            return EntitySyncResult.EMPTY;
        }
        int syncCount = Math.min(syncLimit, bindings.size());
        for (int i = 0; i < syncCount; i++) {
            EntityBinding binding = bindings.get((debugProxySyncCursor + i) % bindings.size());
            processedBindings++;
            SceneState state = states.get(binding.sceneKey());
            ServerLevel level = server.getLevel(binding.levelKey());
            if (state == null || level == null) {
                entityBindings.remove(binding.objectId());
                removedBindings++;
                continue;
            }

            long objectLookupStart = System.nanoTime();
            Optional<PhysicsObject> maybeObject = state.scene().object(binding.objectId());
            objectLookupNanos += System.nanoTime() - objectLookupStart;
            long entityLookupStart = System.nanoTime();
            Entity entity = level.getEntity(binding.entityId());
            entityLookupNanos += System.nanoTime() - entityLookupStart;
            if (maybeObject.isEmpty()) {
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
                entityBindings.remove(binding.objectId());
                removedBindings++;
                continue;
            }
            if (entity != null && !(entity instanceof Display.BlockDisplay)) {
                entity.discard();
                entity = null;
            }
            if (entity == null || entity.isRemoved()) {
                missingEntities++;
                PhysicsObject object = maybeObject.get();
                long recreateStart = System.nanoTime();
                Display.BlockDisplay replacement = createDebugEntity(level, object, binding.halfExtents());
                if (!level.addFreshEntity(replacement)) {
                    recreateNanos += System.nanoTime() - recreateStart;
                    continue;
                }
                recreateNanos += System.nanoTime() - recreateStart;
                entityBindings.put(object.id(), new EntityBinding(binding.sceneKey(), binding.levelKey(), object.id(), replacement.getUUID(), binding.halfExtents()));
                entity = replacement;
                recreated++;
            }

            long poseReadStart = System.nanoTime();
            PhysicsPose pose = maybeObject.get().pose();
            poseReadNanos += System.nanoTime() - poseReadStart;
            long applyStart = System.nanoTime();
            boolean poseApplied = syncDebugEntity((Display.BlockDisplay) entity, pose, binding.halfExtents());
            applyNanos += System.nanoTime() - applyStart;
            if (poseApplied) {
                poseSyncs++;
            }
        }
        if (entityBindings.isEmpty()) {
            debugProxySyncCursor = 0;
        } else {
            debugProxySyncCursor = (debugProxySyncCursor + syncCount) % entityBindings.size();
        }
        lastDebugProxyRecreateCount = recreated;
        debugProxyRecreateCount += recreated;
        return new EntitySyncResult(
                processedBindings,
                poseSyncs,
                recreated,
                removedBindings,
                missingEntities,
                objectLookupNanos,
                entityLookupNanos,
                recreateNanos,
                poseReadNanos,
                applyNanos
        );
    }

    private Display.BlockDisplay createDebugEntity(ServerLevel level, PhysicsObject object, PhysicsVector halfExtents) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(Component.literal("PhysX " + object.id().toString().substring(0, 8)));
        entity.setCustomNameVisible(false);
        applyInitialDebugDisplayState(entity, object.pose(), halfExtents);
        return entity;
    }

    private boolean syncDebugEntity(Display.BlockDisplay entity, PhysicsPose pose, PhysicsVector halfExtents) {
        PhysicsVector position = pose.position();
        boolean syncTransform = debugProxySyncTransform();
        if (!syncTransform && isAtPosition(entity, position)) {
            return false;
        }
        entity.setPos(position.x(), position.y(), position.z());
        if (syncTransform) {
            setDebugDisplayTransformation(entity, debugDisplayTransformation(pose, halfExtents));
        }
        return true;
    }

    private static boolean isAtPosition(Entity entity, PhysicsVector position) {
        double dx = entity.getX() - position.x();
        double dy = entity.getY() - position.y();
        double dz = entity.getZ() - position.z();
        return dx * dx + dy * dy + dz * dz <= 1.0E-6D;
    }

    private void applyInitialDebugDisplayState(Display.BlockDisplay entity, PhysicsPose pose, PhysicsVector halfExtents) {
        CompoundTag tag = new CompoundTag();
        PhysicsVector position = pose.position();
        tag.put("Pos", doubleList(position.x(), position.y(), position.z()));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(DEBUG_PROXY_BLOCK));
        tag.putFloat("width", (float) (halfExtents.x() * 2.0D));
        tag.putFloat("height", (float) (halfExtents.y() * 2.0D));
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.2F);
        tag.putFloat("shadow_strength", 0.4F);
        tag.putInt("interpolation_duration", 0);
        tag.putInt("teleport_duration", 1);
        encodeDisplayTransformation(pose, halfExtents).ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static void setDebugDisplayTransformation(Display.BlockDisplay entity, Transformation transformation) {
        if (DISPLAY_SET_TRANSFORMATION == null) {
            return;
        }
        try {
            DISPLAY_SET_TRANSFORMATION.invoke(entity, transformation);
        } catch (Throwable ignored) {
            // If private display setters are unavailable, position sync still keeps the proxy useful.
        }
    }

    private Optional<net.minecraft.nbt.Tag> encodeDisplayTransformation(PhysicsPose pose, PhysicsVector halfExtents) {
        Transformation transformation = debugDisplayTransformation(pose, halfExtents);
        return Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, transformation)
                .resultOrPartial(message -> com.firedoge.px4mc.PhysX4mc.LOGGER.warn("Failed to encode debug display transformation: {}", message));
    }

    private static Transformation debugDisplayTransformation(PhysicsPose pose, PhysicsVector halfExtents) {
        Quaternionf rotation = toJomlQuaternion(pose.rotation());
        Vector3f scale = new Vector3f(
                (float) (halfExtents.x() * 2.0D),
                (float) (halfExtents.y() * 2.0D),
                (float) (halfExtents.z() * 2.0D)
        );
        Vector3f localCenter = new Vector3f(
                (float) halfExtents.x(),
                (float) halfExtents.y(),
                (float) halfExtents.z()
        ).rotate(new Quaternionf(rotation));
        Vector3f translation = localCenter.negate();
        return new Transformation(
                translation,
                rotation,
                scale,
                new Quaternionf()
        );
    }

    private static MethodHandle findDisplaySetTransformation() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup());
            return lookup.findVirtual(Display.class, "setTransformation", MethodType.methodType(void.class, Transformation.class));
        } catch (ReflectiveOperationException exception) {
            com.firedoge.px4mc.PhysX4mc.LOGGER.warn("Display#setTransformation is unavailable; debug proxies will only sync position", exception);
            return null;
        }
    }

    private static Quaternionf toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }

    private static ListTag doubleList(double first, double second, double third) {
        ListTag list = new ListTag();
        list.addTag(0, DoubleTag.valueOf(first));
        list.addTag(1, DoubleTag.valueOf(second));
        list.addTag(2, DoubleTag.valueOf(third));
        return list;
    }

    private static ListTag floatList(float first, float second) {
        ListTag list = new ListTag();
        list.addTag(0, FloatTag.valueOf(first));
        list.addTag(1, FloatTag.valueOf(second));
        return list;
    }

    private void removeBindingsForScene(ServerLevel level, String sceneKey) {
        for (EntityBinding binding : List.copyOf(entityBindings.values())) {
            if (!binding.sceneKey().equals(sceneKey)) {
                continue;
            }
            Entity entity = level.getEntity(binding.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
            entityBindings.remove(binding.objectId());
        }
    }

    private UUID discardBoundEntity(ServerLevel level, PhysicsObjectId objectId) {
        EntityBinding binding = entityBindings.remove(objectId);
        if (binding == null) {
            return null;
        }
        Entity entity = level.getEntity(binding.entityId());
        if (entity != null && !entity.isRemoved()) {
            entity.discard();
        }
        return binding.entityId();
    }

    private void discardBoundEntities(MinecraftServer server) {
        for (EntityBinding binding : List.copyOf(entityBindings.values())) {
            ServerLevel level = server.getLevel(binding.levelKey());
            if (level == null) {
                continue;
            }
            Entity entity = level.getEntity(binding.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
    }

    public record RuntimeStatus(
            int sceneCount,
            int objectCount,
            int dynamicBoxCount,
            int terrainColliderCount,
            int terrainChunkCount,
            int terrainQueuedChunkCount,
            int terrainBuiltChunkCount,
            int terrainDirtyChunkCount,
            int boundEntityCount,
            boolean nativeLinked,
            boolean gpuDynamicsRequested,
            int gpuDynamicsSceneCount,
            String gpuDynamicsStatus,
            long lastStepNanos,
            int lastTerrainChunkBuildCount,
            int lastTerrainColliderBuildCount,
            int lastTerrainPartialColliderBuildCount,
            long lastTerrainBuildNanos,
            int debugProxyRecreateCount,
            int lastDebugProxyRecreateCount,
            long lastRuntimeTickNanos,
            long lastQueueActiveNanos,
            long lastTerrainProcessNanos,
            long lastStepPhaseNanos,
            long lastSyncEntitiesNanos,
            long lastSyncObjectLookupNanos,
            long lastSyncEntityLookupNanos,
            long lastSyncRecreateNanos,
            long lastSyncPoseReadNanos,
            long lastSyncApplyNanos,
            int lastActiveSnapshotCount,
            int lastActiveDynamicCount,
            int lastActiveTerrainQueuedCount,
            int lastActiveTerrainSkippedHeightCount,
            int lastSyncedEntityCount,
            int lastEntityPoseSyncCount,
            int lastSyncRemovedBindingCount,
            int lastSyncMissingEntityCount,
            boolean debugProxySyncTransform,
            int maxEntityPoseSyncsPerTick,
            int activeTerrainMaxScansPerTick
    ) {
        public double lastStepMillis() {
            return lastStepNanos / 1_000_000.0D;
        }

        public double lastTerrainBuildMillis() {
            return lastTerrainBuildNanos / 1_000_000.0D;
        }

        public double lastRuntimeTickMillis() {
            return lastRuntimeTickNanos / 1_000_000.0D;
        }

        public double lastQueueActiveMillis() {
            return lastQueueActiveNanos / 1_000_000.0D;
        }

        public double lastTerrainProcessMillis() {
            return lastTerrainProcessNanos / 1_000_000.0D;
        }

        public double lastStepPhaseMillis() {
            return lastStepPhaseNanos / 1_000_000.0D;
        }

        public double lastSyncEntitiesMillis() {
            return lastSyncEntitiesNanos / 1_000_000.0D;
        }

        public double lastSyncObjectLookupMillis() {
            return lastSyncObjectLookupNanos / 1_000_000.0D;
        }

        public double lastSyncEntityLookupMillis() {
            return lastSyncEntityLookupNanos / 1_000_000.0D;
        }

        public double lastSyncRecreateMillis() {
            return lastSyncRecreateNanos / 1_000_000.0D;
        }

        public double lastSyncPoseReadMillis() {
            return lastSyncPoseReadNanos / 1_000_000.0D;
        }

        public double lastSyncApplyMillis() {
            return lastSyncApplyNanos / 1_000_000.0D;
        }
    }

    public record SpawnedDebugBox(PhysicsObject object, UUID entityId, int terrainChunkQueueCount, PhysicsVector halfExtents, float mass) {
    }

    public record StressGridResult(int created, int requested, int terrainChunkQueueCount, PhysicsVector halfExtents, float spacing, float mass) {
    }

    public record VelocityControlResult(PhysicsObjectId objectId, PhysicsVector previousVelocity, PhysicsVector newVelocity, double distance) {
    }

    public record RemovedDebugBox(PhysicsObjectId objectId, UUID entityId, PhysicsVector lastPosition, PhysicsVector lastVelocity) {
    }

    private record EntityBinding(String sceneKey, ResourceKey<Level> levelKey, PhysicsObjectId objectId, UUID entityId, PhysicsVector halfExtents) {
    }

    private record TerrainCollider(PhysicsObject object, long chunkKey) {
    }

    private record TerrainChunkBuildResult(int created, int removed, int partialCreated, long buildNanos) {
    }

    private record ActiveObjectTerrainQueueResult(int snapshotCount, int dynamicCount, int skippedByHeight, int queuedTerrainChunks) {
    }

    private record EntitySyncResult(
            int processedBindings,
            int poseSyncs,
            int recreated,
            int removedBindings,
            int missingEntities,
            long objectLookupNanos,
            long entityLookupNanos,
            long recreateNanos,
            long poseReadNanos,
            long applyNanos
    ) {
        private static final EntitySyncResult EMPTY = new EntitySyncResult(0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L);
    }

    private enum TerrainChunkBuildStatus {
        UNSEEN,
        PENDING,
        BUILT,
        DIRTY
    }

    private static final class TerrainChunkBuildState {
        private TerrainChunkBuildStatus status = TerrainChunkBuildStatus.UNSEEN;
        private int colliderCount;
        private long lastBuildNanos;

        private TerrainChunkBuildStatus status() {
            return status;
        }

        private void mark(TerrainChunkBuildStatus status) {
            this.status = status;
        }

        private void markBuilt(int colliderCount, long lastBuildNanos) {
            this.status = TerrainChunkBuildStatus.BUILT;
            this.colliderCount = colliderCount;
            this.lastBuildNanos = lastBuildNanos;
        }
    }

    private static final class SceneState {
        private final ServerPhysicsScene scene;
        private final float fixedTimeStep;
        private final int maxSubSteps;
        private float accumulator;
        private long lastStepNanos;

        private SceneState(ServerPhysicsScene scene, float fixedTimeStep, int maxSubSteps) {
            this.scene = scene;
            this.fixedTimeStep = fixedTimeStep;
            this.maxSubSteps = maxSubSteps;
        }

        private ServerPhysicsScene scene() {
            return scene;
        }

        private long lastStepNanos() {
            return lastStepNanos;
        }

        private void advance(float deltaSeconds) {
            if (scene.isClosed()) {
                return;
            }
            accumulator += Math.max(0.0F, deltaSeconds);
            int steps = 0;
            long start = System.nanoTime();
            while (accumulator >= fixedTimeStep && steps < maxSubSteps) {
                scene.step(fixedTimeStep);
                accumulator -= fixedTimeStep;
                steps++;
            }
            if (steps == maxSubSteps) {
                accumulator = 0.0F;
            }
            lastStepNanos = System.nanoTime() - start;
        }
    }
}
