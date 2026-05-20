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

## First Target

The recommended first vertical slice is:

```text
/px4mc spawn_box creates a PhysX dynamic box and makes a visible Minecraft test
entity follow its pose as it falls.
```

This validates native linking, JNI, Java API lifecycle, server ticking, and
entity synchronization while keeping the scope small.
