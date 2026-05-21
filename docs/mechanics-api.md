# Mechanics API

M18 starts a public mechanics layer for other simulation mods.

The entry point is:

```java
MechanicsApi mechanics = PhysX4mc.api();
MechanicsWorld world = mechanics.world(serverLevel);
```

This layer is intentionally above the raw PhysX backend and above the current
debug `BlockDisplay` proxy path. External systems should treat PhysX scenes,
native handles, and debug proxies as implementation details.

## Current Surface

The first API slice supports server-side rigid dynamic boxes:

```java
MechanicsBodySnapshot body = world.createDynamicBox(
        MechanicsBoxDefinition.gameplayDynamicBox(
                new PhysicsPose(position, PhysicsQuaternion.IDENTITY),
                new PhysicsVector(0.5D, 0.5D, 0.5D),
                1.0F
        )
);

world.applyLinearImpulse(body.id(), new PhysicsVector(0.0D, 2.0D, 0.0D));
Optional<MechanicsBodySnapshot> latest = world.snapshot(body.id());
```

Available operations:

- create a gameplay dynamic box;
- list mechanics-owned body snapshots in one `ServerLevel`;
- read one body snapshot by stable id;
- set linear velocity;
- apply a linear impulse;
- remove the body.

## Sandbox Commands

M18.1 adds command coverage for this public API surface:

```text
/px4mc mechanics spawn_box [size] [mass] [debugProxy]
/px4mc mechanics list [limit]
/px4mc mechanics impulse <idPrefix> <x> <y> <z>
/px4mc mechanics remove <idPrefix>
/px4mc mechanics show <idPrefix>
/px4mc mechanics hide <idPrefix>
```

The spawn/list/impulse/remove path is an API smoke test: it enters through
`PhysX4mc.api()` and works with `MechanicsWorld`, not debug body commands.

## Ownership

Bodies created through the mechanics API are tracked separately from debug and
stress bodies. They do not automatically create `BlockDisplay` render proxies.
Rendering, gameplay conversion, and lifecycle policy should be layered on top of
this API.

`MechanicsBodySnapshot` reports:

- stable `MechanicsBodyId`;
- Minecraft level key;
- body type and role;
- PhysX pose;
- linear velocity as tracked by the current backend wrapper;
- half extents and mass;
- closed state.

## Debug Proxy Adapter

M18.2 adds an optional debug proxy adapter for mechanics bodies:

- `spawn_box ... true` creates the body and immediately shows a debug proxy.
- `show <idPrefix>` attaches a `BlockDisplay` debug proxy to an existing
  mechanics body.
- `hide <idPrefix>` removes the proxy without removing the mechanics body.
- `remove <idPrefix>` removes the body and also discards any bound debug proxy.

The proxy is intentionally not part of the body model. It is a visualization
adapter backed by the same round-robin sync path as other debug proxies.

M20 extends the adapter internally so gameplay prototypes can render a mechanics
body with a specific block state. Detached blocks use this path to show the
original Minecraft block instead of the generic debug material.

## Coupling Direction

Aerodynamics, electromagnetics, vehicle logic, and RL experiments should couple
through this mechanics layer instead of through native handles:

- Aerodynamics can sample wind and call `applyLinearImpulse` or future force
  APIs.
- Electromagnetics can translate field force/torque probes into mechanics
  impulses or forces.
- Vehicle code can own higher-level assemblies while PhysX4MC owns rigid body
  stepping and collision.
- RL tooling can snapshot state, apply actions, and reset bodies without
  depending on debug entities.

## Current Limits

- The first API slice only exposes dynamic boxes.
- `applyLinearImpulse` is implemented through velocity adjustment using current
  body velocity and tracked mass. The PhysX backend now reads current native
  linear velocity before applying the impulse; a native force/impulse bridge is
  still a later step.
- Snapshots are server-side and per-level.
- Soft bodies, joints, compound bodies, sleep state, contact events, and
  deterministic episode reset are future milestones.
