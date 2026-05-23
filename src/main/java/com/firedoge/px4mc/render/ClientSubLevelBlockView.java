package com.firedoge.px4mc.render;

import java.util.Objects;

import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.minecraft.sublevel.ClientSubLevelContainer;
import com.firedoge.px4mc.minecraft.sublevel.ClientTrackedSubLevel;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelClientMetadata;
import com.firedoge.px4mc.minecraft.sublevel.SubLevelTransform;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class ClientSubLevelBlockView implements BlockAndTintGetter {
    private final ClientSubLevelContainer container;
    private final ClientTrackedSubLevel subLevel;
    private final ClientLevel level;

    public ClientSubLevelBlockView(ClientSubLevelContainer container, ClientTrackedSubLevel subLevel) {
        this.container = Objects.requireNonNull(container, "container");
        this.subLevel = Objects.requireNonNull(subLevel, "subLevel");
        this.level = container.level();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (!subLevel.plot().containsPlotBlockPos(pos)) {
            return null;
        }
        return container.plotChunk(new net.minecraft.world.level.ChunkPos(pos))
                .map(chunk -> chunk.getBlockEntity(pos))
                .orElse(null);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (!subLevel.plot().containsPlotBlockPos(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return container.plotChunk(new net.minecraft.world.level.ChunkPos(pos))
                .map(chunk -> chunk.getBlockState(pos))
                .orElseGet(() -> Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBrightness(LightLayer lightType, BlockPos blockPos) {
        return level.getBrightness(lightType, physicalBlockPos(blockPos));
    }

    @Override
    public int getRawBrightness(BlockPos blockPos, int amount) {
        return level.getRawBrightness(physicalBlockPos(blockPos), amount);
    }

    @Override
    public int getBlockTint(BlockPos blockPos, ColorResolver colorResolver) {
        return level.getBlockTint(physicalBlockPos(blockPos), colorResolver);
    }

    private BlockPos physicalBlockPos(BlockPos plotPos) {
        Vec3 physicalCenter = physicalCenter(plotPos);
        return BlockPos.containing(physicalCenter.x, physicalCenter.y, physicalCenter.z);
    }

    private Vec3 physicalCenter(BlockPos plotPos) {
        SubLevelClientMetadata metadata = subLevel.metadata();
        PhysicsPose pose = subLevel.pose();
        PhysicsVector bodyLocalCenter = new PhysicsVector(
                metadata.bodyToPlotOrigin().x() + plotPos.getX() - metadata.plot().minPlotX() + 0.5D,
                metadata.bodyToPlotOrigin().y() + plotPos.getY() - metadata.plot().minPlotY() + 0.5D,
                metadata.bodyToPlotOrigin().z() + plotPos.getZ() - metadata.plot().minPlotZ() + 0.5D
        );
        PhysicsVector world = SubLevelTransform.from(pose).localToWorld(bodyLocalCenter);
        return new Vec3(world.x(), world.y(), world.z());
    }
}
