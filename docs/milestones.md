# PhysX4mc Milestones

This roadmap starts with a narrow end-to-end slice before expanding into full
Minecraft collision integration.

## M1: Native Minimal Loop

Goal: prove the native PhysX bridge works without Minecraft.

- Create a PhysX world.
- Create a ground plane.
- Create a dynamic box.
- Step the simulation for a fixed number of ticks.
- Read the body pose back into Java.

Likely JNI surface:

```text
nativeCreateBoxShape
nativeCreateStaticPlane
nativeCreateStaticBody
nativeCreateDynamicBody
nativeGetBodyPose
nativeSetBodyPose
nativeSetLinearVelocity
nativeDestroyBody
```

Acceptance: a Java-side test or command can step a box until it falls and rests
on a plane.

Status: implemented with `./gradlew nativeSmokeTest`; the smoke test creates a
static plane and a dynamic box, steps 240 frames, and verifies the box settles
near `y=0.5`.

## M2: Java API Lifecycle

Goal: make the Java physics API usable and explicit about ownership.

- Wire `api/` interfaces to the PhysX backend implementation.
- Define handle ownership for worlds, shapes, materials, and bodies.
- Make every `close()` implementation idempotent.
- Decide whether shapes are shared or owned by bodies.
- Ensure failed native creation paths release partially-created resources.

Acceptance: Java code can create and destroy worlds, shapes, and bodies without
leaking handles or double-freeing native objects.

Status: implemented for the current PhysX world/body/shape scope. Lifecycle
rules are documented in `docs/lifecycle.md` and covered by
`./gradlew nativeSmokeTest`.

## M2.5: Scene/Object Layer

Goal: add a lightweight middle layer between Minecraft and the physics backend,
borrowing Sable's sublevel separation without adopting its full plot-grid system
yet.

- Introduce a server-side physics scene per Minecraft level.
- Track physics objects by stable IDs instead of exposing native handles to
  Minecraft integration code.
- Keep pose synchronization, body lifecycle, and debug metadata outside the raw
  PhysX backend.
- Keep this layer smaller than Sable's `SubLevel` until moving block structures
  require isolated chunk grids.
- Reserve full sublevel-style concepts for later moving block assemblies,
  serialization, network interpolation, and client rendering.

Acceptance: Minecraft-facing code can create, tick, query, and remove physics
objects through a scene/object API without touching `PhysXBody` or native
handles directly.

Status: implemented as a lightweight `minecraft/scene` package. Design notes are
in `docs/scene-layer.md` and behavior is covered by `./gradlew nativeSmokeTest`.

## M3: Minecraft Server Tick Integration

Goal: run PhysX from the server tick without client rendering or block collision.

- Create one `PhysicsWorld` per `ServerLevel`.
- Step each world from server tick using a fixed timestep.
- Add basic commands:
  - `/px4mc spawn_box`
  - `/px4mc physics_status`
  - `/px4mc clear`

Acceptance: a command creates a physics body, server ticks step it, and logs or
command output show its pose changing.

Status: implemented for server-side scenes and commands. `ServerTickEvent.Post`
steps the runtime, `ServerStoppedEvent` closes all scenes, and
`RegisterCommandsEvent` registers:

```text
/px4mc spawn_box
/px4mc physics_status
/px4mc clear
```

Run `tools/native/build-linux.sh` before `./gradlew runServer` so the dev run can
load `build/native/linux-x86_64/libphysx4mc_native.so`.

## M4: Entity Synchronization

Goal: make a visible Minecraft entity follow a PhysX body.

- Pick a simple test entity, such as an armor stand or a custom debug entity.
- Attach a PhysX dynamic body to the entity.
- Drive entity position from the PhysX pose on the server.
- Keep networking and rendering minimal.

Acceptance: a visible entity moves according to PhysX simulation.

Status: initially implemented with a visible `ArmorStand` debug entity, then
superseded by M11's `BlockDisplay` render proxy. `/px4mc spawn_box` creates a
dynamic PhysX box and binds a visible Minecraft proxy to its pose; server ticks
update the proxy after physics simulation. Design notes are in
`docs/entity-sync.md`.

## M5: Static Block Collision

Goal: introduce Minecraft world collision in a controlled way.

- Start with simple solid block AABBs.
- Build static collision when chunks load.
- Release static collision when chunks unload.
- Avoid rebuilding chunk collision every tick.
- Later evaluate batched meshes for dense terrain.

Acceptance: a dynamic test body collides with nearby solid blocks.

