# SubLevel Interaction Commands

M23.4.2 starts routing explicit player actions into sublevel-local block space.

This phase intentionally implements only a command-gated break operation. It
does not hook vanilla left click, right click, placement, or block entity use.

## Commands

```text
/px4mc sublevel break [maxDistance]
```

The command uses the same player eye ray as `/px4mc sublevel pick`. When it hits
a sublevel block, `SubLevelManager` removes that section-local block from
`SubLevelSectionStorage`, marks the local position dirty, and discards the
matching `BlockDisplay` visual if one exists.

If the removed block was the final block in the sublevel, the manager also
removes the sublevel mechanics body and forgets the sublevel record.

## Current Limits

- This is still command-gated; vanilla mouse input is not intercepted.
- Placement and use are not implemented yet.
- Collider rebuild is not implemented yet. Breaking a block updates sublevel
  storage and visuals immediately, but the current aggregate mechanics collider
  remains stale until M23.5.
- The removed block is not dropped as an item and is not restored into the host
  world.

