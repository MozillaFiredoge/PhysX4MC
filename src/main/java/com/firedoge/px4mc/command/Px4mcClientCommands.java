package com.firedoge.px4mc.command;

import java.util.Locale;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.ClientTrackedSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelContainers;
import com.firedoge.px4mc.render.ClientSubLevelSelection;
import com.firedoge.px4mc.render.SubLevelPlotRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class Px4mcClientCommands {
    private Px4mcClientCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("px4mc_client")
                .then(Commands.literal("sublevel_status")
                        .executes(context -> subLevelStatus(context.getSource()))));
    }

    private static int subLevelStatus(CommandSourceStack source) {
        if (Minecraft.getInstance().level == null) {
            source.sendFailure(Component.literal("No client level is loaded"));
            return 0;
        }
        ClientSubLevelContainer container = SubLevelContainers.container(Minecraft.getInstance().level)
                .filter(ClientSubLevelContainer.class::isInstance)
                .map(ClientSubLevelContainer.class::cast)
                .orElse(null);
        if (container == null) {
            source.sendFailure(Component.literal("No client sublevel container is attached"));
            return 0;
        }

        int finalized = 0;
        int trackedChunks = 0;
        for (ClientTrackedSubLevel subLevel : container.trackedSubLevels()) {
            if (subLevel.finalized()) {
                finalized++;
            }
            trackedChunks += subLevel.loadedChunks().size();
        }
        int finalizedCount = finalized;
        int trackedLoadedChunks = trackedChunks;
        SubLevelPlotRenderer.RenderStats renderStats = SubLevelPlotRenderer.lastStats();
        ClientSubLevelSelection.Result selection = ClientSubLevelSelection.lastResult().orElse(null);
        ClientTrackedSubLevel selectedSubLevel = selection == null
                ? null
                : container.trackedSubLevel(selection.id()).orElse(null);
        source.sendSuccess(() -> Component.literal(
                "ClientSublevels=" + container.size()
                        + ", finalized=" + finalizedCount
                        + ", loadedPlotChunks=" + container.loadedPlotChunkCount()
                        + ", trackedLoadedChunks=" + trackedLoadedChunks
                        + ", lastRenderSublevels=" + renderStats.subLevels()
                        + ", lastRenderChunks=" + renderStats.chunks()
                        + ", lastRenderBlocks=" + renderStats.blocks()
                        + ", lastRenderBlockEntities=" + renderStats.blockEntities()
                        + ", selected=" + describeSelection(selection)
                        + ", selectedTransform=" + describeSelectionTransform(selection, selectedSubLevel)
        ), false);
        return container.size();
    }

    private static String describeSelection(ClientSubLevelSelection.Result selection) {
        if (selection == null) {
            return "none";
        }
        return selection.id()
                + "@"
                + describeBlockPos(selection.plotPos())
                + "/face="
                + selection.hit().getDirection().getName()
                + "/distance="
                + String.format(Locale.ROOT, "%.3f", selection.distance());
    }

    private static String describeSelectionTransform(ClientSubLevelSelection.Result selection, ClientTrackedSubLevel subLevel) {
        if (selection == null || subLevel == null) {
            return "none";
        }
        Vec3 bodyLocalCenter = ClientSubLevelSelection.plotCenterToBodyLocal(selection.plotPos(), subLevel.metadata());
        Vec3 worldCenter = ClientSubLevelSelection.plotCenterToWorld(selection.plotPos(), subLevel);
        PhysicsPose pose = subLevel.pose();
        PhysicsVector bodyToPlotOrigin = subLevel.metadata().bodyToPlotOrigin();
        return "worldCenter="
                + describeVec3(worldCenter)
                + "/bodyLocalCenter="
                + describeVec3(bodyLocalCenter)
                + "/bodyPos="
                + describeVector(pose.position())
                + "/bodyToPlotOrigin="
                + describeVector(bodyToPlotOrigin);
    }

    private static String describeBlockPos(net.minecraft.core.BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String describeVec3(Vec3 vec) {
        return String.format(Locale.ROOT, "%.3f %.3f %.3f", vec.x, vec.y, vec.z);
    }

    private static String describeVector(PhysicsVector vector) {
        return String.format(Locale.ROOT, "%.3f %.3f %.3f", vector.x(), vector.y(), vector.z());
    }
}
