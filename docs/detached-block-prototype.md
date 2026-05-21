# Detached Block And SubLevel Prototype

M20 starts the first gameplay-facing prototype above the mechanics API.

The prototype is intentionally small: one Minecraft block can be detached from
the world, converted into a mechanics dynamic box, and optionally visualized
with a `BlockDisplay` using the original block state.

## Commands

```text
/px4mc block detach <pos> [mass] [debugProxy]
/px4mc block detach_box <from> <to> [mass] [debugProxy]
/px4mc block list [limit]
/px4mc block restore <idPrefix>
/px4mc block remove <idPrefix>
```

M22.1 adds the preferred sublevel entry point:

```text
/px4mc sublevel assemble_block <pos> [mass] [debugProxy]
/px4mc sublevel assemble_box <from> <to> [mass] [debugProxy]
/px4mc sublevel list [limit]
/px4mc sublevel impulse <idPrefix> <x> <y> <z>
/px4mc sublevel remove <idPrefix>
/px4mc sublevel status
```

- `detach` replaces the selected block with air, creates a mechanics dynamic box
  at the block collision-shape center, and defaults `debugProxy` to `true`.
- `detach_box` collects non-air blocks with collision shapes inside a small
  axis-aligned range, removes them from the world, and creates one aggregate
  mechanics dynamic box for the whole assembly.
- `sublevel assemble_box` uses the same capture path but treats the result as a
  sublevel instead of a temporary detached block.
- `list` shows detached block bodies nearest to the command source.
- `restore` removes the mechanics body and puts the original block state back at
  the original source position or source range if those positions are still air.
- `remove` removes the mechanics body without restoring the world block.

## Runtime Behavior

After M22.1, `SubLevelManager` owns the captured block list, source bounds,
mechanics body id, and visual proxies. The older block commands are compatibility
wrappers around this model.

Detach uses the collision shape bounding box as the first mechanics shape. This
keeps the first slice compatible with full blocks, slabs, and other simple
partial blocks, while avoiding compound-body scope for now.

`detach_box` is still a prototype, not a compound-body implementation. It uses
one aggregate AABB for physics and keeps each original block as a visual child
`BlockDisplay`. The children all follow the same mechanics body pose.

The terrain chunk containing the changed block and its neighbors are refreshed
after detach or restore. This prevents the new mechanics body from immediately
overlapping stale static terrain collision for the detached block.

## Limits

- `detach` maps one block to one dynamic box.
- `detach_box` maps up to 64 collision blocks to one aggregate dynamic box.
- `detach_box` scans at most 512 block positions and at most 8 blocks per axis.
- Block entity data is not preserved.
- Rotation and final placement are visual/mechanics state only; `restore` still
  restores the original block layout at the original positions.
- Complex voxel shapes and multi-block assemblies are approximated by bounding
  boxes rather than true compound colliders.
- Block entities and normal entities are not captured yet.
- Persistence across world reload is not implemented yet.
