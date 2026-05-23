package com.firedoge.px4mc.network;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundSubLevelTransformPayload(SubLevelId id, PhysicsPose pose) implements CustomPacketPayload {
    public static final Type<ClientboundSubLevelTransformPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PhysX4mc.MODID, "sublevel_transform")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSubLevelTransformPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundSubLevelTransformPayload::write, ClientboundSubLevelTransformPayload::read);

    private static ClientboundSubLevelTransformPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundSubLevelTransformPayload(
                SubLevelPayloadCodecs.readSubLevelId(buffer),
                SubLevelPayloadCodecs.readPose(buffer)
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        SubLevelPayloadCodecs.writeSubLevelId(buffer, id);
        SubLevelPayloadCodecs.writePose(buffer, pose);
    }

    @Override
    public Type<ClientboundSubLevelTransformPayload> type() {
        return TYPE;
    }
}
