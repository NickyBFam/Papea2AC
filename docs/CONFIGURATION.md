# Configuration and integration controls

## Decision CFG1

**DECISION:** Core owns the versioned configuration schema, validation, defaults and canonical ConfigSnapshot in model. Platform supplies file bytes; replay receives ConfigSnapshotDto from core. Engines receive immutable subsets, not file loaders.

**WHY:** Budgets and policy are deterministic inputs. **ALTERNATIVES:** Hash raw YAML or scatter defaults across engines. **TRADEOFFS:** Schema migration and explicit reload classification are required.

Fields: schemaVersion; mode (OBSERVE_ONLY default); memory.totalBytes, capacity, sessionTier, replay pool partitions, history target; worker count/work quotas; adapter profile; numeric profile; per-capability qualification; scoped exemptions; anomaly guard thresholds; intent deadline/anchor age/distance; capture policy DURABLE_REQUIRED; disk maxBytes=5 GiB/maxAge=7 days; diagnostics/export permissions. Each numeric unit is explicit. Unknown keys, negative limits, duplicate keys, unsupported enum values and incompatible floor allocations fail validation. Defaults are applied once by the schema, never guessed after errors. Startup failure leaves enforcement off with a reason.

Canonical form: expand defaults, normalize units to integers or specified decimal/binary representations, sort object keys ordinally, preserve semantically ordered arrays, sort only schema-declared sets, emit UTF-8 canonical JSON with LF and no insignificant whitespace; reject NaN/infinity. SHA-256 covers this semantic form plus schema version. Comments/key order/formatting do not change the digest. Engine/replay identifiers reference both schema and digest.

Runtime changes: permissioned admin command -> validated immutable proposal -> global control generation update -> sequenced ConfigApplied record per session. Runtime mode may only become more restrictive immediately; re-enable requires all admission guards and explicit authorized command. Data-plane settings apply at an actor boundary and start a validation epoch if they invalidate state. Worker count, registry and adapter changes require restart/requalification; plugin hot reload remains unsupported. History reductions cause explicit coverage loss. Effective budgets are recorded per event, not only in the header.

Public API permissions: platform authenticates plugin identity/command sender; core bounds request sizes and enqueues notices. Maximum 32 active exemption scopes per player; expiry uses recorded monotonic time. Query results are redacted immutable summaries with age. Declared effects have source plugin, action ID, scope, type, intended interval and expiry; they correlate with captured authoritative outcomes and never double-apply motion. Unknown behavior is uncovered, not silently legitimized by a vector supplied by another plugin. Server operators deliberately authorize exemption providers; a malicious installed plugin is outside the client-adversary boundary.

Phase 1 acceptance: semantically equivalent configuration produces identical digest, invalid configuration cannot enable enforcement, lower quotas appear in replay and runtime OBSERVE_ONLY transition blocks new effect admission. Phase 2 adds declaration/exemption lifecycle tests. No configuration loader or API is implemented in Phase 0.