Status: superseded by M8's chunk-owned build queue. The M5 shape model remains:
full-block collision is represented as static PhysX box colliders grouped by
chunk and released on `ChunkEvent.Unload`. Details and limitations are in
`docs/block-collision.md`.

## M6: Debugging And Diagnostics

Goal: make runtime behavior inspectable.

- Report world count.
- Report body count.
- Report native linked status.
- Report step time.
- Report active chunk collision count.
- Add debug rendering after the simulation path is stable.

Acceptance: commands or debug output can quickly identify missing native links,
leaks, duplicate worlds, and tick cost.

Status: implemented through `/px4mc physics_status`. It reports native link
status, scene count, object count, terrain collider count, terrain chunk count,
terrain queue state, bound debug entity count, last physics step time, last
terrain build time, and a sample dynamic object pose. Details are in
`docs/diagnostics.md`.

## M7: Native Packaging

Goal: package the native bridge with the mod.

- Copy `build/native/linux-x86_64/libphysx4mc_native.so` into
  `src/main/resources/native/linux-x86_64/`.
- Add Gradle tasks for native build and copy.
- Keep Linux working first.
- Add Windows and macOS only after the Linux loop is stable.

Acceptance: the mod jar contains the Linux native library and can load it through
`NativeLibraryLoader`.

Status: implemented for Linux. `buildNativeLinux` runs
`tools/native/build-linux.sh`, `copyNativeLinux` copies
`libphysx4mc_native.so` into generated resources under
`native/linux-x86_64/`, and `processResources` depends on that copy task when
`-Pphysx4mc.bundleNativeLinux=true` is set. Details are in
`docs/native-packaging.md`.

## M8: Chunk Collision Pipeline

Goal: build and maintain static terrain collision through a chunk-owned queue,
instead of directly sampling blocks around `/px4mc spawn_box`.

- Add a terrain collision build queue.
- Schedule chunk collision builds from active dynamic/debug physics objects.
- Build static full-block colliders per chunk on the server tick.
- Track chunk build state: queued, built, dirty, unloaded.
- Rebuild dirty chunks after block updates, with throttling.
- Release chunk-owned colliders when chunks unload.
- Keep the current full-block AABB shape model; leave partial voxel shapes for a
  later milestone.

Acceptance: dynamic debug bodies collide with already-built terrain chunks,
`/px4mc physics_status` reports queued/built/dirty terrain chunks, chunk unload
releases its static objects, and repeated spawn/clear/chunk travel does not grow
object or terrain counts unexpectedly.

Status: initial pipeline implemented. Server ticks queue chunks around active
dynamic debug objects, `/px4mc spawn_box` queues the spawn area, and the runtime
builds at most one terrain chunk per tick before stepping PhysX. Block updates
mark known chunks dirty for queued rebuild, while chunk unload clears queued
state and releases chunk-owned colliders. Details are in
`docs/chunk-collision-pipeline.md`.

## M9: Terrain Collider Batching

Goal: reduce static terrain body count before adding more block shape fidelity.

- Keep the M8 chunk build queue.
- Keep the current full-block collision model.
- Scan chunk full-block occupancy.
- Greedily merge adjacent full blocks into larger axis-aligned box colliders.
- Track each merged box as a chunk-owned static terrain collider.
- Rebuild a dirty chunk by replacing its previous merged colliders.

Acceptance: `/px4mc physics_status` should show far fewer
`terrainColliders` than the old one-body-per-block terrain path, while dynamic
debug boxes still collide with solid full-block terrain.

Status: implemented as full-block greedy AABB batching in
`ServerPhysicsRuntime`. The runtime now creates one static PhysX box per merged
solid region instead of one static PhysX box per exposed full block. Details are
in `docs/terrain-batching.md`.

## M10: Partial Block Collision Shapes

Goal: bring common non-full Minecraft collision shapes into PhysX without
abandoning the M9 full-block batching path.

- Keep full blocks on the greedy chunk batching path.
- Read non-full block `VoxelShape` collision boxes from Minecraft.
- Convert each non-empty shape box into a chunk-owned static PhysX box.
- Rebuild partial shapes through the existing dirty chunk queue.
- Add diagnostics for partial shape collider count per build pass.

Acceptance: slabs, stairs, fences, panes, and similar blocks contribute terrain
collision through `/px4mc physics_status`, and dynamic debug boxes can collide
with those shape boxes after the chunk is built.

