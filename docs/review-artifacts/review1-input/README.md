# Papea2AC

Phase 0 architecture specification for a packet-first Minecraft anticheat. **No detections have been implemented.** The existing Maven/Spigot skeleton is preserved; it is not the production target or a supported release.

Start with [Architecture](docs/ARCHITECTURE.md), [review and acceptance criteria](docs/PHASE_0_REVIEW.md), and [Intave reference analysis](docs/INTAVE_REFERENCE_ANALYSIS.md). The [roadmap](docs/ROADMAP.md) defines the implementation gates. Claude is the independent senior architecture reviewer; this submission does not approve itself.

Research baseline: September 15, 2026 (America/New_York). Latest Minecraft release is 26.3; Paper 26.3 is currently ALPHA, while Paper's stable download is 26.2. Target native Java Edition and the latest stable matching Paper release, with Java 25. Paper 26.2 is a provisional development baseline, not a claim of latest-Minecraft support. See [version support and release gate](docs/VERSION_SUPPORT.md).

All project work belongs in `C:\Users\Nicolas\Documents\GitHub\Papea2AC`. The similarly named OneDrive workspace is not this repository.

Phase 0 changes are documentation only. Module directories, Gradle files, production interfaces and tests described below are designs for later approved phases, not generated placeholders. Existing staged files remain staged and unchanged.
