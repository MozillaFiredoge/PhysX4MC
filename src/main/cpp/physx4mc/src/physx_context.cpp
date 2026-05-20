#include "px4mc/physx_context.hpp"

#include <cmath>

namespace px4mc {
namespace {
#if PX4MC_WITH_PHYSX
template <typename T>
void release_px(T*& object) {
    if (object != nullptr) {
        object->release();
        object = nullptr;
    }
}

template <typename T>
std::uint64_t to_handle(T* pointer) {
    return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(pointer));
}

template <typename T>
T* from_handle(std::uint64_t handle) {
    return reinterpret_cast<T*>(static_cast<std::uintptr_t>(handle));
}

physx::PxTransform make_transform(
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
    physx::PxQuat rotation(
        static_cast<physx::PxReal>(rotation_x),
        static_cast<physx::PxReal>(rotation_y),
        static_cast<physx::PxReal>(rotation_z),
        static_cast<physx::PxReal>(rotation_w)
    );
    if (rotation.magnitudeSquared() <= 0.0f) {
        rotation = physx::PxQuat(0.0f, 0.0f, 0.0f, 1.0f);
    } else {
        rotation.normalize();
    }
    return physx::PxTransform(
        physx::PxVec3(
            static_cast<physx::PxReal>(position_x),
            static_cast<physx::PxReal>(position_y),
            static_cast<physx::PxReal>(position_z)
        ),
        rotation
    );
}
#endif
}

PhysXContext::~PhysXContext() {
#if PX4MC_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& entry : worlds_) {
        release_world(*entry.second);
    }
    worlds_.clear();
    release_px(physics_);
    release_px(foundation_);
#endif
}

std::uint64_t PhysXContext::next_handle() {
    return next_handle_.fetch_add(1, std::memory_order_relaxed);
}

WorldHandle PhysXContext::create_world(double gravity_x, double gravity_y, double gravity_z, float, int) {
    std::lock_guard<std::mutex> lock(mutex_);

#if PX4MC_WITH_PHYSX
    if (!ensure_initialized()) {
        return 0;
    }

    auto world = std::make_unique<WorldState>();
    physx::PxSceneDesc scene_desc(physics_->getTolerancesScale());
    scene_desc.gravity = physx::PxVec3(
        static_cast<physx::PxReal>(gravity_x),
        static_cast<physx::PxReal>(gravity_y),
        static_cast<physx::PxReal>(gravity_z)
    );
    world->dispatcher = physx::PxDefaultCpuDispatcherCreate(2);
    scene_desc.cpuDispatcher = world->dispatcher;
    scene_desc.filterShader = physx::PxDefaultSimulationFilterShader;
    world->scene = physics_->createScene(scene_desc);
    world->material = physics_->createMaterial(0.6f, 0.6f, 0.0f);

    if (world->dispatcher == nullptr || world->scene == nullptr || world->material == nullptr) {
        release_world(*world);
        return 0;
    }

    WorldHandle handle = next_handle();
    worlds_.emplace(handle, std::move(world));
    return handle;
#else
    return 0;
#endif
}

void PhysXContext::destroy_world(WorldHandle handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(handle);
    if (found == worlds_.end()) {
        return;
    }
#if PX4MC_WITH_PHYSX
    release_world(*found->second);
#endif
    worlds_.erase(found);
}

void PhysXContext::step_world(WorldHandle handle, float delta_seconds) {
    if (delta_seconds <= 0.0f) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(handle);
    if (found == worlds_.end()) {
        return;
    }
#if PX4MC_WITH_PHYSX
    physx::PxScene* scene = found->second->scene;
    if (scene == nullptr) {
        return;
    }
    scene->simulate(delta_seconds);
    scene->fetchResults(true);
#endif
}

