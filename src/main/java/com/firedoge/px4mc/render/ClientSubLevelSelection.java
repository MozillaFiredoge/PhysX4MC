package com.firedoge.px4mc.render;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.ClientTrackedSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelClientMetadata;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelId;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelTransform;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ClientSubLevelSelection {
    private static Optional<Result> lastResult = Optional.empty();

    private ClientSubLevelSelection() {
    }

    public static Optional<Result> lastResult() {
        return lastResult;
    }

    public static Optional<Result> update(Minecraft minecraft, ClientSubLevelContainer container) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(container, "container");
        if (minecraft.player == null) {
            lastResult = Optional.empty();
            return lastResult;
        }

        Vec3 origin = minecraft.player.getEyePosition();
        Vec3 look = minecraft.player.getLookAngle();
        double maxDistance = Math.max(1.0D, minecraft.player.blockInteractionRange());
        Vec3 end = origin.add(look.scale(maxDistance));
        Result best = null;

        for (ClientTrackedSubLevel subLevel : container.trackedSubLevels()) {
            if (!subLevel.finalized()) {
                continue;
            }
            Result result = pickSubLevel(container, subLevel, origin, end, maxDistance).orElse(null);
            if (result == null) {
                continue;
            }
            if (best == null || result.distance() < best.distance()) {
                best = result;
            }
        }

        lastResult = Optional.ofNullable(best);
        return lastResult;
    }

    private static Optional<Result> pickSubLevel(
            ClientSubLevelContainer container,
            ClientTrackedSubLevel subLevel,
            Vec3 worldStart,
            Vec3 worldEnd,
            double maxDistance
    ) {
        SubLevelTransform transform = SubLevelTransform.from(subLevel.pose());
        PhysicsVector localStartVector = transform.worldToLocal(toPhysics(worldStart));
        PhysicsVector localEndVector = transform.worldToLocal(toPhysics(worldEnd));
        SubLevelClientMetadata metadata = subLevel.metadata();
        Vec3 plotStart = bodyLocalToPlotSpace(localStartVector, metadata);
        Vec3 plotEnd = bodyLocalToPlotSpace(localEndVector, metadata);
        Result best = null;

        for (ChunkPos chunkPos : subLevel.loadedChunks()) {
            LevelChunk chunk = container.plotChunk(chunkPos).orElse(null);
            if (chunk == null) {
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section.hasOnlyAir()) {
                    continue;
                }
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                BlockPos sectionOrigin = SectionPos.of(chunkPos, sectionY).origin();
                BlockPos.MutableBlockPos plotPos = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) {
                                continue;
                            }
                            plotPos.setWithOffset(sectionOrigin, x, y, z);
                            BlockPos immutablePlotPos = plotPos.immutable();
                            if (!subLevel.plot().containsPlotBlockPos(immutablePlotPos)) {
                                continue;
                            }
                            VoxelShape shape = state.getShape(subLevel.levelView(), immutablePlotPos);
                            if (shape.isEmpty()) {
                                continue;
                            }
                            BlockHitResult hit = shape.clip(plotStart, plotEnd, immutablePlotPos);
                            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                                continue;
                            }
                            double distance = hit.getLocation().distanceTo(plotStart);
                            if (distance > maxDistance + 1.0E-6D || (best != null && distance >= best.distance())) {
                                continue;
                            }
                            best = new Result(subLevel.id(), immutablePlotPos, state, shape, hit, distance);
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Vec3 bodyLocalToPlotSpace(PhysicsVector bodyLocal, SubLevelClientMetadata metadata) {
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        return new Vec3(
                bodyLocal.x() - bodyToPlotOrigin.x() + metadata.plot().minPlotX(),
                bodyLocal.y() - bodyToPlotOrigin.y() + metadata.plot().minPlotY(),
                bodyLocal.z() - bodyToPlotOrigin.z() + metadata.plot().minPlotZ()
        );
    }

    public static Vec3 plotCenterToBodyLocal(BlockPos plotPos, SubLevelClientMetadata metadata) {
        Objects.requireNonNull(plotPos, "plotPos");
        Objects.requireNonNull(metadata, "metadata");
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        return new Vec3(
                bodyToPlotOrigin.x() + (plotPos.getX() - metadata.plot().minPlotX()) + 0.5D,
                bodyToPlotOrigin.y() + (plotPos.getY() - metadata.plot().minPlotY()) + 0.5D,
                bodyToPlotOrigin.z() + (plotPos.getZ() - metadata.plot().minPlotZ()) + 0.5D
        );
    }

    public static Vec3 plotCenterToWorld(BlockPos plotPos, ClientTrackedSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        Vec3 bodyLocal = plotToBodyLocal(Vec3.atCenterOf(plotPos), subLevel.metadata());
        PhysicsVector world = SubLevelTransform.from(subLevel.pose()).localToWorld(toPhysics(bodyLocal));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotToWorld(Vec3 plotPosition, ClientTrackedSubLevel subLevel) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        Objects.requireNonNull(subLevel, "subLevel");
        Vec3 bodyLocal = plotToBodyLocal(plotPosition, subLevel.metadata());
        PhysicsVector world = SubLevelTransform.from(subLevel.pose()).localToWorld(toPhysics(bodyLocal));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotToWorld(Vec3 plotPosition, SubLevelClientMetadata metadata) {
        Objects.requireNonNull(plotPosition, "plotPosition");
        Objects.requireNonNull(metadata, "metadata");
        Vec3 bodyLocal = plotToBodyLocal(plotPosition, metadata);
        PhysicsVector world = SubLevelTransform.from(metadata.pose()).localToWorld(toPhysics(bodyLocal));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotDirectionToWorld(Vec3 plotDirection, ClientTrackedSubLevel subLevel) {
        Objects.requireNonNull(plotDirection, "plotDirection");
        Objects.requireNonNull(subLevel, "subLevel");
        PhysicsVector world = SubLevelTransform.from(subLevel.pose()).localDirectionToWorld(toPhysics(plotDirection));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public static Vec3 plotDirectionToWorld(Vec3 plotDirection, SubLevelClientMetadata metadata) {
        Objects.requireNonNull(plotDirection, "plotDirection");
        Objects.requireNonNull(metadata, "metadata");
        PhysicsVector world = SubLevelTransform.from(metadata.pose()).localDirectionToWorld(toPhysics(plotDirection));
        return new Vec3(world.x(), world.y(), world.z());
    }

    private static Vec3 plotToBodyLocal(Vec3 plotPosition, SubLevelClientMetadata metadata) {
        PhysicsVector bodyToPlotOrigin = metadata.bodyToPlotOrigin();
        return new Vec3(
                bodyToPlotOrigin.x() + plotPosition.x() - metadata.plot().minPlotX(),
                bodyToPlotOrigin.y() + plotPosition.y() - metadata.plot().minPlotY(),
                bodyToPlotOrigin.z() + plotPosition.z() - metadata.plot().minPlotZ()
        );
    }

    private static PhysicsVector toPhysics(Vec3 vec) {
        return new PhysicsVector(vec.x, vec.y, vec.z);
    }

    public record Result(
            SubLevelId id,
            BlockPos plotPos,
            BlockState blockState,
            VoxelShape shape,
            BlockHitResult hit,
            double distance
    ) {
        public Result {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plotPos, "plotPos");
            Objects.requireNonNull(blockState, "blockState");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(hit, "hit");
            if (distance < 0.0D || Double.isNaN(distance)) {
                throw new IllegalArgumentException("distance must not be negative or NaN");
            }
        }
    }
}
