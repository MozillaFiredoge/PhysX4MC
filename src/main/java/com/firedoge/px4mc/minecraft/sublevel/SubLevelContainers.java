package com.firedoge.px4mc.minecraft.sublevel;

import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class SubLevelContainers {
    private SubLevelContainers() {
    }

    public static Optional<SubLevelContainer> container(Level level) {
        if (level instanceof SubLevelContainerHolder holder) {
            return Optional.ofNullable(holder.px4mc$subLevelContainer());
        }
        return Optional.empty();
    }

    public static Optional<ServerSubLevelContainer> server(ServerLevel level) {
        return container(level)
                .filter(ServerSubLevelContainer.class::isInstance)
                .map(ServerSubLevelContainer.class::cast);
    }

    public static ServerSubLevelContainer requireServer(ServerLevel level) {
        return server(level).orElseThrow(() -> new IllegalStateException("ServerLevel does not expose a SubLevelContainer"));
    }
}