std::uint64_t PhysXContext::create_box_shape(WorldHandle world_handle, float half_extent_x, float half_extent_y, float half_extent_z) {
#if PX4MC_WITH_PHYSX
    if (half_extent_x <= 0.0f || half_extent_y <= 0.0f || half_extent_z <= 0.0f) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->material == nullptr) {
        return 0;
    }
    physx::PxShape* shape = physics_->createShape(
        physx::PxBoxGeometry(half_extent_x, half_extent_y, half_extent_z),
        *found->second->material
    );
    return to_handle(shape);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_static_plane(WorldHandle world_handle, double normal_x, double normal_y, double normal_z, double distance) {
#if PX4MC_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || found->second->material == nullptr) {
        return 0;
    }

    double length = std::sqrt(normal_x * normal_x + normal_y * normal_y + normal_z * normal_z);
    if (length <= 0.0) {
        return 0;
    }

    physx::PxPlane plane(
        static_cast<physx::PxReal>(normal_x / length),
        static_cast<physx::PxReal>(normal_y / length),
        static_cast<physx::PxReal>(normal_z / length),
        static_cast<physx::PxReal>(distance / length)
    );
    physx::PxRigidStatic* body = physx::PxCreatePlane(*physics_, plane, *found->second->material);
    if (body == nullptr) {
        return 0;
    }
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_static_body(
    WorldHandle world_handle,
    std::uint64_t shape_handle,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
#if PX4MC_WITH_PHYSX
    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    physx::PxShape* shape = from_handle<physx::PxShape>(shape_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || shape == nullptr) {
        return 0;
    }

    physx::PxRigidStatic* body = physics_->createRigidStatic(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
    if (body == nullptr) {
        return 0;
    }
    if (!body->attachShape(*shape)) {
        body->release();
        return 0;
    }
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

std::uint64_t PhysXContext::create_dynamic_body(
    WorldHandle world_handle,
    std::uint64_t shape_handle,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w,
    float mass
) {
#if PX4MC_WITH_PHYSX
    if (mass <= 0.0f) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto found = worlds_.find(world_handle);
    physx::PxShape* shape = from_handle<physx::PxShape>(shape_handle);
    if (found == worlds_.end() || found->second->scene == nullptr || shape == nullptr) {
        return 0;
    }

    physx::PxRigidDynamic* body = physics_->createRigidDynamic(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
    if (body == nullptr) {
        return 0;
    }
    if (!body->attachShape(*shape)) {
        body->release();
        return 0;
    }
    physx::PxRigidBodyExt::updateMassAndInertia(*body, mass);
    found->second->scene->addActor(*body);
    return to_handle(body);
#else
    return 0;
#endif
}

bool PhysXContext::get_body_pose(std::uint64_t body_handle, double* output) {
#if PX4MC_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr || output == nullptr) {
        return false;
    }
    const physx::PxTransform pose = body->getGlobalPose();
    output[0] = pose.p.x;
    output[1] = pose.p.y;
    output[2] = pose.p.z;
    output[3] = pose.q.x;
    output[4] = pose.q.y;
    output[5] = pose.q.z;
    output[6] = pose.q.w;
    return true;
#else
    return false;
#endif
}

void PhysXContext::set_body_pose(
    std::uint64_t body_handle,
    double position_x,
    double position_y,
    double position_z,
    double rotation_x,
    double rotation_y,
    double rotation_z,
    double rotation_w
) {
#if PX4MC_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return;
    }
    body->setGlobalPose(make_transform(
        position_x,
        position_y,
        position_z,
        rotation_x,
        rotation_y,
        rotation_z,
        rotation_w
    ));
#endif
}

void PhysXContext::set_linear_velocity(std::uint64_t body_handle, double velocity_x, double velocity_y, double velocity_z) {
#if PX4MC_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body == nullptr) {
        return;
    }
    physx::PxRigidDynamic* dynamic = body->is<physx::PxRigidDynamic>();
    if (dynamic == nullptr) {
        return;
    }
    dynamic->setLinearVelocity(physx::PxVec3(
        static_cast<physx::PxReal>(velocity_x),
        static_cast<physx::PxReal>(velocity_y),
        static_cast<physx::PxReal>(velocity_z)
    ));
#endif
}

void PhysXContext::destroy_body(std::uint64_t body_handle) {
#if PX4MC_WITH_PHYSX
    physx::PxRigidActor* body = from_handle<physx::PxRigidActor>(body_handle);
    if (body != nullptr) {
        body->release();
    }
#endif
}

void PhysXContext::destroy_shape(std::uint64_t shape_handle) {
#if PX4MC_WITH_PHYSX
    physx::PxShape* shape = from_handle<physx::PxShape>(shape_handle);
    if (shape != nullptr) {
        shape->release();
    }
#endif
}

#if PX4MC_WITH_PHYSX
bool PhysXContext::ensure_initialized() {
    if (foundation_ != nullptr && physics_ != nullptr) {
        return true;
    }

    foundation_ = PxCreateFoundation(PX_PHYSICS_VERSION, allocator_, error_callback_);
    if (foundation_ == nullptr) {
        return false;
    }

    physics_ = PxCreatePhysics(PX_PHYSICS_VERSION, *foundation_, physx::PxTolerancesScale(), false, nullptr);
    if (physics_ == nullptr) {
        release_px(foundation_);
        return false;
    }

    return true;
}

void PhysXContext::release_world(WorldState& world) {
    release_px(world.scene);
    release_px(world.material);
    release_px(world.dispatcher);
}
#endif

bool is_physx_linked() {
#if PX4MC_WITH_PHYSX
    return true;
#else
    return false;
#endif
}

PhysXContext& context() {
    static PhysXContext instance;
    return instance;
}
}
