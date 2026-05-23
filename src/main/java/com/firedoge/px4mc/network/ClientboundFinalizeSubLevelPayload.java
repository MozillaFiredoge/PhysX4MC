package com.firedoge.px4mc.network;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundFinalizeSubLevelPayload(SubLevelId id) implements CustomPacketPayload {
    public static final Type<ClientboundFinalizeSubLevelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PhysX4mc.MODID, "finalize_sublevel")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundFinalizeSubLevelPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientboundFinalizeSubLevelPayload::write, ClientboundFinalizeSubLevelPayload::read);

    private static ClientboundFinalizeSubLevelPayload read(RegistryFriendlyByteBuf buffer) {
        return new ClientboundFinalizeSubLevelPayload(SubLevelPayloadCodecs.readSubLevelId(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        SubLevelPayloadCodecs.writeSubLevelId(buffer, id);
    }

    @Override
    public Type<ClientboundFinalizeSubLevelPayload> type() {
        return TYPE;
    }
}
