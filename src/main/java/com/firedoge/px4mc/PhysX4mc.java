package com.firedoge.px4mc;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.firedoge.px4mc.api.PhysicsBackend;
import com.firedoge.px4mc.backend.physx.PhysXBackend;
import com.firedoge.px4mc.backend.physx.PhysXNative;
import com.firedoge.px4mc.config.PhysXConfig;
import com.firedoge.px4mc.mechanics.MechanicsApi;
import com.firedoge.px4mc.mechanics.ServerMechanicsApi;
import com.firedoge.px4mc.network.PhysX4mcNetworking;
import com.firedoge.px4mc.nativebridge.NativeException;
import com.firedoge.px4mc.physics.PhysicsManager;
import com.firedoge.px4mc.platform.neoforge.NeoForgeEvents;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(PhysX4mc.MODID)
public class PhysX4mc {
    public static final String MODID = "physx4mc";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final MechanicsApi MECHANICS_API = ServerMechanicsApi.INSTANCE;

    public PhysX4mc(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(PhysX4mcNetworking::registerPayloads);
        modContainer.registerConfig(ModConfig.Type.COMMON, PhysXConfig.SPEC);
        PhysicsManager.INSTANCE.registerBackend(new PhysXBackend());
        NeoForge.EVENT_BUS.register(new NeoForgeEvents());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Registered physics backends: {}", describeBackends());
        if (PhysXConfig.LOAD_NATIVE_ON_STARTUP.getAsBoolean()) {
            event.enqueueWork(() -> {
                try {
                    PhysXNative.load();
                    if (PhysXNative.isPhysXLinked()) {
                        LOGGER.info("Loaded PhysX native bridge with linked PhysX SDK");
                    } else {
                        LOGGER.warn("Loaded PhysX native bridge, but it was built without linked PhysX SDK libraries");
                    }
                } catch (NativeException exception) {
                    LOGGER.warn("PhysX native bridge is not available yet", exception);
                }
            });
        }
    }

    private static String describeBackends() {
        StringBuilder builder = new StringBuilder();
        for (PhysicsBackend backend : PhysicsManager.INSTANCE.backends()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(backend.id());
        }
        return builder.length() == 0 ? "<none>" : builder.toString();
    }

    public static MechanicsApi api() {
        return MECHANICS_API;
    }
}
