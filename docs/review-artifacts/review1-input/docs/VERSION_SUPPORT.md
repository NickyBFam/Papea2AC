# Minecraft and Paper version support

## Verified release-day baseline

Research date: September 15, 2026, America/New_York; tool retrieval may be September 16 UTC. Mojang's live [version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) returned `latest.release = 26.3`, with release timestamp 2026-09-15T11:23:02Z. The official [26.3 release announcement](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3) confirms the release.

The [Paper download page](https://papermc.io/downloads/paper) advertises 26.2 build 124 and labels 26.3 experimental. The live [Paper 26.3 build API](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds) returned builds 3, 4 and 5, all channel ALPHA; build 5 timestamp was 2026-09-15T18:45:30Z. Thus latest stable Minecraft and stable Paper do not yet match. These are observed values, not permanent pins.

[Paper requirements](https://docs.papermc.io/paper/getting-started/) specify Java 25 for 26.1+. Existing repository Java 21/Spigot 1.21.11 is older scaffolding, not a current target.

## Decision V1: native current-version qualification

**DECISION:** Target the latest stable Minecraft release with a stable matching Paper build. For present architecture planning, use Java 25, provisional stable Paper 26.2 development baseline and a separate 26.3 compatibility research lane. Do not certify 26.2 as latest-Minecraft support or ALPHA 26.3 as production stable. Before Phase 1 build migration, recheck release channels and pin the newest stable matching pair; if unavailable, only the explicitly provisional development lane can proceed after Phase 0 approval. Production qualification remains blocked until the matching stable pair passes the suite.

**WHY:** Today's release transition makes an unconditional single-version support claim inaccurate.

**ALTERNATIVES:** Target experimental Paper as production; freeze forever on 1.21.11; support all historic versions immediately.

**TRADEOFFS:** A short-lived 26.2 development adapter may need updating. Separate ruleset hashes and qualification records prevent silent reuse of old behavior.

## Ruleset and adapter separation

Pure VersionProfile includes protocol schema/capabilities, canonical registries, numeric/quantization rules, locomotion operation ordering, collision/outline shapes, entity dimensions/poses, interpolation, input domains, attributes/effects, interaction reach and acknowledgement/application guarantees. Every rule has provenance, test coverage and a support state: VERIFIED, OBSERVE_ONLY or UNSUPPORTED.

Platform-paper contains mappings/channel hook and API extraction/execution differences. Transport details do not enter physics. Unknown registry states or custom data-pack behavior produce unavailable capabilities, not a nearest-version fallback. Canonical ruleset digest includes data-pack/server modification assumptions; custom attributes sent by the server are modeled where supported rather than flagged as cheats.

Initial deployment scope: ordinary Paper, native vanilla Java client of the selected version, standard supported mechanics. Folia, ViaVersion translation, Geyser/Bedrock, modded movement, alternate server forks, custom packet transformers and custom physical block mechanics are unverified until dedicated capability contracts and tests exist. Detect identifiable translation/integration paths; require explicit server configuration attestation for transformations that cannot be detected reliably. Never infer full compatibility merely from a successful login.

Unsupported server builds fail enforcement initialization with a clear status; the server may run observation-only by explicit configuration. Partially unsupported player modes disable only dependent validators. A support matrix is mandatory before enforcement: each mechanic lists native-client fixtures, Paper integration status, replay compatibility and known limitations. No release may claim general movement coverage while silently leaving ordinary mechanics untested.

## Upgrade checklist

Record release/channel evidence -> pin server/API/client artifacts, hashes, mappings and Java toolchain -> compare protocol/registries and client behavior -> update isolated adapter/profile -> run protocol, shape, physics, ACK and combat differential fixtures -> review numeric/causal changes -> regenerate only legitimately changed fixture expectations under review -> benchmark -> independent review/human approval -> staged observation rollout -> enable qualified capabilities.

The Gradle wrapper version must be selected from a release supporting execution on Java 25 and checksum-pinned during the later build migration. Pin API snapshots by resolved artifact verification/lock metadata; never let a moving SNAPSHOT redefine replay behavior. No exact protocol integer, ACK tick guarantee or Minecraft operation order is asserted as verified by this architecture research alone.
