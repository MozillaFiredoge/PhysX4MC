package com.firedoge.px4mc.network;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundStopTrackingSubLevelPayload(SubLevelId id) implements CustomPacketPayload {
    public static final Type<ClientboundStopTrackingSubLevelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PhysX4mc.MODID, "stop_tracking_sublevel")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStopTrackingSubLevelPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundStopTrackingSubLevelPayload::write, ClientboundStopTrackingSubLevelPayload::read);

    private static ClientboundStopTrackingSubLevelPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundStopTrackingSubLevelPayload(SubLevelPayloadCodecs.readSubLevelId(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        SubLevelPayloadCodecs.writeSubLevelId(buffer, id);
    }

    @Override
    public Type<ClientboundStopTrackingSubLevelPayload> type() {
        return TYPE;
    }
}
