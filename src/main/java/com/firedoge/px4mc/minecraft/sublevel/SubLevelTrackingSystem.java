package com.firedoge.px4mc.minecraft.sublevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.firedoge.px4mc.network.ClientboundFinalizeSubLevelPayload;
import com.firedoge.px4mc.network.ClientboundStartTrackingSubLevelPayload;
import com.firedoge.px4mc.network.ClientboundStopTrackingSubLevelPayload;
import com.firedoge.px4mc.network.ClientboundSubLevelTransformPayload;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

public final class SubLevelTrackingSystem {
    private static final double TRACKING_RANGE = 512.0D;
    private static final double POSITION_EPSILON_SQR = 1.0E-6D;
    private static final double ROTATION_EPSILON = 1.0E-6D;

    private final ServerSubLevelContainer container;
    private final Map<SubLevelId, Set<UUID>> trackingPlayers = new LinkedHashMap<>();
    private final Map<SubLevelId, PhysicsPose> lastSentPoses = new LinkedHashMap<>();
    private final Map<SubLevelId, Set<ChunkPos>> pendingChunkResyncs = new LinkedHashMap<>();

    SubLevelTrackingSystem(ServerSubLevelContainer container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    public void tick() {
        for (PhysicsSubLevel subLevel : container.subLevels()) {
            Optional<PhysicsPose> maybePose = pose(subLevel);
            if (maybePose.isEmpty()) {
                continue;
            }
            PhysicsPose pose = maybePose.get();
            updatePlayers(subLevel, pose);
            sendTransformIfChanged(subLevel, pose);
        }
        for (PlotChunkHolder holder : container.plotChunkHolders()) {
            PhysicsSubLevel subLevel = container.subLevelAtChunk(holder.chunk().getPos()).orElse(null);
            if (subLevel != null && containsMovingPiston(subLevel, holder.chunk().getPos())) {
                continue;
            }
            holder.broadcastChanges(holder.chunk());
        }
        sendPendingChunkResyncs();
    }

    public void resyncPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (player.serverLevel() != level()) {
            return;
        }
        for (PhysicsSubLevel subLevel : container.subLevels()) {
            Optional<PhysicsPose> maybePose = pose(subLevel);
            if (maybePose.isEmpty()) {
                continue;
            }
            PhysicsPose pose = maybePose.get();
            UUID playerId = player.getGameProfile().getId();
            Set<UUID> tracking = trackingPlayers.computeIfAbsent(subLevel.id(), ignored -> new LinkedHashSet<>());
            if (shouldTrack(player, pose)) {
                tracking.add(playerId);
                sendFullSync(player, subLevel, pose);
            } else if (tracking.remove(playerId)) {
                sendRemoval(player, subLevel, subLevel.plot().chunkPositions());
            }
        }
    }

    public void onSubLevelAdded(PhysicsSubLevel subLevel) {
        Objects.requireNonNull(subLevel, "subLevel");
        pose(subLevel).ifPresent(pose -> updatePlayers(subLevel, pose));
    }

    public void onSubLevelChunksRebuilt(PhysicsSubLevel subLevel, List<LevelChunk> chunks) {
        Objects.requireNonNull(subLevel, "subLevel");
        Objects.requireNonNull(chunks, "chunks");
        Set<ChunkPos> pending = pendingChunkResyncs.computeIfAbsent(subLevel.id(), ignored -> new LinkedHashSet<>());
        for (LevelChunk chunk : chunks) {
            if (containsMovingPiston(subLevel, chunk.getPos())) {
                continue;
            }
            pending.add(chunk.getPos());
        }
    }

