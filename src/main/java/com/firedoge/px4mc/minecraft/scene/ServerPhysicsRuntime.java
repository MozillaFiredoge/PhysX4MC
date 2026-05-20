package com.firedoge.px4mc.minecraft.scene;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.math.Transformation;

import com.firedoge.px4mc.api.PhysicsBackend;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.api.PhysicsWorldConfig;
import com.firedoge.px4mc.config.PhysXConfig;
import com.firedoge.px4mc.physics.PhysicsManager;

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
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ServerPhysicsRuntime implements AutoCloseable {
    public static final ServerPhysicsRuntime INSTANCE = new ServerPhysicsRuntime();
    private static final int ACTIVE_OBJECT_TERRAIN_CHUNK_RADIUS = 1;
    private static final int SPAWN_TERRAIN_CHUNK_RADIUS = 1;
    private static final int MAX_TERRAIN_CHUNK_BUILDS_PER_TICK = 1;
    private static final BlockState DEBUG_PROXY_BLOCK = Blocks.LIME_STAINED_GLASS.defaultBlockState();

    private final PhysicsSceneManager scenes = new PhysicsSceneManager();
    private final Map<String, SceneState> states = new LinkedHashMap<>();
    private final Map<PhysicsObjectId, EntityBinding> entityBindings = new LinkedHashMap<>();
    private final Map<String, Map<Long, TerrainCollider>> terrainColliders = new LinkedHashMap<>();
    private final Map<String, Map<Long, Set<Long>>> terrainChunks = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<Long>> terrainBuildQueues = new LinkedHashMap<>();
    private final Map<String, Map<Long, TerrainChunkBuildState>> terrainChunkBuildStates = new LinkedHashMap<>();
    private long terrainColliderSequence = 1L;
    private int lastTerrainChunkBuildCount;
    private int lastTerrainColliderBuildCount;
    private int lastTerrainPartialColliderBuildCount;
    private long lastTerrainBuildNanos;
    private int debugProxyRecreateCount;
    private int lastDebugProxyRecreateCount;
    private boolean warnedUnavailable;

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
                PhysXConfig.MAX_SUB_STEPS.get()
        );
        ServerPhysicsScene scene = scenes.createScene(sceneKey, backend, config);
        states.put(sceneKey, new SceneState(scene, config.fixedTimeStep(), config.maxSubSteps()));
        return scene;
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!ensureScenes(server)) {
            return;
        }
        queueTerrainAroundActiveObjects(server);
        processTerrainBuildQueue(server);
        for (SceneState state : List.copyOf(states.values())) {
            state.advance(1.0F / 20.0F);
        }
        syncBoundEntities(server);
    }

    public synchronized RuntimeStatus status() {
        int objectCount = 0;
        int dynamicBoxCount = 0;
        long lastStepNanos = 0L;
        for (SceneState state : states.values()) {
            objectCount += state.scene().objectCount();
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
                lastStepNanos,
                lastTerrainChunkBuildCount,
                lastTerrainColliderBuildCount,
                lastTerrainPartialColliderBuildCount,
                lastTerrainBuildNanos,
                debugProxyRecreateCount,
                lastDebugProxyRecreateCount
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
        terrainColliderSequence = 1L;
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
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
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
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

    @Override
    public synchronized void close() {
        entityBindings.clear();
        terrainColliders.clear();
        terrainChunks.clear();
        terrainBuildQueues.clear();
        terrainChunkBuildStates.clear();
        terrainColliderSequence = 1L;
        lastTerrainChunkBuildCount = 0;
        lastTerrainColliderBuildCount = 0;
        lastTerrainPartialColliderBuildCount = 0;
        lastTerrainBuildNanos = 0L;
        debugProxyRecreateCount = 0;
        lastDebugProxyRecreateCount = 0;
        scenes.close();
        states.clear();
        warnedUnavailable = false;
    }

    public synchronized void close(MinecraftServer server) {
        discardBoundEntities(server);
        close();
    }

    private boolean ensureScenes(MinecraftServer server) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                sceneFor(level);
            }
            return true;
        } catch (RuntimeException exception) {
            if (!warnedUnavailable) {
                com.firedoge.px4mc.PhysX4mc.LOGGER.warn("Physics runtime is not available; server scenes were not created", exception);
                warnedUnavailable = true;
            }
            return false;
        }
    }

    private static String sceneKey(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        return dimension.location().toString();
    }

    private int terrainColliderCount() {
        int count = 0;
        for (Map<Long, TerrainCollider> colliders : terrainColliders.values()) {
            count += colliders.size();
        }
        return count;
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

    private void queueTerrainAroundActiveObjects(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            SceneState state = states.get(sceneKey(level));
            if (state == null || state.scene().isClosed()) {
                continue;
            }
            for (PhysicsObjectSnapshot snapshot : state.scene().snapshots()) {
                if (snapshot.type() == PhysicsObjectType.DYNAMIC_BOX && !snapshot.closed()) {
                    PhysicsVector position = snapshot.pose().position();
                    queueTerrainAround(level, BlockPos.containing(position.x(), position.y(), position.z()), ACTIVE_OBJECT_TERRAIN_CHUNK_RADIUS);
                }
            }
        }
    }

    private int queueTerrainAround(ServerLevel level, BlockPos center, int chunkRadius) {
        int centerChunkX = Math.floorDiv(center.getX(), 16);
        int centerChunkZ = Math.floorDiv(center.getZ(), 16);
        int queued = queueTerrainChunk(level, ChunkPos.asLong(centerChunkX, centerChunkZ), false);

        for (int radius = 1; radius <= chunkRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    queued += queueTerrainChunk(level, ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz), false);
                }
            }
        }
        return queued;
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

    private void syncBoundEntities(MinecraftServer server) {
        int recreated = 0;
        for (EntityBinding binding : List.copyOf(entityBindings.values())) {
            SceneState state = states.get(binding.sceneKey());
            ServerLevel level = server.getLevel(binding.levelKey());
            if (state == null || level == null) {
                entityBindings.remove(binding.objectId());
                continue;
            }

            Optional<PhysicsObject> maybeObject = state.scene().object(binding.objectId());
            Entity entity = level.getEntity(binding.entityId());
            if (maybeObject.isEmpty()) {
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
                entityBindings.remove(binding.objectId());
                continue;
            }
            if (entity != null && !(entity instanceof Display.BlockDisplay)) {
                entity.discard();
                entity = null;
            }
            if (entity == null || entity.isRemoved()) {
                PhysicsObject object = maybeObject.get();
                Display.BlockDisplay replacement = createDebugEntity(level, object, binding.halfExtents());
                if (!level.addFreshEntity(replacement)) {
                    continue;
                }
                entityBindings.put(object.id(), new EntityBinding(binding.sceneKey(), binding.levelKey(), object.id(), replacement.getUUID(), binding.halfExtents()));
                entity = replacement;
                recreated++;
            }

            syncDebugEntity((Display.BlockDisplay) entity, maybeObject.get().pose(), binding.halfExtents());
        }
        lastDebugProxyRecreateCount = recreated;
        debugProxyRecreateCount += recreated;
    }

    private Display.BlockDisplay createDebugEntity(ServerLevel level, PhysicsObject object, PhysicsVector halfExtents) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(Component.literal("PhysX " + object.id().toString().substring(0, 8)));
        entity.setCustomNameVisible(false);
        syncDebugEntity(entity, object.pose(), halfExtents);
        return entity;
    }

    private void syncDebugEntity(Display.BlockDisplay entity, PhysicsPose pose, PhysicsVector halfExtents) {
        PhysicsVector position = pose.position();
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
        applyDebugDisplayState(entity, pose, halfExtents);
        entity.teleportTo(position.x(), position.y(), position.z());
    }

    private void applyDebugDisplayState(Display.BlockDisplay entity, PhysicsPose pose, PhysicsVector halfExtents) {
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

    private Optional<net.minecraft.nbt.Tag> encodeDisplayTransformation(PhysicsPose pose, PhysicsVector halfExtents) {
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
        Transformation transformation = new Transformation(
                translation,
                rotation,
                scale,
                new Quaternionf()
        );
        return Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, transformation)
                .resultOrPartial(message -> com.firedoge.px4mc.PhysX4mc.LOGGER.warn("Failed to encode debug display transformation: {}", message));
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
            long lastStepNanos,
            int lastTerrainChunkBuildCount,
            int lastTerrainColliderBuildCount,
            int lastTerrainPartialColliderBuildCount,
            long lastTerrainBuildNanos,
            int debugProxyRecreateCount,
            int lastDebugProxyRecreateCount
    ) {
        public double lastStepMillis() {
            return lastStepNanos / 1_000_000.0D;
        }

        public double lastTerrainBuildMillis() {
            return lastTerrainBuildNanos / 1_000_000.0D;
        }
    }

    public record SpawnedDebugBox(PhysicsObject object, UUID entityId, int terrainChunkQueueCount, PhysicsVector halfExtents, float mass) {
    }

    public record VelocityControlResult(PhysicsObjectId objectId, PhysicsVector previousVelocity, PhysicsVector newVelocity, double distance) {
    }

    private record EntityBinding(String sceneKey, ResourceKey<Level> levelKey, PhysicsObjectId objectId, UUID entityId, PhysicsVector halfExtents) {
    }

    private record TerrainCollider(PhysicsObject object, long chunkKey) {
    }

    private record TerrainChunkBuildResult(int created, int removed, int partialCreated, long buildNanos) {
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
