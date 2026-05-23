package com.firedoge.px4mc.network;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelClientMetadata;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundStartTrackingSubLevelPayload(SubLevelClientMetadata metadata) implements CustomPacketPayload {
    public static final Type<ClientboundStartTrackingSubLevelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PhysX4mc.MODID, "start_tracking_sublevel")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStartTrackingSubLevelPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundStartTrackingSubLevelPayload::write, ClientboundStartTrackingSubLevelPayload::read);

    private static ClientboundStartTrackingSubLevelPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundStartTrackingSubLevelPayload(SubLevelPayloadCodecs.readMetadata(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        SubLevelPayloadCodecs.writeMetadata(buffer, metadata);
    }

    @Override
    public Type<ClientboundStartTrackingSubLevelPayload> type() {
        return TYPE;
    }
}
