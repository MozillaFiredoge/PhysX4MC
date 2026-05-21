package com.firedoge.px4mc.minecraft.sublevel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodyId;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.firedoge.px4mc.mechanics.MechanicsBoxDefinition;
import com.firedoge.px4mc.mechanics.MechanicsWorld;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;
import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class SubLevelManager {
    public static final SubLevelManager INSTANCE = new SubLevelManager();
    private static final int BLOCK_UPDATE_FLAGS = 3;
    private static final int MAX_ASSEMBLY_SCAN_VOLUME = 512;
    private static final int MAX_ASSEMBLY_BLOCKS = 64;
    private static final int MAX_ASSEMBLY_SPAN = 8;
    private static final MethodHandle DISPLAY_SET_TRANSFORMATION = findDisplaySetTransformation();

    private final Map<SubLevelId, PhysicsSubLevel> subLevels = new LinkedHashMap<>();

    private SubLevelManager() {
    }

    public synchronized SubLevelSnapshot assembleBlock(ServerLevel level, BlockPos pos, float mass, boolean debugProxy) {
        return assembleBox(level, pos, pos, mass, debugProxy);
    }

    public synchronized SubLevelSnapshot assembleBox(ServerLevel level, BlockPos first, BlockPos second, float mass, boolean debugProxy) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Mass must be positive");
        }

        SubLevelBounds bounds = SubLevelBounds.from(first, second);
        validateBounds(bounds);
        List<SubLevelBlock> blocks = collectBlocks(level, bounds);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("No detachable collision blocks in " + describeBounds(bounds));
        }

        AggregateShape aggregate = aggregateShape(blocks);
        List<SubLevelBlock> subLevelBlocks = withVisualOrigins(blocks, aggregate.center());
        MechanicsWorld world = PhysX4mc.api().world(level);
        List<SubLevelBlock> removedBlocks = new ArrayList<>(blocks.size());
        MechanicsBodySnapshot body = null;
        PhysicsSubLevel subLevel = null;
        try {
            for (SubLevelBlock block : blocks) {
                if (!level.setBlock(block.sourcePos(), Blocks.AIR.defaultBlockState(), BLOCK_UPDATE_FLAGS)) {
                    throw new IllegalStateException("Failed to remove block at " + describePos(block.sourcePos()));
                }
                removedBlocks.add(block);
            }
            refreshTerrainAround(level, removedBlocks);

            body = world.createDynamicBox(MechanicsBoxDefinition.gameplayDynamicBox(
                    new PhysicsPose(aggregate.center(), PhysicsQuaternion.IDENTITY),
                    aggregate.halfExtents(),
                    mass
            ));
            subLevel = new PhysicsSubLevel(
                    SubLevelId.random(),
                    level.dimension(),
                    body.id(),
                    bounds,
                    subLevelBlocks
            );
            subLevels.put(subLevel.id(), subLevel);
            if (debugProxy) {
                createVisuals(level, body, subLevel);
            }
            return snapshot(body, subLevel);
        } catch (RuntimeException exception) {
            if (subLevel != null) {
                discardVisuals(level, subLevel);
                subLevels.remove(subLevel.id());
            }
            if (body != null) {
                world.removeBody(body.id());
            }
            for (SubLevelBlock block : removedBlocks) {
                level.setBlock(block.sourcePos(), block.blockState(), BLOCK_UPDATE_FLAGS);
            }
            refreshTerrainAround(level, removedBlocks);
            throw exception;
        }
    }

    public synchronized void tick(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (subLevels.isEmpty()) {
            return;
        }

        for (Map.Entry<SubLevelId, PhysicsSubLevel> entry : List.copyOf(subLevels.entrySet())) {
            PhysicsSubLevel subLevel = entry.getValue();
            ServerLevel level = server.getLevel(subLevel.levelKey());
            if (level == null) {
                continue;
            }
            Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                    .flatMap(world -> world.snapshot(subLevel.bodyId()));
            if (maybeBody.isEmpty()) {
                discardVisuals(level, subLevel);
                subLevels.remove(entry.getKey());
                continue;
            }
            syncVisuals(level, maybeBody.get(), subLevel);
        }
    }

    public synchronized List<SubLevelSnapshot> snapshots(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            removeStaleLevelEntries(level);
            return List.of();
        }

        MechanicsWorld world = maybeWorld.get();
        List<SubLevelSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<SubLevelId, PhysicsSubLevel> entry : List.copyOf(subLevels.entrySet())) {
            PhysicsSubLevel subLevel = entry.getValue();
            if (!subLevel.levelKey().equals(level.dimension())) {
                continue;
            }
            Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(subLevel.bodyId());
            if (maybeBody.isEmpty()) {
                discardVisuals(level, subLevel);
                subLevels.remove(entry.getKey());
                continue;
            }
            snapshots.add(snapshot(maybeBody.get(), subLevel));
        }
        return List.copyOf(snapshots);
    }

    public synchronized Optional<SubLevelSnapshot> snapshot(ServerLevel level, SubLevelId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsSubLevel subLevel = subLevels.get(id);
        if (subLevel == null || !subLevel.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }

        Optional<MechanicsBodySnapshot> maybeBody = PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            discardVisuals(level, subLevel);
            subLevels.remove(id);
            return Optional.empty();
        }
        return Optional.of(snapshot(maybeBody.get(), subLevel));
    }

    public synchronized Optional<SubLevelPickResult> pickBlock(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldOrigin, "worldOrigin");
        Objects.requireNonNull(worldDirection, "worldDirection");
        if (maxDistance <= 0.0D || Double.isNaN(maxDistance)) {
            throw new IllegalArgumentException("maxDistance must be positive");
        }

        PhysicsVector direction = normalize(worldDirection);
        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        if (maybeWorld.isEmpty()) {
            removeStaleLevelEntries(level);
            return Optional.empty();
        }

        MechanicsWorld world = maybeWorld.get();
        SubLevelPickResult best = null;
        for (Map.Entry<SubLevelId, PhysicsSubLevel> entry : List.copyOf(subLevels.entrySet())) {
            PhysicsSubLevel subLevel = entry.getValue();
            if (!subLevel.levelKey().equals(level.dimension())) {
                continue;
            }
            Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(subLevel.bodyId());
            if (maybeBody.isEmpty()) {
                discardVisuals(level, subLevel);
                subLevels.remove(entry.getKey());
                continue;
            }
            SubLevelTransform transform = SubLevelTransform.from(maybeBody.get());
            PhysicsVector localOrigin = transform.worldToLocal(worldOrigin);
            PhysicsVector localDirection = transform.worldDirectionToLocal(direction);
            for (SubLevelBlock block : subLevel.blocks()) {
                Optional<Double> maybeDistance = intersectBlock(localOrigin, localDirection, block, maxDistance);
                if (maybeDistance.isEmpty()) {
                    continue;
                }
                double distance = maybeDistance.get();
                if (best != null && distance >= best.distance()) {
                    continue;
                }
                PhysicsVector localHit = add(localOrigin, scale(localDirection, distance));
                PhysicsVector worldHit = transform.localToWorld(localHit);
                best = new SubLevelPickResult(
                        subLevel.id(),
                        maybeBody.get(),
                        block,
                        block.localPos(),
                        block.blockState(),
                        worldHit,
                        localHit,
                        distance
                );
            }
        }
        return Optional.ofNullable(best);
    }

    public synchronized Optional<SubLevelBreakResult> breakPickedBlock(
            ServerLevel level,
            PhysicsVector worldOrigin,
            PhysicsVector worldDirection,
            double maxDistance
    ) {
        Objects.requireNonNull(level, "level");
        Optional<SubLevelPickResult> maybePick = pickBlock(level, worldOrigin, worldDirection, maxDistance);
        if (maybePick.isEmpty()) {
            return Optional.empty();
        }

        SubLevelPickResult pick = maybePick.get();
        PhysicsSubLevel subLevel = subLevels.get(pick.id());
        if (subLevel == null || !subLevel.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }

        Optional<SubLevelBlock> removedBlock = subLevel.section().removeBlock(pick.localPos());
        if (removedBlock.isEmpty()) {
            return Optional.empty();
        }

        int removedVisuals = discardVisualsForBlock(level, subLevel, pick.localPos());
        boolean removedSubLevel = false;
        if (subLevel.section().isEmpty()) {
            discardVisuals(level, subLevel);
            PhysX4mc.api().existingWorld(level).ifPresent(world -> world.removeBody(subLevel.bodyId()));
            subLevels.remove(subLevel.id());
            removedSubLevel = true;
        }

        return Optional.of(new SubLevelBreakResult(
                pick,
                removedSubLevel,
                subLevel.section().blockCount(),
                subLevel.section().dirtyBlockCount(),
                removedVisuals
        ));
    }

    public synchronized Optional<SubLevelSnapshot> restoreOriginal(ServerLevel level, SubLevelId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsSubLevel subLevel = subLevels.get(id);
        if (subLevel == null || !subLevel.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }

        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        Optional<MechanicsBodySnapshot> maybeBody = maybeWorld.flatMap(world -> world.snapshot(subLevel.bodyId()));
        if (maybeBody.isEmpty()) {
            discardVisuals(level, subLevel);
            subLevels.remove(id);
            return Optional.empty();
        }

        for (SubLevelBlock block : subLevel.blocks()) {
            BlockState currentState = level.getBlockState(block.sourcePos());
            if (!currentState.isAir()) {
                throw new IllegalStateException("Cannot restore " + id + "; source position " + describePos(block.sourcePos()) + " is occupied");
            }
        }
        for (SubLevelBlock block : subLevel.blocks()) {
            if (!level.setBlock(block.sourcePos(), block.blockState(), BLOCK_UPDATE_FLAGS)) {
                throw new IllegalStateException("Failed to restore block at " + describePos(block.sourcePos()));
            }
        }
        refreshTerrainAround(level, subLevel.blocks());

        discardVisuals(level, subLevel);
        maybeWorld.ifPresent(world -> world.removeBody(subLevel.bodyId()));
        subLevels.remove(id);
        return Optional.of(snapshot(maybeBody.get(), subLevel));
    }

    public synchronized Optional<SubLevelSnapshot> remove(ServerLevel level, SubLevelId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        PhysicsSubLevel subLevel = subLevels.get(id);
        if (subLevel == null || !subLevel.levelKey().equals(level.dimension())) {
            return Optional.empty();
        }

        Optional<MechanicsWorld> maybeWorld = PhysX4mc.api().existingWorld(level);
        Optional<MechanicsBodySnapshot> maybeBody = maybeWorld.flatMap(world -> world.snapshot(subLevel.bodyId()));
        discardVisuals(level, subLevel);
        maybeWorld.ifPresent(world -> world.removeBody(subLevel.bodyId()));
        subLevels.remove(id);
        return maybeBody.map(body -> snapshot(body, subLevel));
    }

    public synchronized int forgetLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        int removed = 0;
        for (Map.Entry<SubLevelId, PhysicsSubLevel> entry : List.copyOf(subLevels.entrySet())) {
            if (entry.getValue().levelKey().equals(level.dimension())) {
                discardVisuals(level, entry.getValue());
                subLevels.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    public synchronized void close(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (PhysicsSubLevel subLevel : subLevels.values()) {
            ServerLevel level = server.getLevel(subLevel.levelKey());
            if (level != null) {
                discardVisuals(level, subLevel);
            }
        }
        subLevels.clear();
    }

    private List<SubLevelBlock> collectBlocks(ServerLevel level, SubLevelBounds bounds) {
        List<SubLevelBlock> blocks = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bounds.minSourcePos().getY(); y <= bounds.maxSourcePos().getY(); y++) {
            for (int z = bounds.minSourcePos().getZ(); z <= bounds.maxSourcePos().getZ(); z++) {
                for (int x = bounds.minSourcePos().getX(); x <= bounds.maxSourcePos().getX(); x++) {
                    pos.set(x, y, z);
                    BlockState blockState = level.getBlockState(pos);
                    if (blockState.isAir()) {
                        continue;
                    }
                    VoxelShape collisionShape = blockState.getCollisionShape(level, pos);
                    if (collisionShape.isEmpty()) {
                        continue;
                    }
                    AABB localBounds = collisionShape.bounds();
                    PhysicsVector localHalfExtents = halfExtents(localBounds);
                    if (!isPositive(localHalfExtents)) {
                        continue;
                    }
                    if (blocks.size() >= MAX_ASSEMBLY_BLOCKS) {
                        throw new IllegalArgumentException("Sublevels are limited to " + MAX_ASSEMBLY_BLOCKS + " collision blocks");
                    }
                    BlockPos sourcePos = pos.immutable();
                    blocks.add(new SubLevelBlock(sourcePos, bounds.toLocal(sourcePos), blockState, localBounds, PhysicsVector.ZERO));
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static void validateBounds(SubLevelBounds bounds) {
        if (bounds.width() > MAX_ASSEMBLY_SPAN || bounds.height() > MAX_ASSEMBLY_SPAN || bounds.depth() > MAX_ASSEMBLY_SPAN) {
            throw new IllegalArgumentException("Sublevel assembly span is limited to " + MAX_ASSEMBLY_SPAN + " blocks per axis");
        }
        if (bounds.volume() > MAX_ASSEMBLY_SCAN_VOLUME) {
            throw new IllegalArgumentException("Sublevel assembly scan volume is limited to " + MAX_ASSEMBLY_SCAN_VOLUME + " blocks");
        }
    }

    private static AggregateShape aggregateShape(List<SubLevelBlock> blocks) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (SubLevelBlock block : blocks) {
            BlockPos pos = block.sourcePos();
            AABB bounds = block.localCollisionBounds();
            minX = Math.min(minX, pos.getX() + bounds.minX);
            minY = Math.min(minY, pos.getY() + bounds.minY);
            minZ = Math.min(minZ, pos.getZ() + bounds.minZ);
            maxX = Math.max(maxX, pos.getX() + bounds.maxX);
            maxY = Math.max(maxY, pos.getY() + bounds.maxY);
            maxZ = Math.max(maxZ, pos.getZ() + bounds.maxZ);
        }

        PhysicsVector center = new PhysicsVector(
                (minX + maxX) * 0.5D,
                (minY + maxY) * 0.5D,
                (minZ + maxZ) * 0.5D
        );
        PhysicsVector halfExtents = new PhysicsVector(
                (maxX - minX) * 0.5D,
                (maxY - minY) * 0.5D,
                (maxZ - minZ) * 0.5D
        );
        if (!isPositive(halfExtents)) {
            throw new IllegalArgumentException("Sublevel collision bounds are too small");
        }
        return new AggregateShape(center, halfExtents);
    }

    private static List<SubLevelBlock> withVisualOrigins(List<SubLevelBlock> blocks, PhysicsVector bodyCenter) {
        List<SubLevelBlock> visualBlocks = new ArrayList<>(blocks.size());
        for (SubLevelBlock block : blocks) {
            BlockPos pos = block.sourcePos();
            visualBlocks.add(block.withVisualLocalOrigin(new PhysicsVector(
                    pos.getX() - bodyCenter.x(),
                    pos.getY() - bodyCenter.y(),
                    pos.getZ() - bodyCenter.z()
            )));
        }
        return List.copyOf(visualBlocks);
    }

    private void createVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        for (SubLevelBlock block : subLevel.blocks()) {
            Display.BlockDisplay entity = createVisualEntity(level, body.pose(), block);
            if (level.addFreshEntity(entity)) {
                subLevel.visuals().add(new PhysicsSubLevel.VisualBinding(block, entity.getUUID()));
            }
        }
    }

    private void syncVisuals(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        if (subLevel.visuals().isEmpty()) {
            return;
        }
        for (PhysicsSubLevel.VisualBinding visual : subLevel.visuals()) {
            Entity entity = level.getEntity(visual.entityId());
            if (!(entity instanceof Display.BlockDisplay display) || display.isRemoved()) {
                continue;
            }
            syncVisualEntity(display, body.pose(), visual.block());
        }
    }

    private Display.BlockDisplay createVisualEntity(ServerLevel level, PhysicsPose pose, SubLevelBlock block) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(Component.literal("PhysX sublevel"));
        entity.setCustomNameVisible(false);
        applyInitialVisualState(entity, pose, block);
        return entity;
    }

    private void syncVisualEntity(Display.BlockDisplay entity, PhysicsPose pose, SubLevelBlock block) {
        PhysicsVector position = pose.position();
        entity.setPos(position.x(), position.y(), position.z());
        setDisplayTransformation(entity, visualTransformation(pose, block));
    }

    private void applyInitialVisualState(Display.BlockDisplay entity, PhysicsPose pose, SubLevelBlock block) {
        CompoundTag tag = new CompoundTag();
        PhysicsVector position = pose.position();
        tag.put("Pos", doubleList(position.x(), position.y(), position.z()));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(block.blockState()));
        tag.putFloat("width", 1.0F);
        tag.putFloat("height", 1.0F);
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.2F);
        tag.putFloat("shadow_strength", 0.4F);
        tag.putInt("interpolation_duration", 0);
        tag.putInt("teleport_duration", 1);
        encodeVisualTransformation(pose, block).ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private Optional<net.minecraft.nbt.Tag> encodeVisualTransformation(PhysicsPose pose, SubLevelBlock block) {
        return Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, visualTransformation(pose, block))
                .resultOrPartial(message -> PhysX4mc.LOGGER.warn("Failed to encode sublevel display transformation: {}", message));
    }

    private static Transformation visualTransformation(PhysicsPose pose, SubLevelBlock block) {
        Quaternionf rotation = toJomlQuaternion(pose.rotation());
        Vector3f translation = vector(block.visualLocalOrigin()).rotate(new Quaternionf(rotation));
        return new Transformation(
                translation,
                rotation,
                new Vector3f(1.0F, 1.0F, 1.0F),
                new Quaternionf()
        );
    }

    private void discardVisuals(ServerLevel level, PhysicsSubLevel subLevel) {
        for (PhysicsSubLevel.VisualBinding visual : subLevel.visuals()) {
            Entity entity = level.getEntity(visual.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
        subLevel.visuals().clear();
    }

    private int discardVisualsForBlock(ServerLevel level, PhysicsSubLevel subLevel, BlockPos localPos) {
        int removed = 0;
        for (PhysicsSubLevel.VisualBinding visual : List.copyOf(subLevel.visuals())) {
            if (!visual.block().localPos().equals(localPos)) {
                continue;
            }
            Entity entity = level.getEntity(visual.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
            subLevel.visuals().remove(visual);
            removed++;
        }
        return removed;
    }

    private void removeStaleLevelEntries(ServerLevel level) {
        for (Map.Entry<SubLevelId, PhysicsSubLevel> entry : List.copyOf(subLevels.entrySet())) {
            if (entry.getValue().levelKey().equals(level.dimension())) {
                discardVisuals(level, entry.getValue());
                subLevels.remove(entry.getKey());
            }
        }
    }

    private static SubLevelSnapshot snapshot(MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        return new SubLevelSnapshot(
                subLevel.id(),
                subLevel.levelKey(),
                body,
                subLevel.bounds(),
                subLevel.blocks(),
                subLevel.visuals().size(),
                subLevel.section().dirtyBlockCount()
        );
    }

    private static PhysicsVector halfExtents(AABB bounds) {
        return new PhysicsVector(
                (bounds.maxX - bounds.minX) * 0.5D,
                (bounds.maxY - bounds.minY) * 0.5D,
                (bounds.maxZ - bounds.minZ) * 0.5D
        );
    }

    private static boolean isPositive(PhysicsVector vector) {
        return vector.x() > 0.0D && vector.y() > 0.0D && vector.z() > 0.0D;
    }

    private static Optional<Double> intersectBlock(PhysicsVector origin, PhysicsVector direction, SubLevelBlock block, double maxDistance) {
        AABB bounds = block.localCollisionBounds();
        PhysicsVector blockOrigin = block.visualLocalOrigin();
        AABB bodyLocalBounds = new AABB(
                blockOrigin.x() + bounds.minX,
                blockOrigin.y() + bounds.minY,
                blockOrigin.z() + bounds.minZ,
                blockOrigin.x() + bounds.maxX,
                blockOrigin.y() + bounds.maxY,
                blockOrigin.z() + bounds.maxZ
        );
        return intersectAabb(origin, direction, bodyLocalBounds, maxDistance);
    }

    private static Optional<Double> intersectAabb(PhysicsVector origin, PhysicsVector direction, AABB bounds, double maxDistance) {
        RayInterval x = clipAxis(origin.x(), direction.x(), bounds.minX, bounds.maxX, 0.0D, maxDistance);
        if (x == null) {
            return Optional.empty();
        }
        RayInterval y = clipAxis(origin.y(), direction.y(), bounds.minY, bounds.maxY, x.min(), x.max());
        if (y == null) {
            return Optional.empty();
        }
        RayInterval z = clipAxis(origin.z(), direction.z(), bounds.minZ, bounds.maxZ, y.min(), y.max());
        if (z == null) {
            return Optional.empty();
        }
        return Optional.of(z.min());
    }

    private static RayInterval clipAxis(double origin, double direction, double min, double max, double tMin, double tMax) {
        if (Math.abs(direction) < 1.0E-12D) {
            return origin >= min && origin <= max ? new RayInterval(tMin, tMax) : null;
        }
        double invDirection = 1.0D / direction;
        double first = (min - origin) * invDirection;
        double second = (max - origin) * invDirection;
        if (first > second) {
            double tmp = first;
            first = second;
            second = tmp;
        }
        double clippedMin = Math.max(tMin, first);
        double clippedMax = Math.min(tMax, second);
        return clippedMin <= clippedMax ? new RayInterval(clippedMin, clippedMax) : null;
    }

    private static PhysicsVector normalize(PhysicsVector vector) {
        double length = Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
        if (length <= 1.0E-12D || Double.isNaN(length)) {
            throw new IllegalArgumentException("Ray direction must be non-zero");
        }
        return new PhysicsVector(vector.x() / length, vector.y() / length, vector.z() / length);
    }

    private static PhysicsVector add(PhysicsVector first, PhysicsVector second) {
        return new PhysicsVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static PhysicsVector scale(PhysicsVector vector, double scalar) {
        return new PhysicsVector(vector.x() * scalar, vector.y() * scalar, vector.z() * scalar);
    }

    private static void refreshTerrainAround(ServerLevel level, List<SubLevelBlock> blocks) {
        for (SubLevelBlock block : blocks) {
            ServerPhysicsRuntime.INSTANCE.refreshTerrainCollisionAt(level, block.sourcePos());
            for (Direction direction : Direction.values()) {
                ServerPhysicsRuntime.INSTANCE.refreshTerrainCollisionAt(level, block.sourcePos().relative(direction));
            }
        }
    }

    private static void setDisplayTransformation(Display.BlockDisplay entity, Transformation transformation) {
        if (DISPLAY_SET_TRANSFORMATION == null) {
            return;
        }
        try {
            DISPLAY_SET_TRANSFORMATION.invoke(entity, transformation);
        } catch (Throwable ignored) {
            // Position sync still keeps the sublevel proxy approximately useful.
        }
    }

    private static MethodHandle findDisplaySetTransformation() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup());
            return lookup.findVirtual(Display.class, "setTransformation", MethodType.methodType(void.class, Transformation.class));
        } catch (ReflectiveOperationException exception) {
            PhysX4mc.LOGGER.warn("Display#setTransformation is unavailable; sublevel visuals will only sync position", exception);
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

    private static Vector3f vector(PhysicsVector vector) {
        return new Vector3f(
                (float) vector.x(),
                (float) vector.y(),
                (float) vector.z()
        );
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

    private static String describePos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String describeBounds(SubLevelBounds bounds) {
        return describePos(bounds.minSourcePos()) + " to " + describePos(bounds.maxSourcePos());
    }

    private record AggregateShape(PhysicsVector center, PhysicsVector halfExtents) {
        private AggregateShape {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(halfExtents, "halfExtents");
        }
    }

    private record RayInterval(double min, double max) {
    }
}
