package com.firedoge.px4mc.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelClientMetadata;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelId;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelPlot;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelPlotId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

final class SubLevelPayloadCodecs {
    private SubLevelPayloadCodecs() {
    }

    static void writeMetadata(RegistryFriendlyByteBuf buffer, SubLevelClientMetadata metadata) {
        writeSubLevelId(buffer, metadata.id());
        writePlot(buffer, metadata.plot());
        writePose(buffer, metadata.pose());
        writeVector(buffer, metadata.bodyToPlotOrigin());
        buffer.writeVarInt(metadata.chunkPositions().size());
        for (ChunkPos chunkPos : metadata.chunkPositions()) {
            writeChunkPos(buffer, chunkPos);
        }
    }

    static SubLevelClientMetadata readMetadata(RegistryFriendlyByteBuf buffer) {
        SubLevelId id = readSubLevelId(buffer);
        SubLevelPlot plot = readPlot(buffer);
        PhysicsPose pose = readPose(buffer);
        PhysicsVector bodyToPlotOrigin = readVector(buffer);
        int chunkCount = buffer.readVarInt();
        List<ChunkPos> chunkPositions = new ArrayList<>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            chunkPositions.add(readChunkPos(buffer));
        }
        return new SubLevelClientMetadata(id, plot, pose, bodyToPlotOrigin, chunkPositions);
    }

    static void writeSubLevelId(RegistryFriendlyByteBuf buffer, SubLevelId id) {
        buffer.writeUUID(id.value());
    }

    static SubLevelId readSubLevelId(RegistryFriendlyByteBuf buffer) {
        UUID value = buffer.readUUID();
        return new SubLevelId(value);
    }

    static void writePose(RegistryFriendlyByteBuf buffer, PhysicsPose pose) {
        PhysicsVector position = pose.position();
        PhysicsQuaternion rotation = pose.rotation();
        buffer.writeDouble(position.x());
        buffer.writeDouble(position.y());
        buffer.writeDouble(position.z());
        buffer.writeDouble(rotation.x());
        buffer.writeDouble(rotation.y());
        buffer.writeDouble(rotation.z());
        buffer.writeDouble(rotation.w());
    }

    static PhysicsPose readPose(RegistryFriendlyByteBuf buffer) {
        PhysicsVector position = new PhysicsVector(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
        PhysicsQuaternion rotation = new PhysicsQuaternion(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
        return new PhysicsPose(position, rotation);
    }

    private static void writeVector(RegistryFriendlyByteBuf buffer, PhysicsVector vector) {
        buffer.writeDouble(vector.x());
        buffer.writeDouble(vector.y());
        buffer.writeDouble(vector.z());
    }

    private static PhysicsVector readVector(RegistryFriendlyByteBuf buffer) {
        return new PhysicsVector(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
    }

    private static void writePlot(RegistryFriendlyByteBuf buffer, SubLevelPlot plot) {
        buffer.writeLong(plot.id().value());
        writeChunkPos(buffer, plot.originChunk());
        buffer.writeInt(plot.sectionY());
        buffer.writeVarInt(plot.chunkSpan());
        buffer.writeVarInt(plot.sectionSpan());
    }

    private static SubLevelPlot readPlot(RegistryFriendlyByteBuf buffer) {
        SubLevelPlotId id = new SubLevelPlotId(buffer.readLong());
        ChunkPos originChunk = readChunkPos(buffer);
        int sectionY = buffer.readInt();
        int chunkSpan = buffer.readVarInt();
        int sectionSpan = buffer.readVarInt();
        return new SubLevelPlot(id, originChunk, sectionY, chunkSpan, sectionSpan);
    }

    private static void writeChunkPos(RegistryFriendlyByteBuf buffer, ChunkPos chunkPos) {
        buffer.writeInt(chunkPos.x);
        buffer.writeInt(chunkPos.z);
    }

    private static ChunkPos readChunkPos(RegistryFriendlyByteBuf buffer) {
        return new ChunkPos(buffer.readInt(), buffer.readInt());
    }
}
