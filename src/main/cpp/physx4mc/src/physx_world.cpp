#include "px4mc/physx_world.hpp"

#include "px4mc/physx_context.hpp"

namespace px4mc {
WorldHandle create_world(double gravity_x, double gravity_y, double gravity_z, float fixed_time_step, int max_sub_steps, bool enable_gpu_dynamics) {
    return context().create_world(gravity_x, gravity_y, gravity_z, fixed_time_step, max_sub_steps, enable_gpu_dynamics);
}

void destroy_world(WorldHandle handle) {
    context().destroy_world(handle);
}

void step_world(WorldHandle handle, float delta_seconds) {
    context().step_world(handle, delta_seconds);
}

bool is_world_gpu_dynamics_enabled(WorldHandle handle) {
    return context().is_world_gpu_dynamics_enabled(handle);
}

std::string world_gpu_dynamics_status(WorldHandle handle) {
    return context().world_gpu_dynamics_status(handle);
}

std::uint64_t create_box_shape(WorldHandle world, float half_extent_x, float half_extent_y, float half_extent_z) {
    return context().create_box_shape(world, half_extent_x, half_extent_y, half_extent_z);
}

std::uint64_t create_static_plane(WorldHandle world, double normal_x, double normal_y, double normal_z, double distance) {
    return context().create_static_plane(world, normal_x, normal_y, normal_z, distance);
}

std::uint64_t create_static_body(
    WorldHandle world,
    std::uint64_t shape,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
    return context().create_static_body(
        world,
        shape,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    );
}

std::uint64_t create_dynamic_body(
    WorldHandle world,
    std::uint64_t shape,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w,
    float mass
) {
    return context().create_dynamic_body(
        world,
        shape,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w,
        mass
    );
}

bool get_body_pose(std::uint64_t body, double* output) {
    return PhysXContext::get_body_pose(body, output);
}

void set_body_pose(
    std::uint64_t body,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
    PhysXContext::set_body_pose(
        body,
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    );
}

void set_linear_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z) {
    PhysXContext::set_linear_velocity(body, velocity_x, velocity_y, velocity_z);
}

bool get_linear_velocity(std::uint64_t body, double* output) {
    return PhysXContext::get_linear_velocity(body, output);
}

void destroy_body(std::uint64_t body) {
    PhysXContext::destroy_body(body);
}

void destroy_shape(std::uint64_t shape) {
    PhysXContext::destroy_shape(shape);
}
}
