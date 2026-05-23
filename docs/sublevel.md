# SubLevel Prototype

Detached block assemblies now use a Sable-like plot ownership model for the
first server/client slice. It is still transitional, but the active route is
reserved plot chunks plus a physics root transform rather than mirror/patch
block snapshots.

A sublevel is now the gameplay owner for a detached world fragment:

- `SubLevelId` is the stable gameplay id.
- `PhysicsSubLevel` owns the reserved plot metadata, section-local block store,
  source bounds, mechanics body id, and visual proxy bindings.
- `SubLevelBlock` stores original source position, sublevel-local position,
  block state, collision bounds, and visual offset.
- `SubLevelSectionStorage` stores the sublevel as one 16x16x16 section with
  section-local lookup, mutation, and dirty block tracking.
- `SubLevelManager` assembles blocks from the Minecraft world, creates the
  aggregate mechanics body, owns visual sync, and removes/discards sublevels.
- `ServerSubLevelContainer` owns `PlotChunkHolder` instances for reserved plot
  chunks and sends vanilla chunk packets to tracking clients.
- `ClientSubLevelContainer` stores client-side plot `LevelChunk` instances and
  root transform metadata for rendering.

The mechanics body is still the physics representation, but gameplay systems
should address the object through the sublevel id.

## Commands

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

Legacy block commands still work, but now forward to the sublevel manager:

```text
/px4mc block detach <pos> [mass] [debugProxy]
/px4mc block detach_box <from> <to> [mass] [debugProxy]
/px4mc block list [limit]
/px4mc block remove <idPrefix>
```

`idPrefix` matches either the sublevel id or its mechanics body id. Prefer
`/px4mc sublevel impulse` for sublevels; `/px4mc mechanics impulse` only searches
raw mechanics body ids.

## Current Limits

- Physics is still one aggregate AABB body, not a true compound collider.
- `BlockDisplay` visuals are optional debug proxies, not the main presentation.
- Client rendering currently rebuilds and draws plot chunk geometry immediately
  each frame; Sable-like render-section compile/cache integration is not done.
- Vanilla hit-result replacement, breaking overlay, and full interaction input
  routing are not implemented yet.
- Block entities and normal entities are not captured yet.
- Restore remains a legacy/debug path, not the main lifecycle direction.
- Persistence across world reload is not implemented yet.

## M23 Direction

M23.2 starts treating the sublevel as a chunk/section-local gameplay store driven
by the host world's tick. The final target is a reserved plot chunk route, where
sublevel block state, rendering, lighting, and interaction can be bridged through
Minecraft-like chunk semantics. Player physics also moves under PhysX ownership
later so players, sublevels, and terrain participate in the same authority model.

The current storage layer provides:

- section-local block lookup through `SubLevelSectionStorage`;
- reserved plot metadata for the future plot chunk bridge;
- block mutation APIs that mark dirty local positions;
- dirty position inspection and bounded draining for later collider rebuilds;
- player view ray queries through `/px4mc sublevel pick`;
- command-gated local block removal through `/px4mc sublevel break`;
- compatibility with existing `sublevel` commands and visual proxies.
- client presentation from reserved plot chunks using vanilla block chunk
  packets, root transform metadata, direct block/fluid rendering, and block
  entity rendering;
- client plot-chunk selection outline from the same root transform used for
  rendering.

The detailed interaction contract is in `docs/sublevel-interaction-contract.md`.
The read-only query bridge is described in `docs/sublevel-query-bridge.md`.
Command-gated interaction routing is described in
`docs/sublevel-interactions.md`.
The client-side plot-chunk presentation target is described in
`docs/sublevel-client-presentation.md`.
