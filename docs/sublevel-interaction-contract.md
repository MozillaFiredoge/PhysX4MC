# SubLevel Interaction Contract

M23 promotes sublevels from detached visual assemblies into a gameplay/tick
boundary. Two requirements are now architectural constraints:

1. A player that participates in this physics world is represented by a PhysX
   object. PhysX owns the player's physical pose and Minecraft synchronizes the
   player from the PhysX result.
2. A sublevel behaves like a chunk section inside the host world. The host
   world's server tick is also the sublevel tick; sublevels do not run an
   independent scheduler.

## Terms

- Host level: the `ServerLevel` that owns the sublevel.
- Sublevel section: a chunk-section-like block store using section-local
  coordinates. M23 should start with one 16x16x16 section and leave multi-section
  assemblies for later.
- Sublevel root: the PhysX body/root transform that maps sublevel-local
  coordinates into host-world coordinates.
- Player body: the PhysX actor bound to a Minecraft player UUID.
- World bridge: the adapter layer that translates Minecraft queries and events
  between host-world coordinates and sublevel-local coordinates.

## Tick Contract

Sublevels are driven by the normal server tick of their host level:

1. Collect player inputs and Minecraft interaction intents.
2. Apply queued sublevel block changes and neighbor events.
3. Rebuild or update dirty sublevel colliders within the tick budget.
4. Step the PhysX scene once from the host world tick.
5. Synchronize PhysX-owned player poses and sublevel visual/debug proxies.
6. Dispatch deferred block/entity side effects with bounded work.

Terrain queues, collider rebuild queues, and visual synchronization can be
budgeted across ticks, but they still belong to the host world's tick order.

## Player Physics Ownership

The first player prototype should treat the PhysX player body as authoritative
for position, velocity, and collision response. Minecraft input should become
intent applied to the player body, such as impulses, target velocity constraints,
or controller forces. Minecraft should not be the owner of the final player
position while the PhysX body is active.

The likely initial shape is a capsule rather than a block AABB. The first slice
should stay narrow:

- single-player or single-test-player ownership;
- ground movement only;
- no mounts, swimming, elytra, portals, or special vanilla movement modes;
- post-step synchronization from PhysX body pose to Minecraft player pose;
- clear fallback when native physics is unavailable.

Vanilla behavior that depends on player movement, such as fall damage,
knockback, step height, ladders, fluids, and network interpolation, must be
reintroduced explicitly after the ownership rule is proven.

## Sublevel As Chunk Section

The sublevel storage model should expose chunk-section semantics before deeper
mixins are added:

- section-local block positions;
- block state lookup and mutation;
- collision shape queries in local coordinates;
- dirty shape tracking;
- bounded block tick and neighbor update queues;
- a local-to-world transform supplied by the sublevel root.

The safe implementation path is to build a sublevel storage/query API first,
then route selected Minecraft operations into it. Injecting a sublevel as a
fully vanilla chunk should wait until the storage, tick order, and query bridge
are stable.

## Interaction Boundary

Cross-boundary interaction should be explicit and budgeted:

- Player to sublevel: raycasts and block interactions are transformed from
  world coordinates into sublevel-local coordinates, then routed to sublevel
  storage.
- Sublevel to world: the sublevel root collides with host terrain through PhysX,
  while block events that affect the host level pass through the world bridge.
- World entity to sublevel: physical collision should be handled by PhysX first;
  gameplay interactions should start with a whitelist.
- Sublevel block entity behavior: delayed until ordinary block state queries,
  neighbor updates, and player interaction routing are stable.

BlockDisplay visuals remain debug/render proxies. They are not the authority for
sublevel block state, collision, or tick behavior.

## Compatibility And Performance

Mixins should be introduced only where the bridge needs Minecraft behavior that
cannot be reached through public APIs. The likely seams are player movement
application, collision/raycast queries, and block interaction routing.

M23 should avoid per-block entity ownership for normal sublevel content. The
authoritative data is the section store plus dirty collider state. Debug visuals
can remain optional and should keep their own synchronization budget.

## M23 Slices

- M23.1: lock this interaction contract and update the milestone plan.
- M23.2: add a section-local sublevel storage API with dirty block tracking.
- M23.3: add a PhysX-owned player body prototype and post-step player pose sync.
  Implemented as a command-gated dynamic-box prototype; full character
  controller behavior remains later work.
- M23.4: add a vanilla player bridge through a PhysX player proxy. This becomes
  the normal-gameplay path; M23.3 remains an experimental PhysX-owned agent
  mode.
- M23.4.1: add a read-only player view ray query into sublevel-local block
  space.
- M23.4.2: add command-gated sublevel-local block removal through the query
  bridge.
- M23.4.x: route player use/place intents into sublevel-local coordinates for a
  small whitelist.
- M23.5: rebuild dirty sublevel colliders from section storage, still with
  conservative shape scope.
- M23.6: add bounded cross-boundary event dispatch and diagnostics.
