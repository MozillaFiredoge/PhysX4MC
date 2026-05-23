package com.firedoge.px4mc.platform.neoforge;

import com.firedoge.px4mc.command.Px4mcClientCommands;
import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.render.SubLevelPlotRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class NeoForgeClientEvents {
    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        Px4mcClientCommands.register(event);
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        SubLevelContainers.container(Minecraft.getInstance().level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .ifPresent(container -> {
                    SubLevelPlotRenderer.render(event, container);
                    SubLevelPlotRenderer.renderBlockEntities(event, container);
                    SubLevelPlotRenderer.renderBreakingProgress(event, container);
                    SubLevelPlotRenderer.renderSelectionOutline(event, container);
                });
    }

    @SubscribeEvent
    public void onRenderBlockHighlight(RenderHighlightEvent.Block event) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        SubLevelContainers.container(Minecraft.getInstance().level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .filter(container -> container.inPlotBounds(new ChunkPos(event.getTarget().getBlockPos())))
                .ifPresent(ignored -> event.setCanceled(true));
    }
}
