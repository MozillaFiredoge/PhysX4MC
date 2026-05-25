package com.firedoge.px4mc.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.ClientTrackedSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelTransform;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ClientSubLevelCollision {
    private ClientSubLevelCollision() {
    }

    public static List<VoxelShape> blockCollisionShapes(ClientLevel level, AABB worldBounds) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldBounds, "worldBounds");
        ClientSubLevelContainer container = SubLevelContainers.container(level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .orElse(null);
        if (container == null || container.isEmpty()) {
            return List.of();
        }

        AABB queryBounds = worldBounds.inflate(1.0E-7D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (ClientTrackedSubLevel subLevel : container.trackedSubLevels()) {
            if (!subLevel.finalized()) {
                continue;
            }
            appendCachedSubLevelCollisions(container, subLevel, queryBounds, shapes);
        }
        return List.copyOf(shapes);
    }

    private static void appendCachedSubLevelCollisions(
            ClientSubLevelContainer container,
            ClientTrackedSubLevel subLevel,
            AABB queryBounds,
            List<VoxelShape> output
    ) {
        ClientTrackedSubLevel.WorldCollisionCache cache = worldCollisionCache(container, subLevel);
        for (AABB worldBox : cache.candidateBoxes(queryBounds)) {
            if (worldBox.intersects(queryBounds)) {
                output.add(Shapes.create(worldBox));
            }
        }
    }

    private static ClientTrackedSubLevel.WorldCollisionCache worldCollisionCache(
            ClientSubLevelContainer container,
            ClientTrackedSubLevel subLevel
    ) {
        long geometryVersion = subLevel.collisionGeometryVersion();
        PhysicsPose pose = subLevel.pose();
        ClientTrackedSubLevel.WorldCollisionCache cached = subLevel.worldCollisionCache();
        if (cached != null && cached.geometryVersion() == geometryVersion && cached.pose().equals(pose)) {
            return cached;
        }

        List<AABB> bodyLocalBoxes = bodyLocalCollisionBoxes(container, subLevel);
        SubLevelTransform transform = SubLevelTransform.from(pose);
        List<AABB> worldBoxes = new ArrayList<>(bodyLocalBoxes.size());
        for (AABB bodyLocalBox : bodyLocalBoxes) {
            worldBoxes.add(bodyLocalBoxToWorldBounds(transform, bodyLocalBox));
        }
        subLevel.cacheWorldCollisionBoxes(pose, worldBoxes);
        return subLevel.worldCollisionCache();
    }

    private static List<AABB> bodyLocalCollisionBoxes(ClientSubLevelContainer container, ClientTrackedSubLevel subLevel) {
        long geometryVersion = subLevel.collisionGeometryVersion();
        ClientTrackedSubLevel.BodyLocalCollisionCache cached = subLevel.bodyLocalCollisionCache();
        if (cached != null && cached.geometryVersion() == geometryVersion) {
            return cached.boxes();
        }

        List<AABB> boxes = new ArrayList<>();
        appendBodyLocalCollisions(container, subLevel, boxes);
        subLevel.cacheBodyLocalCollisionBoxes(boxes);
        return subLevel.bodyLocalCollisionCache().boxes();
    }

    private static void appendBodyLocalCollisions(
            ClientSubLevelContainer container,
            ClientTrackedSubLevel subLevel,
            List<AABB> output
    ) {
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
                            VoxelShape collisionShape = state.getCollisionShape(subLevel.levelView(), immutablePlotPos);
                            if (collisionShape.isEmpty()) {
                                continue;
                            }
                            appendBodyLocalCollisionBoxes(subLevel, immutablePlotPos, collisionShape, output);
                        }
                    }
                }
            }
        }
    }

    private static void appendBodyLocalCollisionBoxes(
            ClientTrackedSubLevel subLevel,
            BlockPos plotPos,
            VoxelShape collisionShape,
            List<AABB> output
    ) {
        for (AABB box : collisionShape.toAabbs()) {
            AABB plotBox = new AABB(
                    plotPos.getX() + box.minX,
                    plotPos.getY() + box.minY,
                    plotPos.getZ() + box.minZ,
                    plotPos.getX() + box.maxX,
                    plotPos.getY() + box.maxY,
                    plotPos.getZ() + box.maxZ
            );
            output.add(plotBoxToBodyLocalBounds(subLevel, plotBox));
        }
    }

    private static AABB plotBoxToBodyLocalBounds(ClientTrackedSubLevel subLevel, AABB plotBox) {
        PhysicsVector bodyToPlotOrigin = subLevel.metadata().bodyToPlotOrigin();
        double xOffset = bodyToPlotOrigin.x() - subLevel.plot().minPlotX();
        double yOffset = bodyToPlotOrigin.y() - subLevel.plot().minPlotY();
        double zOffset = bodyToPlotOrigin.z() - subLevel.plot().minPlotZ();
        return new AABB(
                plotBox.minX + xOffset,
                plotBox.minY + yOffset,
                plotBox.minZ + zOffset,
                plotBox.maxX + xOffset,
                plotBox.maxY + yOffset,
                plotBox.maxZ + zOffset
        );
    }

    private static AABB bodyLocalBoxToWorldBounds(SubLevelTransform transform, AABB localBox) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? localBox.minX : localBox.maxX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? localBox.minY : localBox.maxY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? localBox.minZ : localBox.maxZ;
                    PhysicsVector world = transform.localToWorld(new PhysicsVector(x, y, z));
                    minX = Math.min(minX, world.x());
                    minY = Math.min(minY, world.y());
                    minZ = Math.min(minZ, world.z());
                    maxX = Math.max(maxX, world.x());
                    maxY = Math.max(maxY, world.y());
                    maxZ = Math.max(maxZ, world.z());
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