Status: implemented as `VoxelShape.forAllBoxes` import for non-full blocks.
`lastTerrainPartial` reports how many partial-shape colliders were created by
the last terrain build pass. Details are in
`docs/partial-block-collision.md`.

## M11: Real Physics Debug Entity / Render Proxy

Goal: replace the ArmorStand debug proxy with a visible box-like render proxy
that matches the PhysX dynamic body pose more accurately.

- Use a `BlockDisplay` as the visible debug proxy.
- Render a 1x1x1 cube-like block instead of an ArmorStand.
- Synchronize position and full quaternion rotation from PhysX pose.
- Keep PhysX body authoritative.
- Keep proxy lifecycle owned by `ServerPhysicsRuntime`.
- Recreate missing proxies without deleting the PhysX body.
- Report proxy recreation diagnostics in `/px4mc physics_status`.

Acceptance: `/px4mc spawn_box` creates a visible cube-like object, the visual
proxy follows PhysX position and rotation, missing proxies are recreated, and
`physics_status` keeps reporting `boundEntities=1` with a dynamic sample pose.

Status: implemented with `Display.BlockDisplay` using a lime stained glass block
state and a server-authored display transformation. `proxyRecreated` and
`lastProxyRecreated` report debug proxy recovery. Details are in
`docs/render-proxy.md`.

## M12: Dynamic Body Controls

Goal: make dynamic PhysX bodies easier to create and manipulate for collision
testing.

- Add optional size and mass arguments to `/px4mc spawn_box`.
- Scale the `BlockDisplay` render proxy to match the spawned PhysX box size.
- Add a command to set linear velocity on the nearest dynamic debug box.
- Report active dynamic box count in `/px4mc physics_status`.
- Include sample linear velocity in status output.

Acceptance: testers can spawn boxes with different sizes and masses, apply a
velocity to the nearest box, and observe dynamic body motion through the
`BlockDisplay` proxy while PhysX remains authoritative.

Status: implemented. `/px4mc spawn_box [size] [mass]` creates a uniform dynamic
box with matching proxy scale, and `/px4mc set_velocity <x> <y> <z>` sets the
linear velocity of the nearest dynamic box within 32 blocks. Details are in
`docs/dynamic-body-controls.md`.

## M13: Debug Object Management

Goal: make active dynamic debug bodies manageable without clearing the whole
dimension.

- Add a command to list dynamic debug boxes in the current level.
- Sort listed boxes by distance from the command source.
- Add a command to remove the nearest dynamic debug box.
- Add a command to remove a dynamic debug box by object id prefix.
- When removing a dynamic box, close the PhysX object and discard its
  `BlockDisplay` proxy.
- Keep terrain colliders untouched during individual debug object removal.

Acceptance: testers can spawn several debug boxes, list them, remove one by
nearest distance or id prefix, and observe `physics_status` decrement
`dynamicBoxes`, `objects`, and `boundEntities` without dropping cached terrain
colliders.

Status: implemented. `/px4mc list_boxes [limit]`, `/px4mc remove_nearest
[maxDistance]`, and `/px4mc remove_box <idPrefix>` manage dynamic debug boxes
inside the current level. Details are in `docs/debug-object-management.md`.

## M14: Cross-Platform Native Build Entry Points

Goal: make the JNI bridge CMake project portable enough that Windows and macOS
native builds do not require rewriting `CMakeLists.txt`.

- Detect the PhysX platform binary directory from the CMake host platform.
- Keep `PHYSX_LIB_DIR` as an explicit override for unusual local installs.
- Add platform-specific PhysX install hints for Linux, Windows, and macOS.
- Link platform system libraries through CMake platform branches instead of
  hardcoding Linux `dl` and `pthread`.
- Keep Gradle native packaging opt-in and Linux-only until other platforms are
  validated.

Acceptance: the existing Linux native smoke test still passes, Windows/macOS
helpers can invoke the same CMake project with platform overrides, and normal
`./gradlew build` remains Java-only.

Status: implemented at the build-entry level. Linux was verified locally through
`./gradlew nativeSmokeTest`; Windows and macOS CMake paths are prepared but
still require local platform validation before jar packaging tasks are added.
Details are in `docs/native-packaging.md`.

## M15: GPU Dynamics Stress Path

Goal: make PhysX GPU rigid body dynamics testable under many-body load without
Minecraft render proxies dominating the result.

- Add an opt-in config flag for requesting `PxSceneFlag::eENABLE_GPU_DYNAMICS`
  on newly created PhysX scenes.
- Create one shared CUDA context manager when the local PhysX build and driver
  support GPU dynamics.
