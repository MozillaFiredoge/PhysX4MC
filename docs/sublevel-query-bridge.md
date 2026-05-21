# SubLevel Query Bridge

M23.4.1 adds the first read-only query bridge from vanilla player view space
into moving sublevel block space.

The bridge answers one question directly: which block inside a physics sublevel
is the player looking at? M23.4.2 builds on the same query path for explicit
command-gated block removal.

## Command

```text
/px4mc sublevel pick [maxDistance]
```

The command requires a player command source. It uses the player's eye position
and look direction, transforms the ray into each sublevel body's local space,
tests against captured block collision bounds, and reports the closest hit.

The output includes:

- sublevel id;
- mechanics body id;
- section-local block position;
- original source block position;
- block id;
- world hit position;
- body-local hit position;
- hit distance.

## Transform Model

`SubLevelTransform` maps between host-world coordinates and sublevel body-local
coordinates using the mechanics body pose:

```text
world position = body position + body rotation * body-local position
body-local position = inverse(body rotation) * (world position - body position)
```

For M23.4.1, each captured block is queried as an AABB in body-local space. The
AABB origin comes from `SubLevelBlock.visualLocalOrigin`, which is already the
coordinate used by the current `BlockDisplay` visual proxy sync. This keeps pick
results aligned with the visible prototype.

## Current Limits

- `/px4mc sublevel pick` is read-only. `/px4mc sublevel break` mutates storage
  through the same query path.
- The block test uses each block collision shape's bounding box, not the full
  voxel shape.
- Place/use routing is not implemented yet.
- The current physics collider is still the aggregate mechanics body, so query
  precision can be better than solver collision precision until M23.5.
