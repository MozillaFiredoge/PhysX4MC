# SubLevel Prototype

M22.1 moves detached block assemblies toward a Sable-like ownership model.
It is a transitional prototype, not the final chunk-section implementation.

A sublevel is now the gameplay owner for a detached world fragment:

- `SubLevelId` is the stable gameplay id.
- `PhysicsSubLevel` owns the section-local block store, source bounds,
  mechanics body id, and visual proxy bindings.
- `SubLevelBlock` stores original source position, sublevel-local position,
  block state, collision bounds, and visual offset.
- `SubLevelSectionStorage` stores the sublevel as one 16x16x16 section with
  section-local lookup, mutation, and dirty block tracking.
- `SubLevelManager` assembles blocks from the Minecraft world, creates the
  aggregate mechanics body, owns visual sync, and removes/discards sublevels.

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
- Visuals are one `BlockDisplay` per captured block.
- Block entities and normal entities are not captured yet.
- Restore remains a legacy/debug path, not the main lifecycle direction.
- Persistence across world reload is not implemented yet.

## M23 Direction

M23.2 starts treating the sublevel as a chunk-section-like gameplay store driven
by the host world's tick. Player physics also moves under PhysX ownership later
so players, sublevels, and terrain participate in the same authority model.

The current storage layer provides:

- section-local block lookup through `SubLevelSectionStorage`;
- block mutation APIs that mark dirty local positions;
- dirty position inspection and bounded draining for later collider rebuilds;
- player view ray queries through `/px4mc sublevel pick`;
- command-gated local block removal through `/px4mc sublevel break`;
- compatibility with existing `sublevel` commands and visual proxies.

The detailed interaction contract is in `docs/sublevel-interaction-contract.md`.
The read-only query bridge is described in `docs/sublevel-query-bridge.md`.
Command-gated interaction routing is described in
`docs/sublevel-interactions.md`.
