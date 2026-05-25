package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.render.ClientSubLevelBlockView;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

public final class ClientTrackedSubLevel {
    private final SubLevelClientMetadata metadata;
    private final Set<ChunkPos> loadedChunks = new LinkedHashSet<>();
    private ClientSubLevelBlockView levelView;
    private PhysicsPose pose;
    private boolean finalized;
    private long collisionGeometryVersion;
    private BodyLocalCollisionCache bodyLocalCollisionCache;
    private WorldCollisionCache worldCollisionCache;
    private static final double COLLISION_INDEX_CELL_SIZE = 4.0D;

    ClientTrackedSubLevel(SubLevelClientMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.pose = metadata.pose();
    }

    public SubLevelClientMetadata metadata() {
        return metadata;
    }

    public SubLevelId id() {
        return metadata.id();
    }

    public SubLevelPlot plot() {
        return metadata.plot();
    }

    public PhysicsPose pose() {
        return pose;
    }

    public ClientSubLevelBlockView levelView() {
        if (levelView == null) {
            throw new IllegalStateException("Client sublevel block view has not been attached");
        }
        return levelView;
    }

    void attachLevelView(ClientSubLevelBlockView levelView) {
        this.levelView = Objects.requireNonNull(levelView, "levelView");
    }

    void updatePose(PhysicsPose pose) {
        this.pose = Objects.requireNonNull(pose, "pose");
    }

    public boolean finalized() {
        return finalized;
    }

    void finalizeTracking() {
        finalized = true;
    }

    void markChunkLoaded(ChunkPos chunkPos) {
        loadedChunks.add(Objects.requireNonNull(chunkPos, "chunkPos"));
        invalidateCollisionGeometry();
    }

    void markChunkDropped(ChunkPos chunkPos) {
        if (loadedChunks.remove(chunkPos)) {
            invalidateCollisionGeometry();
        }
    }

    public Set<ChunkPos> loadedChunks() {
        return Set.copyOf(loadedChunks);
    }

    public long collisionGeometryVersion() {
        return collisionGeometryVersion;
    }

    public void invalidateCollisionGeometry() {
        collisionGeometryVersion++;
        bodyLocalCollisionCache = null;
        worldCollisionCache = null;
    }

    public BodyLocalCollisionCache bodyLocalCollisionCache() {
        return bodyLocalCollisionCache;
    }

    public void cacheBodyLocalCollisionBoxes(List<AABB> boxes) {
        bodyLocalCollisionCache = new BodyLocalCollisionCache(collisionGeometryVersion, boxes);
        worldCollisionCache = null;
    }

    public WorldCollisionCache worldCollisionCache() {
        return worldCollisionCache;
    }

    public void cacheWorldCollisionBoxes(PhysicsPose pose, List<AABB> boxes) {
        worldCollisionCache = new WorldCollisionCache(collisionGeometryVersion, pose, boxes);
    }

    public record BodyLocalCollisionCache(long geometryVersion, List<AABB> boxes) {
        public BodyLocalCollisionCache {
            boxes = List.copyOf(boxes);
        }
    }

    public record WorldCollisionCache(
            long geometryVersion,
            PhysicsPose pose,
            List<AABB> boxes,
            AABB bounds,
            Map<CellKey, List<Integer>> boxIndexesByCell
    ) {
        public WorldCollisionCache(long geometryVersion, PhysicsPose pose, List<AABB> boxes) {
            this(geometryVersion, pose, boxes, union(boxes), index(boxes));
        }

        public WorldCollisionCache {
            Objects.requireNonNull(pose, "pose");
            boxes = List.copyOf(boxes);
            boxIndexesByCell = copyIndex(boxIndexesByCell);
        }

        public List<AABB> candidateBoxes(AABB queryBounds) {
            if (bounds == null || !bounds.intersects(queryBounds)) {
                return List.of();
            }

            int minCellX = cell(queryBounds.minX);
            int minCellY = cell(queryBounds.minY);
            int minCellZ = cell(queryBounds.minZ);
            int maxCellX = cell(queryBounds.maxX);
            int maxCellY = cell(queryBounds.maxY);
            int maxCellZ = cell(queryBounds.maxZ);
            if (minCellX == maxCellX && minCellY == maxCellY && minCellZ == maxCellZ) {
                return boxesForIndexes(boxIndexesByCell.get(new CellKey(minCellX, minCellY, minCellZ)));
            }

            Set<Integer> indexes = new LinkedHashSet<>();
            for (int x = minCellX; x <= maxCellX; x++) {
                for (int y = minCellY; y <= maxCellY; y++) {
                    for (int z = minCellZ; z <= maxCellZ; z++) {
                        List<Integer> cellIndexes = boxIndexesByCell.get(new CellKey(x, y, z));
                        if (cellIndexes != null) {
                            indexes.addAll(cellIndexes);
                        }
                    }
                }
            }
            return boxesForIndexes(indexes.stream().toList());
        }

        private List<AABB> boxesForIndexes(List<Integer> indexes) {
            if (indexes == null || indexes.isEmpty()) {
                return List.of();
            }

            List<AABB> candidates = new ArrayList<>(indexes.size());
            for (int index : indexes) {
                candidates.add(boxes.get(index));
            }
            return candidates;
        }

        private static AABB union(List<AABB> boxes) {
            if (boxes.isEmpty()) {
                return null;
            }

            AABB first = boxes.getFirst();
            double minX = first.minX;
            double minY = first.minY;
            double minZ = first.minZ;
            double maxX = first.maxX;
            double maxY = first.maxY;
            double maxZ = first.maxZ;
            for (int i = 1; i < boxes.size(); i++) {
                AABB box = boxes.get(i);
                minX = Math.min(minX, box.minX);
                minY = Math.min(minY, box.minY);
                minZ = Math.min(minZ, box.minZ);
                maxX = Math.max(maxX, box.maxX);
                maxY = Math.max(maxY, box.maxY);
                maxZ = Math.max(maxZ, box.maxZ);
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private static Map<CellKey, List<Integer>> index(List<AABB> boxes) {
            Map<CellKey, List<Integer>> index = new LinkedHashMap<>();
            for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
                AABB box = boxes.get(boxIndex);
                int minCellX = cell(box.minX);
                int minCellY = cell(box.minY);
                int minCellZ = cell(box.minZ);
                int maxCellX = cell(box.maxX);
                int maxCellY = cell(box.maxY);
                int maxCellZ = cell(box.maxZ);
                for (int x = minCellX; x <= maxCellX; x++) {
                    for (int y = minCellY; y <= maxCellY; y++) {
                        for (int z = minCellZ; z <= maxCellZ; z++) {
                            index.computeIfAbsent(new CellKey(x, y, z), ignored -> new ArrayList<>()).add(boxIndex);
                        }
                    }
                }
            }
            return copyIndex(index);
        }

        private static Map<CellKey, List<Integer>> copyIndex(Map<CellKey, List<Integer>> index) {
            Map<CellKey, List<Integer>> copy = new LinkedHashMap<>();
            for (Map.Entry<CellKey, List<Integer>> entry : index.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }

        private static int cell(double coordinate) {
            return (int) Math.floor(coordinate / COLLISION_INDEX_CELL_SIZE);
        }

        private record CellKey(int x, int y, int z) {
        }
    }
}
