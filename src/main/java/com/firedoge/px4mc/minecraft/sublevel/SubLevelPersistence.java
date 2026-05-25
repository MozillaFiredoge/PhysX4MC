package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.firedoge.px4mc.mechanics.MechanicsWorld;
import com.firedoge.px4mc.minecraft.scene.ServerPhysicsRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.SavedTick;
import net.neoforged.neoforge.common.IOUtilities;

public final class SubLevelPersistence {
    private static final int DATA_VERSION = 1;
    private static final int CAPTURE_INTERVAL_TICKS = 100;
    private static final int RESTORE_TERRAIN_CHUNK_RADIUS = 1;
    private static final double RESTORE_Y_MARGIN = 512.0D;
    private static final double MAX_RESTORED_LINEAR_SPEED = 256.0D;
    private static final String DATA_NAME = PhysX4mc.MODID + "_sublevels";
    private static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, Data::load);
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    private static int captureTick;
    private static boolean serverStopping;

    private SubLevelPersistence() {
    }

    public static void startServer() {
        serverStopping = false;
        captureTick = 0;
    }

    public static void restore(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        for (ServerLevel level : server.getAllLevels()) {
            restore(level);
        }
    }

    public static void capturePeriodically(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (serverStopping) {
            return;
        }
        captureTick++;
        if (captureTick < CAPTURE_INTERVAL_TICKS) {
            return;
        }
        captureTick = 0;
        if (capture(server, false)) {
            for (ServerLevel level : server.getAllLevels()) {
                level.getDataStorage().save();
            }
            IOUtilities.waitUntilIOWorkerComplete();
        }
    }

    public static void captureBeforeLevelSave(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (serverStopping) {
            return;
        }
        if (capture(level)) {
            level.getDataStorage().save();
            IOUtilities.waitUntilIOWorkerComplete();
        }
    }

    public static void flush(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        capture(server, true);
        serverStopping = true;
        for (ServerLevel level : server.getAllLevels()) {
            level.getDataStorage().save();
        }
        IOUtilities.waitUntilIOWorkerComplete();
    }

    private static void restore(ServerLevel level) {
        Data data = data(level);
        if (data.restored()) {
            return;
        }
        if (!data.restoreLoadLogged()) {
            PhysX4mc.LOGGER.info(
                    "Loaded {} persisted sublevel records for {}",
                    data.size(),
                    level.dimension().location()
            );
            data.markRestoreLoadLogged();
        }
        if (data.isEmpty()) {
            data.markRestored(false);
            return;
        }

        ServerSubLevelContainer container = SubLevelContainers.requireServer(level);
        if (!container.isEmpty()) {
            data.markRestored(false);
            return;
        }

        List<StoredSubLevel> storedSubLevels = new ArrayList<>(data.size());
        int failed = 0;
        int deferred = 0;
        for (CompoundTag subLevelTag : data.subLevels()) {
            try {
                StoredSubLevel stored = readSubLevel(level, subLevelTag);
                if (!isRestoreTerrainLoadedAround(level, stored)) {
                    deferred++;
                }
                storedSubLevels.add(stored);
            } catch (RuntimeException exception) {
                failed++;
                PhysX4mc.LOGGER.warn("Failed to read persisted sublevel in {}", level.dimension().location(), exception);
            }
        }
        if (deferred > 0) {
            PhysX4mc.LOGGER.debug(
                    "Deferred restore of {} persisted sublevels for {} until nearby terrain chunks are loaded",
                    deferred,
                    level.dimension().location()
            );
            return;
        }

        int restored = 0;
        RESTORING.set(true);
        try {
            for (StoredSubLevel stored : storedSubLevels) {
                try {
                    if (restoreSubLevel(level, container, stored)) {
                        restored++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    PhysX4mc.LOGGER.warn("Failed to restore persisted sublevel in {}", level.dimension().location(), exception);
                }
            }
        } finally {
            RESTORING.set(false);
        }

        data.markRestored(failed > 0);
        if (restored > 0 || failed > 0) {
            PhysX4mc.LOGGER.info(
                    "Restored {} persisted sublevels for {}{}",
                    restored,
                    level.dimension().location(),
                    failed == 0 ? "" : " (" + failed + " failed)"
            );
        }
    }

    private static boolean restoreSubLevel(ServerLevel level, ServerSubLevelContainer container, StoredSubLevel stored) {
        ServerPhysicsRuntime.INSTANCE.buildTerrainCollisionAround(
                level,
                restoreCenter(stored),
                RESTORE_TERRAIN_CHUNK_RADIUS
        );
        MechanicsWorld world = PhysX4mc.api().world(level);
        MechanicsBodySnapshot body = world.createDynamicCompoundBox(SubLevelAssembler.compoundDefinition(
                stored.pose(),
                stored.blocks(),
                stored.mass()
        ));
        world.setLinearVelocity(body.id(), stored.linearVelocity());

        PhysicsSubLevel subLevel = new PhysicsSubLevel(
                stored.id(),
                level.dimension(),
                stored.plot(),
                body.id(),
                stored.bounds(),
                stored.blocks()
        );
        try {
            container.add(subLevel, false);
            restoreScheduledTicks(level, stored.plot(), stored.scheduledTicks());
            container.requestPlotBlockUpdatePrime(subLevel);
            subLevel.activate();
            return true;
        } catch (RuntimeException exception) {
            container.remove(subLevel.id());
            world.removeBody(body.id());
            throw exception;
        }
    }

    private static boolean isRestoreTerrainLoadedAround(ServerLevel level, StoredSubLevel stored) {
        BlockPos center = restoreCenter(stored);
        int centerChunkX = Math.floorDiv(center.getX(), 16);
        int centerChunkZ = Math.floorDiv(center.getZ(), 16);
        for (int radius = 0; radius <= RESTORE_TERRAIN_CHUNK_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    if (!level.hasChunk(centerChunkX + dx, centerChunkZ + dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static BlockPos restoreCenter(StoredSubLevel stored) {
        PhysicsVector position = stored.pose().position();
        return BlockPos.containing(position.x(), position.y(), position.z());
    }

    private static boolean capture(MinecraftServer server, boolean force) {
        if (RESTORING.get()) {
            return false;
        }
        boolean changed = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (force || shouldCapture(level)) {
                changed |= capture(level);
            }
        }
        return changed;
    }

    private static boolean shouldCapture(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainers.server(level).orElse(null);
        if (container != null && !container.isEmpty()) {
            return true;
        }

        Data existing = existingData(level);
        return existing != null && existing.restored() && !existing.restoreHadFailures() && !existing.isEmpty();
    }

    private static boolean capture(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainers.server(level).orElse(null);
        Data existing = existingData(level);
        if ((container == null || container.isEmpty()) && (existing == null || existing.isEmpty())) {
            return false;
        }
        if (existing != null && existing.restoreHadFailures()) {
            return false;
        }

        List<PhysicsSubLevel> subLevels = container == null ? List.of() : container.subLevels();
        if (subLevels.isEmpty()) {
            return data(level).replaceSubLevels(List.of());
        }

        MechanicsWorld world = PhysX4mc.api().existingWorld(level).orElse(null);
        if (world == null) {
            return false;
        }

        List<CompoundTag> saved = new ArrayList<>();
        for (PhysicsSubLevel subLevel : subLevels) {
            if (subLevel.state() == SubLevelLifecycleState.REMOVING) {
                continue;
            }
            container.refreshBlockEntityTags(subLevel);
            Optional<MechanicsBodySnapshot> maybeBody = world.snapshot(subLevel.bodyId());
            if (maybeBody.isEmpty()) {
                return false;
            }
            MechanicsBodySnapshot body = maybeBody.get();
            if (!isReasonablePose(level, body.pose()) || !isReasonableLinearVelocity(body.linearVelocity())) {
                PhysX4mc.LOGGER.warn(
                        "Skipping persistence update for sublevel {} because body pose/velocity is invalid: position={}, velocity={}",
                        subLevel.id(),
                        describe(body.pose().position()),
                        describe(body.linearVelocity())
                );
                return false;
            }
            saved.add(writeSubLevel(level, container, subLevel, body));
        }
        return data(level).replaceSubLevels(saved);
    }

    private static CompoundTag writeSubLevel(
            ServerLevel level,
            ServerSubLevelContainer container,
            PhysicsSubLevel subLevel,
            MechanicsBodySnapshot body
    ) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", subLevel.id().value());
        tag.put("bounds", writeBounds(subLevel.bounds()));
        tag.put("plot", writePlot(subLevel.plot()));
        tag.put("pose", writePose(body.pose()));
        tag.put("linear_velocity", writeVector(body.linearVelocity()));
        tag.putFloat("mass", body.mass());
        tag.put("scheduled_ticks", writeScheduledTicks(level, container, subLevel));

        ListTag blocks = new ListTag();
        for (SubLevelBlock block : subLevel.blocks()) {
            blocks.add(writeBlock(block));
        }
        tag.put("blocks", blocks);
        return tag;
    }

    private static CompoundTag writeBlock(SubLevelBlock block) {
        CompoundTag tag = new CompoundTag();
        tag.put("source_pos", NbtUtils.writeBlockPos(block.sourcePos()));
        tag.put("local_pos", NbtUtils.writeBlockPos(block.localPos()));
        tag.put("state", NbtUtils.writeBlockState(block.blockState()));
        tag.put("local_collision_bounds", writeAabb(block.localCollisionBounds()));
        tag.put("visual_local_origin", writeVector(block.visualLocalOrigin()));

        ListTag collisionBoxes = new ListTag();
        for (AABB box : block.localCollisionBoxes()) {
            collisionBoxes.add(writeAabb(box));
        }
        tag.put("local_collision_boxes", collisionBoxes);

        CompoundTag blockEntityTag = block.blockEntityTag();
        if (blockEntityTag != null) {
            tag.put("block_entity", blockEntityTag.copy());
        }
        return tag;
    }

    private static StoredSubLevel readSubLevel(ServerLevel level, CompoundTag tag) {
        SubLevelId id = new SubLevelId(tag.getUUID("id"));
        SubLevelBounds bounds = readBounds(tag.getCompound("bounds"));
        SubLevelPlot plot = readPlot(tag.getCompound("plot"));
        PhysicsPose pose = readPose(tag.getCompound("pose"));
        PhysicsVector linearVelocity = readVector(tag.getCompound("linear_velocity"));
        float mass = tag.getFloat("mass");

        HolderLookup.RegistryLookup<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        ListTag blockTags = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<SubLevelBlock> blocks = new ArrayList<>(blockTags.size());
        for (int i = 0; i < blockTags.size(); i++) {
            blocks.add(readBlock(blockTags.getCompound(i), blockLookup));
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Persisted sublevel " + id + " has no blocks");
        }
        if (mass <= 0.0F) {
            throw new IllegalArgumentException("Persisted sublevel " + id + " has invalid mass " + mass);
        }
        pose = sanitizePose(level, id, pose, blocks);
        linearVelocity = sanitizeLinearVelocity(id, linearVelocity);
        StoredScheduledTicks scheduledTicks = readScheduledTicks(tag.getCompound("scheduled_ticks"));
        return new StoredSubLevel(id, bounds, plot, pose, linearVelocity, mass, List.copyOf(blocks), scheduledTicks);
    }

    private static SubLevelBlock readBlock(CompoundTag tag, HolderLookup.RegistryLookup<Block> blockLookup) {
        BlockPos sourcePos = readBlockPos(tag, "source_pos");
        BlockPos localPos = readBlockPos(tag, "local_pos");
        BlockState blockState = NbtUtils.readBlockState(blockLookup, tag.getCompound("state"));
        AABB localCollisionBounds = readAabb(tag.getCompound("local_collision_bounds"));
        PhysicsVector visualLocalOrigin = readVector(tag.getCompound("visual_local_origin"));
        ListTag collisionBoxTags = tag.getList("local_collision_boxes", Tag.TAG_COMPOUND);
        List<AABB> collisionBoxes = new ArrayList<>(collisionBoxTags.size());
        for (int i = 0; i < collisionBoxTags.size(); i++) {
            collisionBoxes.add(readAabb(collisionBoxTags.getCompound(i)));
        }
        CompoundTag blockEntityTag = tag.contains("block_entity", Tag.TAG_COMPOUND)
                ? tag.getCompound("block_entity").copy()
                : null;
        return new SubLevelBlock(
                sourcePos,
                localPos,
                blockState,
                localCollisionBounds,
                List.copyOf(collisionBoxes),
                visualLocalOrigin,
                blockEntityTag
        );
    }

    private static CompoundTag writeBounds(SubLevelBounds bounds) {
        CompoundTag tag = new CompoundTag();
        tag.put("source_origin", NbtUtils.writeBlockPos(bounds.sourceOrigin()));
        tag.put("min_source_pos", NbtUtils.writeBlockPos(bounds.minSourcePos()));
        tag.put("max_source_pos", NbtUtils.writeBlockPos(bounds.maxSourcePos()));
        return tag;
    }

    private static SubLevelBounds readBounds(CompoundTag tag) {
        return new SubLevelBounds(
                readBlockPos(tag, "source_origin"),
                readBlockPos(tag, "min_source_pos"),
                readBlockPos(tag, "max_source_pos")
        );
    }

    private static CompoundTag writePlot(SubLevelPlot plot) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", plot.id().value());
        tag.putInt("origin_chunk_x", plot.originChunk().x);
        tag.putInt("origin_chunk_z", plot.originChunk().z);
        tag.putInt("section_y", plot.sectionY());
        tag.putInt("chunk_span", plot.chunkSpan());
        tag.putInt("section_span", plot.sectionSpan());
        return tag;
    }

    private static SubLevelPlot readPlot(CompoundTag tag) {
        return SubLevelPlot.sections(
                new SubLevelPlotId(tag.getLong("id")),
                new ChunkPos(tag.getInt("origin_chunk_x"), tag.getInt("origin_chunk_z")),
                tag.getInt("section_y"),
                tag.getInt("chunk_span"),
                tag.getInt("section_span")
        );
    }

    private static CompoundTag writeScheduledTicks(
            ServerLevel level,
            ServerSubLevelContainer container,
            PhysicsSubLevel subLevel
    ) {
        long gameTime = level.getLevelData().getGameTime();
        ListTag chunks = new ListTag();
        for (ChunkPos chunkPos : subLevel.plot().chunkPositions()) {
            container.plotChunk(chunkPos).ifPresent(chunk -> {
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putInt("x", chunkPos.x);
                chunkTag.putInt("z", chunkPos.z);
                chunkTag.put(
                        "block_ticks",
                        chunk.getTicksForSerialization().blocks().save(
                                gameTime,
                                block -> BuiltInRegistries.BLOCK.getKey(block).toString()
                        )
                );
                chunkTag.put(
                        "fluid_ticks",
                        chunk.getTicksForSerialization().fluids().save(
                                gameTime,
                                fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString()
                        )
                );
                chunks.add(chunkTag);
            });
        }

        CompoundTag tag = new CompoundTag();
        tag.put("chunks", chunks);
        return tag;
    }

    private static StoredScheduledTicks readScheduledTicks(CompoundTag tag) {
        ListTag chunkTags = tag.getList("chunks", Tag.TAG_COMPOUND);
        List<StoredChunkTicks> chunks = new ArrayList<>(chunkTags.size());
        for (int i = 0; i < chunkTags.size(); i++) {
            CompoundTag chunkTag = chunkTags.getCompound(i);
            chunks.add(new StoredChunkTicks(
                    new ChunkPos(chunkTag.getInt("x"), chunkTag.getInt("z")),
                    chunkTag.getList("block_ticks", Tag.TAG_COMPOUND).copy(),
                    chunkTag.getList("fluid_ticks", Tag.TAG_COMPOUND).copy()
            ));
        }
        return new StoredScheduledTicks(chunks);
    }

    private static void restoreScheduledTicks(ServerLevel level, SubLevelPlot plot, StoredScheduledTicks scheduledTicks) {
        long gameTime = level.getLevelData().getGameTime();
        long[] subTickOrder = new long[] {0L};
        for (StoredChunkTicks chunk : scheduledTicks.chunks()) {
            if (!plot.containsChunk(chunk.chunkPos())) {
                continue;
            }
            SavedTick.loadTickList(
                    chunk.blockTicks(),
                    id -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(id)),
                    chunk.chunkPos(),
                    savedTick -> {
                        if (plot.containsPlotBlockPos(savedTick.pos())) {
                            level.getBlockTicks().schedule(savedTick.unpack(gameTime, subTickOrder[0]++));
                        }
                    }
            );
            SavedTick.loadTickList(
                    chunk.fluidTicks(),
                    id -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(id)),
                    chunk.chunkPos(),
                    savedTick -> {
                        if (plot.containsPlotBlockPos(savedTick.pos())) {
                            level.getFluidTicks().schedule(savedTick.unpack(gameTime, subTickOrder[0]++));
                        }
                    }
            );
        }
    }

    private static CompoundTag writePose(PhysicsPose pose) {
        CompoundTag tag = new CompoundTag();
        tag.put("position", writeVector(pose.position()));
        tag.put("rotation", writeQuaternion(pose.rotation()));
        return tag;
    }

    private static PhysicsPose readPose(CompoundTag tag) {
        return new PhysicsPose(
                readVector(tag.getCompound("position")),
                readQuaternion(tag.getCompound("rotation"))
        );
    }

    private static CompoundTag writeVector(PhysicsVector vector) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vector.x());
        tag.putDouble("y", vector.y());
        tag.putDouble("z", vector.z());
        return tag;
    }

    private static PhysicsVector readVector(CompoundTag tag) {
        return new PhysicsVector(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
    }

    private static CompoundTag writeQuaternion(PhysicsQuaternion quaternion) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", quaternion.x());
        tag.putDouble("y", quaternion.y());
        tag.putDouble("z", quaternion.z());
        tag.putDouble("w", quaternion.w());
        return tag;
    }

    private static PhysicsQuaternion readQuaternion(CompoundTag tag) {
        return new PhysicsQuaternion(
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                tag.getDouble("w")
        );
    }

    private static CompoundTag writeAabb(AABB box) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("min_x", box.minX);
        tag.putDouble("min_y", box.minY);
        tag.putDouble("min_z", box.minZ);
        tag.putDouble("max_x", box.maxX);
        tag.putDouble("max_y", box.maxY);
        tag.putDouble("max_z", box.maxZ);
        return tag;
    }

    private static AABB readAabb(CompoundTag tag) {
        return new AABB(
                tag.getDouble("min_x"),
                tag.getDouble("min_y"),
                tag.getDouble("min_z"),
                tag.getDouble("max_x"),
                tag.getDouble("max_y"),
                tag.getDouble("max_z")
        );
    }

    private static BlockPos readBlockPos(CompoundTag tag, String key) {
        return NbtUtils.readBlockPos(tag, key)
                .orElseThrow(() -> new IllegalArgumentException("Missing block position tag: " + key));
    }

    private static PhysicsPose sanitizePose(
            ServerLevel level,
            SubLevelId id,
            PhysicsPose pose,
            List<SubLevelBlock> blocks
    ) {
        if (isReasonablePose(level, pose)) {
            return pose;
        }

        PhysicsPose fallback = new PhysicsPose(originalBodyCenter(blocks), PhysicsQuaternion.IDENTITY);
        PhysX4mc.LOGGER.warn(
                "Persisted sublevel {} had an invalid pose at {}; restoring near original body center {}",
                id,
                describe(pose.position()),
                describe(fallback.position())
        );
        return fallback;
    }

    private static boolean isReasonablePose(ServerLevel level, PhysicsPose pose) {
        PhysicsVector position = pose.position();
        PhysicsQuaternion rotation = pose.rotation();
        return finite(position.x())
                && finite(position.y())
                && finite(position.z())
                && finite(rotation.x())
                && finite(rotation.y())
                && finite(rotation.z())
                && finite(rotation.w())
                && position.y() >= level.getMinBuildHeight() - RESTORE_Y_MARGIN
                && position.y() <= level.getMaxBuildHeight() + RESTORE_Y_MARGIN;
    }

    private static PhysicsVector sanitizeLinearVelocity(SubLevelId id, PhysicsVector velocity) {
        if (isReasonableLinearVelocity(velocity)) {
            return velocity;
        }
        PhysX4mc.LOGGER.warn(
                "Persisted sublevel {} had an invalid linear velocity {}; restoring with zero velocity",
                id,
                describe(velocity)
        );
        return PhysicsVector.ZERO;
    }

    private static boolean isReasonableLinearVelocity(PhysicsVector velocity) {
        if (!finite(velocity.x()) || !finite(velocity.y()) || !finite(velocity.z())) {
            return false;
        }
        double speedSqr = velocity.x() * velocity.x() + velocity.y() * velocity.y() + velocity.z() * velocity.z();
        return speedSqr <= MAX_RESTORED_LINEAR_SPEED * MAX_RESTORED_LINEAR_SPEED;
    }

    private static PhysicsVector originalBodyCenter(List<SubLevelBlock> blocks) {
        SubLevelBlock first = blocks.getFirst();
        return new PhysicsVector(
                first.sourcePos().getX() - first.visualLocalOrigin().x(),
                first.sourcePos().getY() - first.visualLocalOrigin().y(),
                first.sourcePos().getZ() - first.visualLocalOrigin().z()
        );
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static String describe(PhysicsVector vector) {
        return "(" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")";
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static Data existingData(ServerLevel level) {
        return level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    private record StoredSubLevel(
            SubLevelId id,
            SubLevelBounds bounds,
            SubLevelPlot plot,
            PhysicsPose pose,
            PhysicsVector linearVelocity,
            float mass,
            List<SubLevelBlock> blocks,
            StoredScheduledTicks scheduledTicks
    ) {
        private StoredSubLevel {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(plot, "plot");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(linearVelocity, "linearVelocity");
            Objects.requireNonNull(scheduledTicks, "scheduledTicks");
            blocks = List.copyOf(blocks);
        }
    }

    private record StoredScheduledTicks(List<StoredChunkTicks> chunks) {
        private StoredScheduledTicks {
            chunks = List.copyOf(chunks);
        }
    }

    private record StoredChunkTicks(ChunkPos chunkPos, ListTag blockTicks, ListTag fluidTicks) {
        private StoredChunkTicks {
            Objects.requireNonNull(chunkPos, "chunkPos");
            Objects.requireNonNull(blockTicks, "blockTicks");
            Objects.requireNonNull(fluidTicks, "fluidTicks");
            blockTicks = blockTicks.copy();
            fluidTicks = fluidTicks.copy();
        }
    }

    private static final class Data extends SavedData {
        private List<CompoundTag> subLevels = List.of();
        private boolean restored;
        private boolean restoreHadFailures;
        private boolean restoreLoadLogged;

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            ListTag subLevels = tag.getList("sublevels", Tag.TAG_COMPOUND);
            List<CompoundTag> loaded = new ArrayList<>(subLevels.size());
            for (int i = 0; i < subLevels.size(); i++) {
                loaded.add(subLevels.getCompound(i).copy());
            }
            data.subLevels = List.copyOf(loaded);
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("version", DATA_VERSION);
            ListTag subLevelTags = new ListTag();
            for (CompoundTag subLevel : subLevels) {
                subLevelTags.add(subLevel.copy());
            }
            tag.put("sublevels", subLevelTags);
            return tag;
        }

        private boolean isEmpty() {
            return subLevels.isEmpty();
        }

        private int size() {
            return subLevels.size();
        }

        private List<CompoundTag> subLevels() {
            return subLevels.stream()
                    .map(CompoundTag::copy)
                    .toList();
        }

        private boolean restored() {
            return restored;
        }

        private boolean restoreHadFailures() {
            return restoreHadFailures;
        }

        private boolean restoreLoadLogged() {
            return restoreLoadLogged;
        }

        private void markRestoreLoadLogged() {
            restoreLoadLogged = true;
        }

        private void markRestored(boolean restoreHadFailures) {
            this.restored = true;
            this.restoreHadFailures = restoreHadFailures;
        }

        private boolean replaceSubLevels(List<CompoundTag> subLevels) {
            List<CompoundTag> copy = subLevels.stream()
                    .map(CompoundTag::copy)
                    .toList();
            if (this.subLevels.equals(copy)) {
                return false;
            }
            this.subLevels = copy;
            setDirty();
            return true;
        }
    }
}