- Use GPU broadphase for GPU scenes, and fall back to CPU scenes if GPU setup
  fails.
- Create physics scenes lazily instead of pre-creating one scene for every
  loaded dimension during server tick.
- Report both requested GPU dynamics and actually enabled GPU scene count in
  `/px4mc physics_status`.
- Add a physics-only stress-grid command that creates dynamic boxes without
  `BlockDisplay` proxies.
- Copy `PhysXGpu` runtime artifacts into native output when CMake can find
  them.

Acceptance: testers can enable `enableGpuDynamics`, restart the world, confirm
`gpuRequested=true` and `gpuScenes>0` after creating a physics scene, then run
`/px4mc spawn_stress_grid <countX> <countY> <countZ> [spacing] [size] [mass]`
to compare CPU and GPU dynamics behavior. CPU fallback remains usable and is
visible as `gpuRequested=true, gpuScenes=0`.

Status: implemented. Details are in `docs/gpu-dynamics.md`.

## M16: Runtime Profiling

Goal: make server-side physics tick costs visible before optimizing the wrong
layer.

- Measure total time spent inside `ServerPhysicsRuntime.tick`.
- Split the tick into active-object terrain queueing, terrain queue processing,
  scene stepping, and debug entity sync.
- Keep the existing native scene step metric for comparison.
- Count active-object snapshots, dynamic bodies found during the active scan,
  terrain chunks queued, synced debug entities, and entity pose syncs.
- Report the metrics through `/px4mc physics_status`.

Acceptance: a tester can distinguish native PhysX step cost from Java/JNI
snapshot readback, terrain queueing, and Minecraft debug entity sync cost.

Status: implemented. Details are in `docs/runtime-profiling.md`.

## M17: Many-Body Runtime Stress Pass

Goal: validate many dynamic PhysX bodies under Minecraft server tick load, while
separating solver cost from terrain queueing and debug proxy synchronization.

- Use physics-only stress grids to test solver throughput without Minecraft
  entity overhead.
- Use visible `BlockDisplay` proxies to measure the cost of debug visualization
  separately from the native PhysX step.
- Round-robin debug proxy sync and skip unchanged position-only proxy writes.
- Budget active-object terrain scans instead of reading every dynamic body pose
  every tick.
- Skip active terrain queueing for bodies far outside Minecraft build height.
- Report active terrain scan budget, height skips, proxy sync counts, and sync
  sub-timings in `/px4mc physics_status`.

Acceptance: thousands of dynamic boxes can be kept in one server scene, with
`lastSyncEntitiesMs` reduced below the native solver step cost and
`lastQueueActiveMs` bounded by `activeTerrainMaxScansPerTick`. The status log
should make the remaining bottleneck obvious.

Status: passed for the current debug/stress scope. A representative run with
4,444 dynamic boxes reported roughly `lastTickMs=11.961`,
`lastStepPhaseMs=9.450`, `lastQueueActiveMs=1.919`, and
`lastSyncEntitiesMs=0.579`, showing that the main remaining cost is the PhysX
solver step rather than Minecraft proxy synchronization. This does not imply
that real gameplay should use thousands of visible Minecraft entities.

## M18: Gameplay Physics Boundary Prototype

Goal: move from debug bodies and stress tests toward a gameplay-facing physics
object model.

- Separate debug/stress bodies from gameplay physics objects in the scene layer.
- Define which Minecraft state is authoritative for a gameplay object:
  block state, entity state, or a physics-owned assembly.
- Prototype one small physics block assembly: remove a few blocks from the
  world, create one PhysX body or compound representation, drive a lightweight
  render proxy, then settle or remove it cleanly.
- Add lifecycle rules for objects that fall out of world bounds, go to sleep,
  unload with chunks, or need to be converted back into blocks.
- Keep the first prototype single-level and server-authored before revisiting a
  full Sable-style sublevel grid.

Acceptance: a small gameplay object can be spawned from Minecraft state,
simulated by PhysX, inspected through `/px4mc physics_status`, and cleaned up
without leaving orphaned bodies, proxies, or terrain colliders.

Status: recommended next. This phase should answer what the mod actually wants
from physics before investing in thousands of visible entity proxies or a full
sublevel implementation.

## First Target

The recommended first vertical slice is:

```text
/px4mc spawn_box creates a PhysX dynamic box and makes a visible Minecraft test
entity follow its pose as it falls.
```

This validates native linking, JNI, Java API lifecycle, server ticking, and
entity synchronization while keeping the scope small.
