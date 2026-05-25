# PhysX4mc

PhysX4mc is an experimental NeoForge mod that connects Minecraft gameplay to a
NVIDIA PhysX-backed mechanics layer. Its current focus is sublevels: detached
block assemblies that keep Minecraft's block logic, redstone, block entities,
collision, interaction, and persistence while being driven by a physics body.

The goal is a small physics substrate where Minecraft keeps owning most vanilla
logic, while PhysX owns rigid body movement and collision.

## Status

Current scope is considered feature-complete enough for maintenance mode.
Remaining work should be driven by concrete, reproducible issues rather than new
architecture expansion.

Tested project baseline:

- Minecraft `1.21.1`
- NeoForge `21.1.230`
- Java `21`
- Parchment `2024.11.17`

## Features

- PhysX-backed server mechanics layer with dynamic rigid bodies.
- Terrain collision extraction and batching for loaded Minecraft chunks.
- Sublevel assembly from blocks or block volumes.
- Reserved plot chunks that let vanilla Minecraft tick sublevel blocks.
- Redstone and block/fluid scheduled tick migration and persistence.
- Block entity support for common containers and functional blocks.
- Entity query projection for pressure plates, item pickup, item frames, and
  similar vanilla logic.
- Sublevel block collision on server and client so players/entities can stand on
  basic moving assemblies.
- Item frame and painting support inside sublevels, including comparator reads
  for item frames.
- Persistent sublevel save/restore through per-level `SavedData`.
- Optional debug visual proxies and runtime profiling commands.

## Current Limits

- This is still experimental mod infrastructure, not a polished gameplay mod.
- Sublevel collision is projected from block AABBs. It covers basic standing and
  blocking, but is not a full precision OBB/contact solution.
- Fast moving platforms, rotating platforms, velocity inheritance, joints, cloth,
  FEM, vehicles, and gameplay integrations are outside the current implemented
  scope.
- Liquid placement inside sublevels is not supported yet.
- Native PhysX packaging is only automated for Linux x86_64 at the moment.

## Build

```text
./gradlew build
```

Run the dev client:

```text
./gradlew runClient
```

The normal build does not bundle the native PhysX bridge. For Linux x86_64,
native packaging can be built and bundled with:

```text
./gradlew buildNativeLinux
./gradlew -Pphysx4mc.bundleNativeLinux=true build
```

During dev runs, the Gradle config points `physx4mc.nativeLibraryPath` at:

```text
build/native/linux-x86_64
```

See [docs/native-packaging.md](docs/native-packaging.md) for platform and loader
details.

## Useful Commands

Sublevel commands:

```text
/px4mc sublevel assemble_block <pos> [mass] [debugProxy]
/px4mc sublevel assemble_box <from> <to> [mass] [debugProxy]
/px4mc sublevel list [limit]
/px4mc sublevel pick [maxDistance]
/px4mc sublevel break [maxDistance]
/px4mc sublevel impulse <idPrefix> <x> <y> <z>
/px4mc sublevel remove <idPrefix>
/px4mc sublevel status
```

Mechanics API smoke-test commands:

```text
/px4mc mechanics spawn_box [size] [mass] [debugProxy]
/px4mc mechanics list [limit]
/px4mc mechanics impulse <idPrefix> <x> <y> <z>
/px4mc mechanics remove <idPrefix>
/px4mc mechanics show <idPrefix>
/px4mc mechanics hide <idPrefix>
```

Client status:

```text
/px4mc_client sublevel_status
```

## Documentation Map

- [docs/milestones_2.md](docs/milestones_2.md): current sublevel milestone state.
- [docs/sublevel.md](docs/sublevel.md): sublevel ownership model and commands.
- [docs/sublevel-interactions.md](docs/sublevel-interactions.md): interaction
  routing.
- [docs/sublevel-query-bridge.md](docs/sublevel-query-bridge.md): entity query
  projection.
- [docs/mechanics-api.md](docs/mechanics-api.md): public mechanics API surface.
- [docs/chunk-collision-pipeline.md](docs/chunk-collision-pipeline.md):
  terrain collision build pipeline.
- [docs/runtime-profiling.md](docs/runtime-profiling.md): profiling and runtime
  status.
- [docs/native-packaging.md](docs/native-packaging.md): native bridge packaging.

## Development Notes

The central design rule is to keep vanilla Minecraft authoritative wherever
possible. Sublevel blocks live in reserved plot chunks so redstone, block
entities, scheduled ticks, entity queries, comparator reads, and normal block
updates can continue using Minecraft's own systems. PhysX4mc maps those plot
chunks to a moving physics body and bridges the places where vanilla world
coordinates no longer match physical world coordinates.

For new work, prefer narrow compatibility fixes over broad rewrites. Add a
focused repro first, then patch the specific bridge boundary that fails.

## Third-party References

- NVIDIA PhysX SDK: <https://github.com/NVIDIA-Omniverse/PhysX>
- PhysX license: BSD-3-Clause, see
  <https://github.com/NVIDIA-Omniverse/PhysX/blob/main/LICENSE.md>

PhysX4mc uses PhysX through a native bridge. Third-party software remains under
its own license terms.

## License

The project metadata currently declares `All Rights Reserved`.