    public void onSubLevelRemoved(PhysicsSubLevel subLevel, List<ChunkPos> removedChunks) {
        Objects.requireNonNull(subLevel, "subLevel");
        Objects.requireNonNull(removedChunks, "removedChunks");
        Set<UUID> tracking = trackingPlayers.remove(subLevel.id());
        lastSentPoses.remove(subLevel.id());
        pendingChunkResyncs.remove(subLevel.id());
        if (tracking == null || tracking.isEmpty()) {
            return;
        }
        for (UUID playerId : tracking) {
            ServerPlayer player = level().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == level()) {
                sendRemoval(player, subLevel, removedChunks);
            }
        }
    }

    public void clear() {
        trackingPlayers.clear();
        lastSentPoses.clear();
        pendingChunkResyncs.clear();
    }

    public List<ServerPlayer> playersTracking(ChunkPos chunkPos) {
        Objects.requireNonNull(chunkPos, "chunkPos");
        return container.subLevelAtChunk(chunkPos)
                .map(subLevel -> playersTracking(subLevel.id()))
                .orElseGet(List::of);
    }

    public List<ServerPlayer> playersTracking(SubLevelId id) {
        Objects.requireNonNull(id, "id");
        Set<UUID> tracking = trackingPlayers.get(id);
        if (tracking == null || tracking.isEmpty()) {
            return List.of();
        }
        List<ServerPlayer> players = new ArrayList<>(tracking.size());
        for (UUID playerId : tracking) {
            ServerPlayer player = level().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == level()) {
                players.add(player);
            }
        }
        return List.copyOf(players);
    }

    private void updatePlayers(PhysicsSubLevel subLevel, PhysicsPose pose) {
        Set<UUID> tracking = trackingPlayers.computeIfAbsent(subLevel.id(), ignored -> new LinkedHashSet<>());
        Set<UUID> shouldTrack = new LinkedHashSet<>();
        for (ServerPlayer player : level().players()) {
            if (shouldTrack(player, pose)) {
                shouldTrack.add(player.getGameProfile().getId());
                if (tracking.add(player.getGameProfile().getId())) {
                    sendFullSync(player, subLevel, pose);
                }
            }
        }

        tracking.removeIf(playerId -> {
            if (shouldTrack.contains(playerId)) {
                return false;
            }
            ServerPlayer player = level().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.serverLevel() == level()) {
                sendRemoval(player, subLevel, subLevel.plot().chunkPositions());
            }
            return true;
        });
    }

    private void sendFullSync(ServerPlayer player, PhysicsSubLevel subLevel, PhysicsPose pose) {
        if (player.connection == null) {
            return;
        }
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        SubLevelClientMetadata metadata = SubLevelClientMetadata.from(subLevel, pose);
        packets.add(new ClientboundCustomPayloadPacket(new ClientboundStartTrackingSubLevelPayload(metadata)));
        for (ChunkPos chunkPos : metadata.chunkPositions()) {
            container.plotChunk(chunkPos)
                    .map(chunk -> SubLevelChunkSender.chunkPacket(level(), chunk))
                    .ifPresent(packets::add);
        }
        packets.add(new ClientboundCustomPayloadPacket(new ClientboundFinalizeSubLevelPayload(subLevel.id())));
        player.connection.send(new ClientboundBundlePacket(packets));
        lastSentPoses.put(subLevel.id(), pose);
    }

    private void sendRemoval(ServerPlayer player, PhysicsSubLevel subLevel, List<ChunkPos> removedChunks) {
        if (player.connection == null) {
            return;
        }
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(new ClientboundCustomPayloadPacket(new ClientboundStopTrackingSubLevelPayload(subLevel.id())));
        for (ChunkPos chunkPos : removedChunks) {
            packets.add(SubLevelChunkSender.forgetPacket(chunkPos));
        }
        player.connection.send(new ClientboundBundlePacket(packets));
    }

    private void sendTransformIfChanged(PhysicsSubLevel subLevel, PhysicsPose pose) {
        Set<UUID> tracking = trackingPlayers.get(subLevel.id());
        if (tracking == null || tracking.isEmpty()) {
            return;
        }
        PhysicsPose previous = lastSentPoses.get(subLevel.id());
        if (previous != null && closeEnough(previous, pose)) {
            return;
        }
        lastSentPoses.put(subLevel.id(), pose);
        ClientboundSubLevelTransformPayload payload = new ClientboundSubLevelTransformPayload(subLevel.id(), pose);
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        for (ServerPlayer player : playersTracking(subLevel.id())) {
            player.connection.send(packet);
        }
    }

    private void sendPendingChunkResyncs() {
        if (pendingChunkResyncs.isEmpty()) {
            return;
        }

        Map<SubLevelId, Set<ChunkPos>> pending = new LinkedHashMap<>(pendingChunkResyncs);
        pendingChunkResyncs.clear();
        for (Map.Entry<SubLevelId, Set<ChunkPos>> entry : pending.entrySet()) {
            PhysicsSubLevel subLevel = container.subLevel(entry.getKey()).orElse(null);
            if (subLevel == null) {
                continue;
            }
            List<ServerPlayer> players = playersTracking(entry.getKey());
            if (players.isEmpty()) {
                continue;
            }
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (ChunkPos chunkPos : entry.getValue()) {
                if (containsMovingPiston(subLevel, chunkPos)) {
                    pendingChunkResyncs
                            .computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>())
                            .add(chunkPos);
                    continue;
                }
                container.plotChunk(chunkPos)
                        .map(chunk -> SubLevelChunkSender.chunkPacket(level(), chunk))
                        .ifPresent(packets::add);
            }
            if (packets.isEmpty()) {
                continue;
            }
            ClientboundBundlePacket bundle = new ClientboundBundlePacket(packets);
            for (ServerPlayer player : players) {
                if (player.connection != null) {
                    player.connection.send(bundle);
                }
            }
        }
    }

    private Optional<PhysicsPose> pose(PhysicsSubLevel subLevel) {
        return PhysX4mc.api().existingWorld(level())
                .flatMap(world -> world.snapshot(subLevel.bodyId()))
                .filter(body -> !body.closed())
                .map(MechanicsBodySnapshot::pose);
    }

    private static boolean containsMovingPiston(PhysicsSubLevel subLevel, ChunkPos chunkPos) {
        for (SubLevelBlock block : subLevel.blocks()) {
            if (block.blockState().is(Blocks.MOVING_PISTON)
                    && new ChunkPos(subLevel.plot().toPlotBlockPos(block.localPos())).equals(chunkPos)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldTrack(ServerPlayer player, PhysicsPose pose) {
        PhysicsVector position = pose.position();
        double dx = position.x() - player.getX();
        double dy = position.y() - player.getY();
        double dz = position.z() - player.getZ();
        return dx * dx + dy * dy + dz * dz <= TRACKING_RANGE * TRACKING_RANGE;
    }

    private static boolean closeEnough(PhysicsPose previous, PhysicsPose current) {
        PhysicsVector a = previous.position();
        PhysicsVector b = current.position();
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        if (dx * dx + dy * dy + dz * dz > POSITION_EPSILON_SQR) {
            return false;
        }
        PhysicsQuaternion qa = previous.rotation();
        PhysicsQuaternion qb = current.rotation();
        return Math.abs(qa.x() - qb.x()) <= ROTATION_EPSILON
                && Math.abs(qa.y() - qb.y()) <= ROTATION_EPSILON
                && Math.abs(qa.z() - qb.z()) <= ROTATION_EPSILON
                && Math.abs(qa.w() - qb.w()) <= ROTATION_EPSILON;
    }

    private ServerLevel level() {
        return container.level();
    }
}
