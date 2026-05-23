package com.firedoge.px4mc.minecraft.sublevel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.firedoge.px4mc.PhysX4mc;
import com.firedoge.px4mc.api.PhysicsPose;
import com.firedoge.px4mc.api.PhysicsQuaternion;
import com.firedoge.px4mc.api.PhysicsVector;
import com.firedoge.px4mc.mechanics.MechanicsBodySnapshot;
import com.mojang.math.Transformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

final class SubLevelDebugVisuals {
    private static final MethodHandle DISPLAY_SET_TRANSFORMATION = findDisplaySetTransformation();

    private SubLevelDebugVisuals() {
    }

    static void create(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        for (SubLevelBlock block : subLevel.blocks()) {
            Display.BlockDisplay entity = createVisualEntity(level, body.pose(), block);
            if (level.addFreshEntity(entity)) {
                subLevel.visuals().add(new PhysicsSubLevel.VisualBinding(block, entity.getUUID()));
            }
        }
    }

    static void sync(ServerLevel level, MechanicsBodySnapshot body, PhysicsSubLevel subLevel) {
        if (subLevel.visuals().isEmpty()) {
            return;
        }
        for (PhysicsSubLevel.VisualBinding visual : subLevel.visuals()) {
            Entity entity = level.getEntity(visual.entityId());
            if (!(entity instanceof Display.BlockDisplay display) || display.isRemoved()) {
                continue;
            }
            syncVisualEntity(display, body.pose(), visual.block());
        }
    }

    static void discard(ServerLevel level, PhysicsSubLevel subLevel) {
        for (PhysicsSubLevel.VisualBinding visual : subLevel.visuals()) {
            Entity entity = level.getEntity(visual.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
        subLevel.visuals().clear();
    }

    static int discardBlock(ServerLevel level, PhysicsSubLevel subLevel, BlockPos localPos) {
        int removed = 0;
        for (PhysicsSubLevel.VisualBinding visual : List.copyOf(subLevel.visuals())) {
            if (!visual.block().localPos().equals(localPos)) {
                continue;
            }
            Entity entity = level.getEntity(visual.entityId());
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
            subLevel.visuals().remove(visual);
            removed++;
        }
        return removed;
    }

    private static Display.BlockDisplay createVisualEntity(ServerLevel level, PhysicsPose pose, SubLevelBlock block) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setCustomName(Component.literal("PhysX sublevel debug visual"));
        entity.setCustomNameVisible(false);
        applyInitialVisualState(entity, pose, block);
        return entity;
    }

    private static void syncVisualEntity(Display.BlockDisplay entity, PhysicsPose pose, SubLevelBlock block) {
        PhysicsVector position = pose.position();
        entity.setPos(position.x(), position.y(), position.z());
        setDisplayTransformation(entity, visualTransformation(pose, block));
    }

    private static void applyInitialVisualState(Display.BlockDisplay entity, PhysicsPose pose, SubLevelBlock block) {
        CompoundTag tag = new CompoundTag();
        PhysicsVector position = pose.position();
        tag.put("Pos", doubleList(position.x(), position.y(), position.z()));
        tag.put("Motion", doubleList(0.0D, 0.0D, 0.0D));
        tag.put("Rotation", floatList(0.0F, 0.0F));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        tag.put("block_state", NbtUtils.writeBlockState(block.blockState()));
        tag.putFloat("width", 1.0F);
        tag.putFloat("height", 1.0F);
        tag.putFloat("view_range", 64.0F);
        tag.putFloat("shadow_radius", 0.2F);
        tag.putFloat("shadow_strength", 0.4F);
        tag.putInt("interpolation_duration", 0);
        tag.putInt("teleport_duration", 1);
        encodeVisualTransformation(pose, block).ifPresent(transformation -> tag.put("transformation", transformation));
        entity.load(tag);
    }

    private static Optional<net.minecraft.nbt.Tag> encodeVisualTransformation(PhysicsPose pose, SubLevelBlock block) {
        return Transformation.EXTENDED_CODEC
                .encodeStart(NbtOps.INSTANCE, visualTransformation(pose, block))
                .resultOrPartial(message -> PhysX4mc.LOGGER.warn("Failed to encode sublevel display transformation: {}", message));
    }

    private static Transformation visualTransformation(PhysicsPose pose, SubLevelBlock block) {
        Quaternionf rotation = toJomlQuaternion(pose.rotation());
        Vector3f translation = vector(block.visualLocalOrigin()).rotate(new Quaternionf(rotation));
        return new Transformation(
                translation,
                rotation,
                new Vector3f(1.0F, 1.0F, 1.0F),
                new Quaternionf()
        );
    }

    private static void setDisplayTransformation(Display.BlockDisplay entity, Transformation transformation) {
        if (DISPLAY_SET_TRANSFORMATION == null) {
            return;
        }
        try {
            DISPLAY_SET_TRANSFORMATION.invoke(entity, transformation);
        } catch (Throwable ignored) {
            // Position sync still keeps the debug proxy approximately useful.
        }
    }

    private static MethodHandle findDisplaySetTransformation() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Display.class, MethodHandles.lookup());
            return lookup.findVirtual(Display.class, "setTransformation", MethodType.methodType(void.class, Transformation.class));
        } catch (ReflectiveOperationException exception) {
            PhysX4mc.LOGGER.warn("Display#setTransformation is unavailable; sublevel debug visuals will only sync position", exception);
            return null;
        }
    }

    private static Quaternionf toJomlQuaternion(PhysicsQuaternion rotation) {
        return new Quaternionf(
                (float) rotation.x(),
                (float) rotation.y(),
                (float) rotation.z(),
                (float) rotation.w()
        ).normalize();
    }

    private static Vector3f vector(PhysicsVector vector) {
        return new Vector3f(
                (float) vector.x(),
                (float) vector.y(),
                (float) vector.z()
        );
    }

    private static ListTag doubleList(double first, double second, double third) {
        ListTag list = new ListTag();
        list.addTag(0, DoubleTag.valueOf(first));
        list.addTag(1, DoubleTag.valueOf(second));
        list.addTag(2, DoubleTag.valueOf(third));
        return list;
    }

    private static ListTag floatList(float first, float second) {
        ListTag list = new ListTag();
        list.addTag(0, FloatTag.valueOf(first));
        list.addTag(1, FloatTag.valueOf(second));
        return list;
    }
}
