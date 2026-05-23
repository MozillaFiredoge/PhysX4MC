#include "px4mc/jni_bridge.hpp"

#include "px4mc/physx_world.hpp"

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeIsPhysXLinked(
    JNIEnv*,
    jclass
) {
    return px4mc::is_physx_linked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateWorld(
    JNIEnv*,
    jclass,
    jdouble gravity_x,
    jdouble gravity_y,
    jdouble gravity_z,
    jfloat fixed_time_step,
    jint max_sub_steps,
    jboolean enable_gpu_dynamics
) {
    return static_cast<jlong>(px4mc::create_world(
        gravity_x,
        gravity_y,
        gravity_z,
        fixed_time_step,
        max_sub_steps,
        enable_gpu_dynamics == JNI_TRUE
    ));
}

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeDestroyWorld(
    JNIEnv*,
    jclass,
    jlong world_handle
) {
    px4mc::destroy_world(static_cast<px4mc::WorldHandle>(world_handle));
}

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeStepWorld(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jfloat delta_seconds
) {
    px4mc::step_world(static_cast<px4mc::WorldHandle>(world_handle), delta_seconds);
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeIsWorldGpuDynamicsEnabled(
    JNIEnv*,
    jclass,
    jlong world_handle
) {
    return px4mc::is_world_gpu_dynamics_enabled(static_cast<px4mc::WorldHandle>(world_handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeGetWorldGpuDynamicsStatus(
    JNIEnv* env,
    jclass,
    jlong world_handle
) {
    return env->NewStringUTF(px4mc::world_gpu_dynamics_status(static_cast<px4mc::WorldHandle>(world_handle)).c_str());
}

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateBoxShape(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jfloat half_extent_x,
    jfloat half_extent_y,
    jfloat half_extent_z
) {
    return static_cast<jlong>(px4mc::create_box_shape(
        static_cast<px4mc::WorldHandle>(world_handle),
        half_extent_x,
        half_extent_y,
        half_extent_z
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateStaticPlane(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jdouble normal_x,
    jdouble normal_y,
    jdouble normal_z,
    jdouble distance
) {
    return static_cast<jlong>(px4mc::create_static_plane(
        static_cast<px4mc::WorldHandle>(world_handle),
        normal_x,
        normal_y,
        normal_z,
        distance
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateStaticBody(
    JNIEnv*,
    jclass,
    jlong world_handle,
    jlong shape_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
) {
    return static_cast<jlong>(px4mc::create_static_body(
        static_cast<px4mc::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(shape_handle),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateDynamicBody(
    JNIEnv*,
    jclass,
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
) {
    return static_cast<jlong>(px4mc::create_dynamic_body(
        static_cast<px4mc::WorldHandle>(world_handle),
        static_cast<std::uint64_t>(shape_handle),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    ));
}

JNIEXPORT jlong JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeCreateDynamicCompoundBoxBody(
    JNIEnv* env,
    jclass,
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
) {
    if (boxes == nullptr || box_count <= 0 || env->GetArrayLength(boxes) < box_count * 6) {
        return 0;
    }

    jdouble* values = env->GetDoubleArrayElements(boxes, nullptr);
    if (values == nullptr) {
        return 0;
    }
    std::uint64_t body = px4mc::create_dynamic_compound_box_body(
        static_cast<px4mc::WorldHandle>(world_handle),
        values,
        static_cast<int>(box_count),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    );
    env->ReleaseDoubleArrayElements(boxes, values, JNI_ABORT);
    return static_cast<jlong>(body);
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeGetBodyPose(
    JNIEnv* env,
    jclass,
    jlong body_handle,
    jdoubleArray output
) {
    if (output == nullptr || env->GetArrayLength(output) < 7) {
        return JNI_FALSE;
    }
    jdouble values[7] = {};
    if (!px4mc::get_body_pose(static_cast<std::uint64_t>(body_handle), values)) {
        return JNI_FALSE;
    }
    env->SetDoubleArrayRegion(output, 0, 7, values);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeSetBodyPose(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble position_x,
    jdouble position_y,
    jdouble position_z,
    jdouble rotation_x,
    jdouble rotation_y,
    jdouble rotation_z,
    jdouble rotation_w
) {
    px4mc::set_body_pose(
        static_cast<std::uint64_t>(body_handle),
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    );
}

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeSetLinearVelocity(
    JNIEnv*,
    jclass,
    jlong body_handle,
    jdouble velocity_x,
    jdouble velocity_y,
    jdouble velocity_z
) {
    px4mc::set_linear_velocity(
        static_cast<std::uint64_t>(body_handle),
        velocity_x,
        velocity_y,
        velocity_z
    );
}

JNIEXPORT jboolean JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeGetLinearVelocity(
    JNIEnv* env,
    jclass,
    jlong body_handle,
    jdoubleArray output
) {
    if (output == nullptr || env->GetArrayLength(output) < 3) {
        return JNI_FALSE;
    }
    jdouble values[3] = {};
    if (!px4mc::get_linear_velocity(static_cast<std::uint64_t>(body_handle), values)) {
        return JNI_FALSE;
    }
    env->SetDoubleArrayRegion(output, 0, 3, values);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeDestroyBody(
    JNIEnv*,
    jclass,
    jlong body_handle
) {
    px4mc::destroy_body(static_cast<std::uint64_t>(body_handle));
}

JNIEXPORT void JNICALL Java_com_firedoge_px4mc_backend_physx_PhysXNative_nativeDestroyShape(
    JNIEnv*,
    jclass,
    jlong shape_handle
) {
    px4mc::destroy_shape(static_cast<std::uint64_t>(shape_handle));
}
