#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#ifndef PX4MC_WITH_PHYSX
#define PX4MC_WITH_PHYSX 0
#endif

#if PX4MC_WITH_PHYSX
#include "PxPhysicsAPI.h"
#endif

namespace px4mc {
using WorldHandle = std::uint64_t;

struct WorldState {
#if PX4MC_WITH_PHYSX
    physx::PxScene* scene = nullptr;
    physx::PxDefaultCpuDispatcher* dispatcher = nullptr;
    physx::PxMaterial* material = nullptr;
    bool gpu_dynamics_requested = false;
    bool gpu_dynamics_enabled = false;
    std::string gpu_dynamics_status = "not_requested";
#endif
};

class PhysXContext {
public:
    PhysXContext() = default;
    ~PhysXContext();

    std::uint64_t next_handle();
    WorldHandle create_world(double gravity_x, double gravity_y, double gravity_z, float fixed_time_step, int max_sub_steps, bool enable_gpu_dynamics);
    void destroy_world(WorldHandle handle);
    void step_world(WorldHandle handle, float delta_seconds);
    bool is_world_gpu_dynamics_enabled(WorldHandle handle);
    std::string world_gpu_dynamics_status(WorldHandle handle);
    std::uint64_t create_box_shape(WorldHandle world, float half_extent_x, float half_extent_y, float half_extent_z);
    std::uint64_t create_static_plane(WorldHandle world, double normal_x, double normal_y, double normal_z, double distance);
    std::uint64_t create_static_body(WorldHandle world, std::uint64_t shape, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w);
    std::uint64_t create_dynamic_body(WorldHandle world, std::uint64_t shape, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w, float mass);
    std::uint64_t create_dynamic_compound_box_body(WorldHandle world, const double* boxes, int box_count, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w, float mass);
    static bool get_body_pose(std::uint64_t body, double* output);
    static void set_body_pose(std::uint64_t body, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w);
    static void set_linear_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z);
    static bool get_linear_velocity(std::uint64_t body, double* output);
    static void destroy_body(std::uint64_t body);
    static void destroy_shape(std::uint64_t shape);

private:
#if PX4MC_WITH_PHYSX
    bool ensure_initialized();
    bool ensure_cuda_context_manager(std::string& status);
    void release_world(WorldState& world);

    physx::PxDefaultAllocator allocator_;
    physx::PxDefaultErrorCallback error_callback_;
    physx::PxFoundation* foundation_ = nullptr;
    physx::PxPhysics* physics_ = nullptr;
#if PX_SUPPORT_GPU_PHYSX
    physx::PxCudaContextManager* cuda_context_manager_ = nullptr;
    bool cuda_context_manager_attempted_ = false;
    int gpu_world_count_ = 0;
    std::string cuda_context_manager_status_ = "not_requested";
#endif
#endif

    std::mutex mutex_;
    std::atomic_uint64_t next_handle_{1};
    std::unordered_map<WorldHandle, std::unique_ptr<WorldState>> worlds_;
};

bool is_physx_linked();
PhysXContext& context();
}
