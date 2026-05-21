package com.firedoge.px4mc.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PhysXConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOAD_NATIVE_ON_STARTUP = BUILDER
            .comment("Attempt to load the native PhysX bridge during common setup.")
            .define("loadNativeOnStartup", false);

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_BACKEND = BUILDER
            .comment("Default physics backend id.")
            .define("defaultBackend", "physx");

    public static final ModConfigSpec.DoubleValue FIXED_TIME_STEP = BUILDER
            .comment("Physics fixed time step in seconds.")
            .defineInRange("fixedTimeStep", 1.0D / 20.0D, 1.0D / 240.0D, 1.0D);

    public static final ModConfigSpec.IntValue MAX_SUB_STEPS = BUILDER
            .comment("Maximum physics substeps per game tick.")
            .defineInRange("maxSubSteps", 4, 1, 32);

    public static final ModConfigSpec.BooleanValue ENABLE_GPU_DYNAMICS = BUILDER
            .comment("Request PhysX GPU rigid body dynamics for newly created scenes. Requires a PhysX GPU build, NVIDIA driver, and PhysXGpu runtime library.")
            .define("enableGpuDynamics", false);

    public static final ModConfigSpec.IntValue ACTIVE_TERRAIN_MAX_SCANS_PER_TICK = BUILDER
            .comment("Maximum dynamic boxes scanned per server tick for active-object terrain queueing. Use 0 to disable active terrain queueing.")
            .defineInRange("activeTerrainMaxScansPerTick", 1024, 0, 100000);

    public static final ModConfigSpec.IntValue ACTIVE_TERRAIN_VERTICAL_MARGIN = BUILDER
            .comment("Vertical margin outside the Minecraft build height where dynamic boxes still request terrain colliders.")
            .defineInRange("activeTerrainVerticalMargin", 64, 0, 4096);

    public static final ModConfigSpec.IntValue DEBUG_PROXY_MAX_SYNCS_PER_TICK = BUILDER
            .comment("Maximum BlockDisplay debug proxy pose syncs per server tick. Use 0 to disable proxy pose sync.")
            .defineInRange("debugProxyMaxSyncsPerTick", 256, 0, 20000);

    public static final ModConfigSpec.BooleanValue DEBUG_PROXY_SYNC_TRANSFORM = BUILDER
            .comment("Synchronize BlockDisplay transformation every proxy sync. Disable this to sync position only and reduce SynchedEntityData/network churn.")
            .define("debugProxySyncTransform", false);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable verbose physics debug logging.")
            .define("debugLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PhysXConfig() {
    }
}
