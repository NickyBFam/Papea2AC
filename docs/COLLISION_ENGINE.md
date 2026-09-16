# Collision engine

## Decision C1: pure geometry with contextual shapes

**DECISION:** Separate geometry from versioned shape resolution and world's BlockBehaviorQuery. Shape kinds have version provenance; combat occlusion is a use that may alias the client's outline/pick path, not an invented third geometry family. Context includes pose, equipment/abilities, fluid, neighbors and temporal revision.

**WHY:** One material-to-box lookup cannot describe partial, dynamic or contextual blocks.

**ALTERNATIVES:** Treat all blocks as unit cubes; call Paper ray tracing from workers; cache every resolved shape by material alone.

**TRADEOFFS:** More cache keys and coverage conditions; correctness outweighs a superficially high hit rate.

## Contracts and operations

ShapeQuery accepts canonical block state, ShapeKind, position-independent context, neighbor signature, version profile and immutable WorldView. It returns canonical local-space boxes or a contextual resolver result, dependencies and completeness. Geometry types are immutable AABB, vector, ray, interval and ordered shape collection. Negative coordinates, face-touching and degenerate boxes have explicit tested semantics.

MovementQuery accepts prior body box, requested displacement, step capability and supported motion mode. It returns clipped displacement, contacts/normals, ground-support provenance, swept coverage and uncertainty. Broad phase enumerates bounded cells intersecting the swept volume; narrow phase clips using the selected version's axis/step ordering. Do not invent a universal XYZ ordering. Ground state claimed by the client is compared with collision-derived support, not trusted as geometry input.

RayQuery accepts origin, normalized direction, finite reach interval, requested shape kind and world slice. It returns first intersection parameter/face and coverage. Zero direction components use explicit parallel-slab handling; NaN/infinity are rejected. An origin inside a shape, face grazing and equal-distance hits have specified deterministic tie rules. Reach checks use intersection distance from the correct eye position, not center-to-center distance.

## Numerical and contextual rules

Physics dictates float/double conversions and movement clipping order. Tolerances must name their source: coordinate quantization, known client arithmetic or profile-tested boundary behavior. No global epsilon expansion that compounds on each tick. Contact tolerance is separate from a violation threshold. Record the applied numeric profile in evidence/replay.

Static origin-shape cache keys include ruleset, shape kind and canonical state. Context-sensitive caches additionally include the full finite context and neighbor signature; otherwise resolve without caching. Moving blocks and temporal effects cannot share a static cache entry. Cache eviction changes performance only, never query results. Recursive neighbor resolution has a depth/work cap and detects cycles; exhaustion returns UNKNOWN, not an empty shape.

Candidate scratch geometry is actor-local and reset per event. Bound touched cells, boxes, recursion and cache bytes; see [Performance](PERFORMANCE.md). A large displacement does not allocate a giant swept-volume array: range check then bounded iteration, returning BUDGET_EXHAUSTED if incomplete.

## Verification obligations

Fixtures cover slabs, stairs, fences/walls, doors/trapdoors, snow layers, scaffolding, powder snow, fluids, climbables, honey/slime, pistons/moving shapes, world border, vehicle and pose changes. Property tests check translation invariance within precision bounds, broad-phase containment and deterministic cache/no-cache equality. Compare supported cases against independently captured vanilla behavior; an internal geometry test alone cannot establish version correctness.

Runtime ShapeQuery uses interned BlockStateId plus ruleset/context; canonical state keys are archival identities. Body collision includes profile-defined solid entity boxes from world's bounded neighborhood, with provenance separate from entity push. Missing collider history invalidates completeness. MovementQuery never calculates friction/inside drag itself: physics queries BlockBehaviorQuery using the independently specified sampling rule. Slime bounce, honey wall-slide, ice friction, soul sand slowdown, bed bounce, web/berry drag, bubble columns and fluid flow need property fixtures in addition to shape fixtures.

Numerical operations follow [Numerical policy](NUMERICAL_POLICY.md); direct engine Math transcendental calls are forbidden. A shape-use mapping fixture must prove combat occlusion behavior on the target client. SafeAnchorValidator in mitigation calls this same pure engine against a bounded server-current destination view captured on the main thread; checks cannot call it or reimplement clearance.
