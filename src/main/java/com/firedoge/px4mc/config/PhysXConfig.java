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

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable verbose physics debug logging.")
            .define("debugLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PhysXConfig() {
    }
}
