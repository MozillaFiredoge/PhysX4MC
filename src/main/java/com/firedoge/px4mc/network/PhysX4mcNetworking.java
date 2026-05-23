package com.firedoge.px4mc.network;

import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class PhysX4mcNetworking {
    private static final String NETWORK_VERSION = "2";

    private PhysX4mcNetworking() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(
                ClientboundStartTrackingSubLevelPayload.TYPE,
                ClientboundStartTrackingSubLevelPayload.STREAM_CODEC,
                PhysX4mcNetworking::handleStartTracking
        );
        registrar.playToClient(
                ClientboundFinalizeSubLevelPayload.TYPE,
                ClientboundFinalizeSubLevelPayload.STREAM_CODEC,
                PhysX4mcNetworking::handleFinalize
        );
        registrar.playToClient(
                ClientboundSubLevelTransformPayload.TYPE,
                ClientboundSubLevelTransformPayload.STREAM_CODEC,
                PhysX4mcNetworking::handleTransform
        );
        registrar.playToClient(
                ClientboundStopTrackingSubLevelPayload.TYPE,
                ClientboundStopTrackingSubLevelPayload.STREAM_CODEC,
                PhysX4mcNetworking::handleStopTracking
        );
    }

    private static void handleStartTracking(ClientboundStartTrackingSubLevelPayload payload, IPayloadContext context) {
        SubLevelContainers.container(context.player().level())
                .ifPresent(container -> container.clientStartTracking(payload.metadata()));
    }

    private static void handleFinalize(ClientboundFinalizeSubLevelPayload payload, IPayloadContext context) {
        SubLevelContainers.container(context.player().level())
                .ifPresent(container -> container.clientFinalizeTracking(payload.id()));
    }

    private static void handleTransform(ClientboundSubLevelTransformPayload payload, IPayloadContext context) {
        SubLevelContainers.container(context.player().level())
                .ifPresent(container -> container.clientUpdateTransform(payload.id(), payload.pose()));
    }

    private static void handleStopTracking(ClientboundStopTrackingSubLevelPayload payload, IPayloadContext context) {
        SubLevelContainers.container(context.player().level())
                .ifPresent(container -> container.clientStopTracking(payload.id()));
    }
}
