#pragma once

#include <jni.h>

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeIsPhysXLinked(
    JNIEnv* env,
    jclass type
);

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateWorld(
    JNIEnv* env,
    jclass type,
    jdouble gravity_x,
    jdouble gravity_y,
    jdouble gravity_z,
    jfloat fixed_time_step,
    jint max_sub_steps,
    jboolean enable_gpu_dynamics
);

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeDestroyWorld(
    JNIEnv* env,
    jclass type,
    jlong world_handle
);

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeStepWorld(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jfloat delta_seconds
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeIsWorldGpuDynamicsEnabled(
    JNIEnv* env,
    jclass type,
    jlong world_handle
);

JNIEXPORT jstring JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeGetWorldGpuDynamicsStatus(
    JNIEnv* env,
    jclass type,
    jlong world_handle
);

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateBoxShape(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jfloat half_extent_x,
    jfloat half_extent_y,
    jfloat half_extent_z
);

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateStaticPlane(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jdouble normal_x,
    jdouble normal_y,
    jdouble normal_z,
    jdouble distance
);

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateStaticBody(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong shape_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
);

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateDynamicBody(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jlong shape_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w,
    jfloat mass
);

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateDynamicCompoundBoxBody(
    JNIEnv* env,
    jclass type,
    jlong world_handle,
    jdoubleArray boxes,
    jint box_count,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w,
    jfloat mass
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeGetBodyPose(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdoubleArray output
);

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeSetBodyPose(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
);

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeSetLinearVelocity(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdouble velocity_x,
    jdouble velocity_y,
    jdouble velocity_z
);

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeGetLinearVelocity(
    JNIEnv* env,
    jclass type,
    jlong body_handle,
    jdoubleArray output
);

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeDestroyBody(
    JNIEnv* env,
    jclass type,
    jlong body_handle
);

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeDestroyShape(
    JNIEnv* env,
    jclass type,
    jlong shape_handle
);
}
