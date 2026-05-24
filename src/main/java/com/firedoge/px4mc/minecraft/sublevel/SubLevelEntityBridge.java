package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsVector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

public final class SubLevelEntityBridge {
    private static final double EPSILON = 1.0E-7D;
    private static final double SEARCH_EPSILON = 1.0E-5D;
    private static final int MAX_ENTITY_INSIDE_BLOCKS = 512;
    private static final ThreadLocal<Boolean> PROJECTING_QUERY = ThreadLocal.withInitial(() -> false);

    private SubLevelEntityBridge() {
    }

    public static boolean isProjectingQuery() {
        return PROJECTING_QUERY.get();
    }

    public static void tickEntityInside(ServerLevel level, ServerSubLevelContainer container) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(container, "container");
        if (container.isEmpty()) {
            return;
        }

        for (PhysicsSubLevel subLevel : container.subLevels()) {
            Optional<QueryTarget> maybeTarget = queryTarget(level, subLevel);
            if (maybeTarget.isEmpty()) {
                continue;
            }
            QueryTarget target = maybeTarget.get();
            AABB plotBounds = plotBounds(subLevel.plot()).inflate(EPSILON);
            AABB worldSearchBounds = plotAabbToWorldAabb(target, plotBounds).inflate(SEARCH_EPSILON);
            List<Entity> entities = queryWorldEntities(level, (Entity) null, worldSearchBounds, entity -> !entity.isRemoved());
            for (Entity entity : entities) {
                if (entity.level() != level || entity.isRemoved()) {
                    continue;
                }
                AABB projectedBounds = worldAabbToPlotAabb(target, entity.getBoundingBox()).inflate(EPSILON);
                if (projectedBounds.intersects(plotBounds)) {
                    dispatchEntityInside(level, subLevel, entity, projectedBounds);
                }
            }
        }
    }

    public static List<Entity> projectedEntities(
            ServerLevel level,
            @Nullable Entity excluded,
            AABB plotBounds,
            Predicate<? super Entity> predicate,
            List<Entity> existing
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plotBounds, "plotBounds");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(existing, "existing");
        if (PROJECTING_QUERY.get()) {
            return List.of();
        }

        List<QueryTarget> targets = queryTargets(level, plotBounds);
        if (targets.isEmpty()) {
            return List.of();
        }

        Set<Entity> seen = identitySet(existing);
        List<Entity> projected = new ArrayList<>();
        for (QueryTarget target : targets) {
            AABB worldSearchBounds = plotAabbToWorldAabb(target, plotBounds).inflate(SEARCH_EPSILON);
            for (Entity entity : queryWorldEntities(level, excluded, worldSearchBounds, predicate)) {
                if (!seen.add(entity)) {
                    continue;
                }
                if (worldAabbToPlotAabb(target, entity.getBoundingBox()).intersects(plotBounds)) {
                    projected.add(entity);
                }
            }
        }
        return List.copyOf(projected);
    }

    public static <T extends Entity> List<T> projectedEntities(
            ServerLevel level,
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB plotBounds,
            Predicate<? super T> predicate,
            List<T> existing
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entityTypeTest, "entityTypeTest");
        Objects.requireNonNull(plotBounds, "plotBounds");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(existing, "existing");
        if (PROJECTING_QUERY.get()) {
            return List.of();
        }

        List<QueryTarget> targets = queryTargets(level, plotBounds);
        if (targets.isEmpty()) {
            return List.of();
        }

        Set<T> seen = identitySet(existing);
        List<T> projected = new ArrayList<>();
        for (QueryTarget target : targets) {
            AABB worldSearchBounds = plotAabbToWorldAabb(target, plotBounds).inflate(SEARCH_EPSILON);
            for (T entity : queryWorldEntities(level, entityTypeTest, worldSearchBounds, predicate)) {
                if (!seen.add(entity)) {
                    continue;
                }
                if (worldAabbToPlotAabb(target, entity.getBoundingBox()).intersects(plotBounds)) {
                    projected.add(entity);
                }
            }
        }
        return List.copyOf(projected);
    }

    private static void dispatchEntityInside(ServerLevel level, PhysicsSubLevel subLevel, Entity entity, AABB projectedBounds) {
        BlockPos min = BlockPos.containing(
                projectedBounds.minX + EPSILON,
                projectedBounds.minY + EPSILON,
                projectedBounds.minZ + EPSILON
        );
        BlockPos max = BlockPos.containing(
                projectedBounds.maxX - EPSILON,
                projectedBounds.maxY - EPSILON,
                projectedBounds.maxZ - EPSILON
        );
        int count = (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (count <= 0 || count > MAX_ENTITY_INSIDE_BLOCKS) {
            return;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    pos.set(x, y, z);
                    if (!subLevel.plot().containsPlotBlockPos(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        state.entityInside(level, pos, entity);
                    }
                }
            }
        }
    }

    private static List<QueryTarget> queryTargets(ServerLevel level, AABB plotBounds) {
        ServerSubLevelContainer container = SubLevelContainers.server(level).orElse(null);
        if (container == null || container.isEmpty()) {
            return List.of();
        }

        int minChunkX = SectionPos.blockToSectionCoord(blockMin(plotBounds.minX));
        int maxChunkX = SectionPos.blockToSectionCoord(blockMax(plotBounds.maxX));
        int minChunkZ = SectionPos.blockToSectionCoord(blockMin(plotBounds.minZ));
        int maxChunkZ = SectionPos.blockToSectionCoord(blockMax(plotBounds.maxZ));
        Map<SubLevelId, QueryTarget> targets = new LinkedHashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                container.subLevelAtChunk(new ChunkPos(chunkX, chunkZ))
                        .filter(subLevel -> intersectsPlot(subLevel.plot(), plotBounds))
                        .flatMap(subLevel -> queryTarget(level, subLevel))
                        .ifPresent(target -> targets.putIfAbsent(target.subLevel().id(), target));
            }
        }
        return List.copyOf(targets.values());
    }

    private static Optional<QueryTarget> queryTarget(ServerLevel level, PhysicsSubLevel subLevel) {
        return PhysX4mc.api().existingWorld(level)
                .flatMap(world -> world.snapshot(subLevel.bodyId()))
                .map(body -> new QueryTarget(subLevel, SubLevelTransform.from(body), bodyToPlotOrigin(subLevel)));
    }

    private static List<Entity> queryWorldEntities(
            ServerLevel level,
            @Nullable Entity excluded,
            AABB worldBounds,
            Predicate<? super Entity> predicate
    ) {
        PROJECTING_QUERY.set(true);
        try {
            return level.getEntities(excluded, worldBounds, predicate);
        } finally {
            PROJECTING_QUERY.set(false);
        }
    }

    private static <T extends Entity> List<T> queryWorldEntities(
            ServerLevel level,
            EntityTypeTest<Entity, T> entityTypeTest,
            AABB worldBounds,
            Predicate<? super T> predicate
    ) {
        PROJECTING_QUERY.set(true);
        try {
            return level.getEntities(entityTypeTest, worldBounds, predicate);
        } finally {
            PROJECTING_QUERY.set(false);
        }
    }

    private static AABB plotBounds(SubLevelPlot plot) {
        return new AABB(
                plot.minPlotX(),
                plot.minPlotY(),
                plot.minPlotZ(),
                plot.maxPlotX() + 1.0D,
                plot.maxPlotY() + 1.0D,
                plot.maxPlotZ() + 1.0D
        );
    }

    private static boolean intersectsPlot(SubLevelPlot plot, AABB plotBounds) {
        return plotBounds.maxX > plot.minPlotX()
                && plotBounds.minX < plot.maxPlotX() + 1.0D
                && plotBounds.maxY > plot.minPlotY()
                && plotBounds.minY < plot.maxPlotY() + 1.0D
                && plotBounds.maxZ > plot.minPlotZ()
                && plotBounds.minZ < plot.maxPlotZ() + 1.0D;
    }

    private static AABB plotAabbToWorldAabb(QueryTarget target, AABB plotBounds) {
        BoundsBuilder builder = new BoundsBuilder();
        forEachCorner(plotBounds, (x, y, z) -> builder.include(plotToWorld(target, x, y, z)));
        return builder.build();
    }

    private static AABB worldAabbToPlotAabb(QueryTarget target, AABB worldBounds) {
        BoundsBuilder builder = new BoundsBuilder();
        forEachCorner(worldBounds, (x, y, z) -> builder.include(worldToPlot(target, x, y, z)));
        return builder.build();
    }

    private static PhysicsVector plotToWorld(QueryTarget target, double plotX, double plotY, double plotZ) {
        SubLevelPlot plot = target.subLevel().plot();
        PhysicsVector bodyToPlotOrigin = target.bodyToPlotOrigin();
        return target.transform().localToWorld(new PhysicsVector(
                bodyToPlotOrigin.x() + plotX - plot.minPlotX(),
                bodyToPlotOrigin.y() + plotY - plot.minPlotY(),
                bodyToPlotOrigin.z() + plotZ - plot.minPlotZ()
        ));
    }

    private static PhysicsVector worldToPlot(QueryTarget target, double worldX, double worldY, double worldZ) {
        SubLevelPlot plot = target.subLevel().plot();
        PhysicsVector bodyToPlotOrigin = target.bodyToPlotOrigin();
        PhysicsVector local = target.transform().worldToLocal(new PhysicsVector(worldX, worldY, worldZ));
        return new PhysicsVector(
                local.x() - bodyToPlotOrigin.x() + plot.minPlotX(),
                local.y() - bodyToPlotOrigin.y() + plot.minPlotY(),
                local.z() - bodyToPlotOrigin.z() + plot.minPlotZ()
        );
    }

    private static PhysicsVector bodyToPlotOrigin(PhysicsSubLevel subLevel) {
        return subLevel.blocks().stream()
                .findFirst()
                .map(block -> new PhysicsVector(
                        block.visualLocalOrigin().x() - block.localPos().getX(),
                        block.visualLocalOrigin().y() - block.localPos().getY(),
                        block.visualLocalOrigin().z() - block.localPos().getZ()
                ))
                .orElse(PhysicsVector.ZERO);
    }

    private static int blockMin(double coordinate) {
        return Mth.floor(coordinate + EPSILON);
    }

    private static int blockMax(double coordinate) {
        return Mth.floor(coordinate - EPSILON);
    }

    private static <T extends Entity> Set<T> identitySet(List<? extends T> existing) {
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(existing);
        return seen;
    }

    private static void forEachCorner(AABB bounds, CornerConsumer consumer) {
        consumer.accept(bounds.minX, bounds.minY, bounds.minZ);
        consumer.accept(bounds.minX, bounds.minY, bounds.maxZ);
        consumer.accept(bounds.minX, bounds.maxY, bounds.minZ);
        consumer.accept(bounds.minX, bounds.maxY, bounds.maxZ);
        consumer.accept(bounds.maxX, bounds.minY, bounds.minZ);
        consumer.accept(bounds.maxX, bounds.minY, bounds.maxZ);
        consumer.accept(bounds.maxX, bounds.maxY, bounds.minZ);
        consumer.accept(bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    @FunctionalInterface
    private interface CornerConsumer {
        void accept(double x, double y, double z);
    }

    private record QueryTarget(
            PhysicsSubLevel subLevel,
            SubLevelTransform transform,
            PhysicsVector bodyToPlotOrigin
    ) {
        private QueryTarget {
            Objects.requireNonNull(subLevel, "subLevel");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(bodyToPlotOrigin, "bodyToPlotOrigin");
        }
    }

    private static final class BoundsBuilder {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void include(PhysicsVector point) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }

        private AABB build() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
