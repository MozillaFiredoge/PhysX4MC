package com.firedoge.px4mc.render;

import java.util.Objects;

import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class ClientSubLevelPickBridge {
    private ClientSubLevelPickBridge() {
    }

    public static void updateCrosshairTarget(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        ClientSubLevelContainer container = SubLevelContainers.container(minecraft.level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .orElse(null);
        if (container == null || container.isEmpty()) {
            return;
        }

        ClientSubLevelSelection.Result subLevelHit = ClientSubLevelSelection.update(minecraft, container).orElse(null);
        if (subLevelHit == null) {
            return;
        }

        HitResult vanillaHit = minecraft.hitResult;
        if (vanillaHit != null
                && vanillaHit.getType() != HitResult.Type.MISS
                && vanillaHit.getLocation().distanceTo(minecraft.player.getEyePosition()) <= subLevelHit.distance()) {
            return;
        }

        minecraft.hitResult = new BlockHitResult(
                subLevelHit.hit().getLocation(),
                subLevelHit.hit().getDirection(),
                subLevelHit.plotPos(),
                subLevelHit.hit().isInside()
        );
        minecraft.crosshairPickEntity = null;
    }
}
