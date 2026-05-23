#pragma once

#include "px4mc/physx_context.hpp"

namespace px4mc {
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
bool get_body_pose(std::uint64_t body, double* output);
void set_body_pose(std::uint64_t body, double position_x, double position_y, double position_z, double rotation_x, double rotation_y, double rotation_z, double rotation_w);
void set_linear_velocity(std::uint64_t body, double velocity_x, double velocity_y, double velocity_z);
bool get_linear_velocity(std::uint64_t body, double* output);
void destroy_body(std::uint64_t body);
void destroy_shape(std::uint64_t shape);
}
