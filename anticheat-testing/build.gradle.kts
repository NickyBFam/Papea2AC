dependencies {
    testImplementation(project(":anticheat-api"))
    testImplementation(project(":anticheat-model"))
    testImplementation(project(":anticheat-replay-schema"))
    testImplementation(project(":anticheat-version"))
    testImplementation(project(":anticheat-protocol"))
    testImplementation(project(":anticheat-packet"))
    testImplementation(project(":anticheat-player"))
    testImplementation(project(":anticheat-world"))
    testImplementation(project(":anticheat-collision"))
    testImplementation(project(":anticheat-physics"))
    testImplementation(project(":anticheat-simulation"))
    testImplementation(project(":anticheat-combat"))
    testImplementation(project(":anticheat-interaction"))
    testImplementation(project(":anticheat-evidence"))
    testImplementation(project(":anticheat-checks"))
    testImplementation(project(":anticheat-mitigation"))
    testImplementation(project(":anticheat-replay"))
    testImplementation(project(":anticheat-core"))
    testImplementation(project(":anticheat-platform-paper"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("io.netty:netty-transport:4.2.15.Final")
}

val nativeTest by sourceSets.creating
tasks.register<JavaExec>("verifyMandatoryCoverage") {
    description = "Release gate: fails while any mandatory packet semantics remain incomplete."
    dependsOn(tasks.named(nativeTest.classesTaskName))
    classpath = nativeTest.runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.MandatoryCoverageMain")
}
tasks.test { systemProperty("test.runtime.classpath", sourceSets.test.get().runtimeClasspath.asPath) }
tasks.register<JavaExec>("loadProfile") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.LoadProfileMain")
    minHeapSize = "512m"
    maxHeapSize = "1g"
    args(rootProject.file("docs/phase1-artifacts/synthetic-load.json").absolutePath)
}
tasks.register<JavaExec>("replayConformance") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ReplayConformanceMain")
    args(rootProject.file("docs/phase1-artifacts/transport-conformance.capsule.b64").absolutePath)
}
configurations[nativeTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[nativeTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(nativeTest.runtimeOnlyConfigurationName, files(rootProject.file(".phase1-runtime/versions/26.2/paper-26.2.jar")))
    add(nativeTest.runtimeOnlyConfigurationName, fileTree(rootProject.file(".phase1-runtime/libraries")) { include("**/*.jar") })
}
tasks.register<Test>("nativeTest") {
    description = "Pinned native-server packet and Netty boundary tests; requires prepared local Paper runtime."
    testClassesDirs = nativeTest.output.classesDirs
    classpath = nativeTest.runtimeClasspath
    doFirst { check(rootProject.file(".phase1-runtime/versions/26.2/paper-26.2.jar").exists()) { "Prepare pinned Paper runtime first" } }
}
tasks.register<JavaExec>("captureInventory") {
    dependsOn(tasks.named(nativeTest.classesTaskName))
    classpath = nativeTest.runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.InventoryMain")
    args(rootProject.file("docs/phase1-artifacts/paper-26.2-124-packet-inventory.tsv").absolutePath)
}
tasks.register<JavaExec>("playerTimelineMemoryHarness") {
    description = "Empirical (forced-GC heap diff) Phase 2 Timeline memory estimate across representative workloads. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.PlayerTimelineMemoryMain")
    minHeapSize = "512m"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase2-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase2-artifacts").mkdirs() }
}
tasks.register<JavaExec>("timelineFixtures") {
    description = "Regenerates the pinned Phase 2 timeline fixture digests (docs/phase2-artifacts/timeline-fixtures.json). A changed digest is a reviewed event."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.TimelineFixtureMain")
    args(rootProject.file("docs/phase2-artifacts/timeline-fixtures.json").absolutePath)
    doFirst { rootProject.file("docs/phase2-artifacts").mkdirs() }
}
tasks.register<JavaExec>("worldFixtures") {
    description = "Regenerates the pinned Phase 3 world fixture digests (docs/phase3-artifacts/world-fixtures.json). A changed digest is a reviewed event."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.WorldFixtureMain")
    args(rootProject.file("docs/phase3-artifacts/world-fixtures.json").absolutePath)
    doFirst { rootProject.file("docs/phase3-artifacts").mkdirs() }
}
tasks.register<JavaExec>("heavyNormalizerBenchmark") {
    description = "Descriptive (not JMH) hot-path timing for item/container/chunk/entity-metadata normalization."
    dependsOn(tasks.named(nativeTest.classesTaskName))
    classpath = nativeTest.runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.HeavyNormalizerBenchmarkMain")
    args(rootProject.file("docs/phase1-artifacts/heavy-normalizer-benchmark.json").absolutePath)
}

val benchmark by sourceSets.creating
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
dependencies {
    add(benchmark.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(benchmark.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
    add(benchmark.implementationConfigurationName, sourceSets.test.get().output) // Phase 10: the mitigation kernels run on the real world/collision test harness (MitigationActorTest.H0)
}
tasks.register<JavaExec>("benchmark") {
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("PacketBenchmarks", "-prof", "gc", "-rf", "json", "-rff", rootProject.file("docs/phase1-artifacts/benchmarks.json").absolutePath)
    doFirst { rootProject.file("docs/phase1-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkPlayerTimeline") {
    description = "Phase 2 JMH kernels: Timeline append/lookup/eviction hot paths. Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("PlayerTimelineBenchmarks", "-prof", "gc", "-rf", "json", "-rff", rootProject.file("docs/phase2-artifacts/benchmarks.json").absolutePath)
    doFirst { rootProject.file("docs/phase2-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkObserverWorld") {
    description = "Phase 3 JMH kernels: observer-world baseline ingest, block updates, lookups, copy-on-write, unload, history eviction. Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("ObserverWorldBenchmarks", "-prof", "gc", "-rf", "json", "-rff", rootProject.file("docs/phase3-artifacts/benchmarks.json").absolutePath)
    doFirst { rootProject.file("docs/phase3-artifacts").mkdirs() }
}
tasks.register<JavaExec>("observerWorldMemoryHarness") {
    description = "Empirical (forced-GC heap diff) Phase 3 observer-world memory estimate across view-size scenarios, shared vs unique content. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ObserverWorldMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase3-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase3-artifacts").mkdirs() }
}
tasks.register<JavaExec>("inspectCapsule") {
    description = "Read-only inspection of a recorded transport capsule (qualification evidence): -Pcapsule=<file> [-Pfilter=BlockChange,SectionBlocksUpdate]"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.CapsuleInspectMain")
    val capsule = project.findProperty("capsule")?.toString() ?: ""
    val filter = project.findProperty("filter")?.toString() ?: ""
    args(listOf(capsule) + filter.split(",").filter { it.isNotBlank() })
}
tasks.register<JavaExec>("extractCollisionShapes") {
    description = "Phase 4 controlled extraction: regenerates the version-pinned vanilla collision shape table (anticheat-version resource) and its summary from the pinned Paper jar. Never runs in production; a changed table is a reviewed event."
    dependsOn(tasks.named(nativeTest.classesTaskName))
    classpath = nativeTest.runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.CollisionShapeExtractMain")
    args(rootProject.file("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/collision-shapes-paper-26.2-124.tsv").absolutePath,
         rootProject.file("docs/phase4-artifacts/shape-table-summary.json").absolutePath)
    doFirst { check(rootProject.file(".phase1-runtime/versions/26.2/paper-26.2.jar").exists()) { "Prepare pinned Paper runtime first" } }
}
tasks.register<JavaExec>("collisionFixtures") {
    description = "Regenerates the pinned Phase 4 collision fixture digests (docs/phase4-artifacts/collision-fixtures.json). A changed digest is a reviewed event."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.CollisionFixtureMain")
    args(rootProject.file("docs/phase4-artifacts/collision-fixtures.json").absolutePath)
    doFirst { rootProject.file("docs/phase4-artifacts").mkdirs() }
}
tasks.register<JavaExec>("shapeCoverage") {
    description = "Phase 4 shape coverage inventory (docs/phase4-artifacts/shape-coverage.tsv + .json): every block of the pinned profile classified; the ShapeCoverageInventoryTest gate asserts zero unexplained UNSUPPORTED."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ShapeCoverageMain")
    args(rootProject.file("docs/phase4-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase4-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkCollision") {
    description = "Phase 4 JMH kernels: shape lookup, broad phase, no-hit/many-hit/historical/pathological queries, clip. Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("CollisionBenchmarks", "-prof", "gc", "-rf", "json", "-rff", rootProject.file("docs/phase4-artifacts/benchmarks.json").absolutePath)
    doFirst { rootProject.file("docs/phase4-artifacts").mkdirs() }
}
tasks.register<JavaExec>("collisionMemoryHarness") {
    description = "Empirical (forced-GC heap diff) Phase 4 collision memory estimate: shape table, resolver cache, engine, per-query retention. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.CollisionMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase4-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase4-artifacts").mkdirs() }
}
tasks.register<JavaExec>("extractMovementProperties") {
    description = "Phase 5 controlled extraction: regenerates the version-pinned vanilla movement property tables (block friction/speed/jump/bounce/tags/override classes/fluid states, player attributes, effect templates, Mth.SIN bits) from the pinned Paper jar. Never runs in production; a changed table is a reviewed event."
    dependsOn(tasks.named(nativeTest.classesTaskName))
    classpath = nativeTest.runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.MovementPropertyExtractMain")
    args(rootProject.file("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/block-movement-paper-26.2-124.tsv").absolutePath,
         rootProject.file("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/movement-profile-paper-26.2-124.tsv").absolutePath,
         rootProject.file("anticheat-version/src/main/resources/me/nicolas/papea2ac/version/mth-sin-paper-26.2-124.bin").absolutePath,
         rootProject.file("docs/phase5-artifacts/movement-table-summary.json").absolutePath)
    doFirst { check(rootProject.file(".phase1-runtime/versions/26.2/paper-26.2.jar").exists()) { "Prepare pinned Paper runtime first" } }
}

// Phase 5 native oracle: a test-only Paper plugin compiled directly against the pinned Mojang-mapped server jar.
val oracle by sourceSets.creating
dependencies {
    add(oracle.compileOnlyConfigurationName, files(rootProject.file(".phase1-runtime/versions/26.2/paper-26.2.jar")))
    add(oracle.compileOnlyConfigurationName, fileTree(rootProject.file(".phase1-runtime/libraries")) { include("**/*.jar") })
}
tasks.named<JavaCompile>(oracle.compileJavaTaskName) {
    doFirst { options.compilerArgs.removeAll(listOf("-Werror", "-Xlint:all")); options.compilerArgs.add("-Xlint:none") }
}
tasks.register<Jar>("physicsOracleJar") {
    description = "Builds the Phase 5 test-only oracle plugin (never shipped)."
    archiveBaseName.set("physics-oracle"); archiveVersion.set("")
    from(oracle.output)
    manifest { attributes("paperweight-mappings-namespace" to "mojang") }
}
tasks.register<JavaExec>("physicsNativeDifferential") {
    description = "Native differential: starts an isolated pinned Paper server with the oracle plugin, runs the scenario catalog through the server's own movement code, then compares every tick against the Papea2AC simulator. Since P6-F1 (client input pipeline) it writes docs/phase6-artifacts/native-differential-results.json, multi-tick-drift-results.json and physics-fixtures/native-trajectories.json; the Phase 5 artifacts are the frozen pre-P6-F1 record."
    dependsOn(tasks.named(nativeTest.classesTaskName), tasks.named("physicsOracleJar"))
    classpath = nativeTest.runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.PhysicsOracleMain")
    args(rootProject.file(".phase1-runtime").absolutePath, layout.buildDirectory.dir("physics-oracle").get().asFile.absolutePath,
         rootProject.file("docs/phase9-artifacts").absolutePath, tasks.named<Jar>("physicsOracleJar").get().archiveFile.get().asFile.absolutePath)
    doFirst { check(rootProject.file(".phase1-runtime/versions/26.2/paper-26.2.jar").exists()) { "Prepare pinned Paper runtime first" }; rootProject.file("docs/phase9-artifacts").mkdirs() }
}
// the nativeTest lane reuses the test lane's scenario catalog / world builders (same package, package-private access)
dependencies { add(nativeTest.implementationConfigurationName, sourceSets.test.get().output) }
tasks.register<JavaExec>("movementPropertyCoverage") {
    description = "Phase 5 movement property coverage inventory (docs/phase5-artifacts/movement-property-coverage.tsv + .json): every block of the pinned profile classified; the MovementPropertyCoverageTest gate asserts zero unexplained gaps."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.MovementPropertyCoverageMain")
    args(rootProject.file("docs/phase5-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase5-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkPhysics") {
    description = "Phase 5 JMH kernels: one forward step per movement family, 20-tick chained walk, candidate scaling 1/8/32/64. Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args("PhysicsBenchmarks", "-prof", "gc", "-rf", "json", "-rff", rootProject.file("docs/phase5-artifacts/benchmarks.json").absolutePath)
    doFirst { rootProject.file("docs/phase5-artifacts").mkdirs() }
}
tasks.register<JavaExec>("physicsMemoryHarness") {
    description = "Empirical (forced-GC heap diff + thread allocation) Phase 5 physics memory estimate: profile tables, behaviour cache, simulator, result retention, per-step allocation. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.PhysicsMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase5-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase5-artifacts").mkdirs() }
}

// ---- Phase 6: input reconstruction harnesses (test lane; no server needed)
tasks.register<JavaExec>("reconstructionFixtures") {
    description = "Regenerates the pinned Phase 6 reconstruction fixture summaries (docs/phase6-artifacts/reconstruction-fixtures/reconstruction-fixtures.json). A changed digest is a reviewed event."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ReconstructionFixtureMain")
    args(rootProject.file("docs/phase9-artifacts/reconstruction-fixtures/reconstruction-fixtures.json").absolutePath, rootProject.file("docs/phase9-artifacts/input-domain.json").absolutePath) // P9-F3 re-pin; docs/phase6-artifacts keeps the P6 pin
    doFirst { rootProject.file("docs/phase6-artifacts/reconstruction-fixtures").mkdirs() }
}
tasks.register<JavaExec>("reconstructionDistribution") {
    description = "Phase 6 candidate-count distribution over the 88 pinned native trajectories and a deterministic random corpus (docs/phase6-artifacts/candidate-distribution.json + native-reconstruction-results.json); fails on any false exclusion."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ReconstructionDistributionMain")
    args(rootProject.file("docs/phase6-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase6-artifacts").mkdirs() }
}
tasks.register<JavaExec>("reconstructionMemoryHarness") {
    description = "Empirical (forced-GC heap diff + thread allocation) Phase 6 reconstruction memory estimate: reconstructor, retained results, per-request allocation with the envelope prefilter on/off. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ReconstructionMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase6-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase6-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkReconstruction") {
    description = "Phase 6 JMH kernels: one reconstruction request per family (idle unique/ambiguous, walk, strafe, diagonal, sprint, jump, airborne, wall, step, stairs, ice, 36/72/144 raw candidates, multi-tick 2/3, empty set, worst bounded search) plus breakdown kernels. Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    // -PjmhInclude=<regex> re-runs a subset into -PjmhOut=<file> (partial re-measurement after a targeted change; the full file stays the record)
    val include = project.findProperty("jmhInclude")?.toString() ?: "ReconstructionBenchmarks"
    val outFile = project.findProperty("jmhOut")?.toString() ?: rootProject.file("docs/phase6-artifacts/benchmarks.json").absolutePath
    args(include, "-prof", "gc", "-rf", "json", "-rff", outFile)
    doFirst { rootProject.file("docs/phase6-artifacts").mkdirs() }
}
// ---- Phase 7: timing fixtures/gates artifacts, memory harness, JMH
tasks.register<JavaExec>("timingFixtures") {
    description = "Phase 7 evidence artifacts under docs/phase7-artifacts: deterministic timing fixtures, velocity/teleport/fuzz round-trip gate results, the RTT model results (summaries, no packet dumps). A changed fixture digest is a reviewed event."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.TimingFixtureMain")
    args(rootProject.file("docs/phase9-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase9-artifacts").mkdirs() }
}
tasks.register<JavaExec>("timingMemoryHarness") {
    description = "Empirical (forced-GC heap diff + thread allocation) Phase 7 memory estimate: per-session ledger/estimator/marker state, retained resolutions, per-resolution allocation, timing-only versus timing + reconstruction. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.TimingMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase7-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase7-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkTiming") {
    description = "Phase 7 JMH kernels: RTT update, ACK register/resolve, velocity/teleport register, timing-only resolution (1/2/4 branches, 2/3-tick gaps, worst bounded), and timing + Phase 6 + Phase 5 end to end (velocity, teleport, gaps). Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val include = project.findProperty("jmhInclude")?.toString() ?: "TimingBenchmarks"
    val outFile = project.findProperty("jmhOut")?.toString() ?: rootProject.file("docs/phase7-artifacts/benchmarks.json").absolutePath
    args(include, "-prof", "gc", "-rf", "json", "-rff", outFile)
    doFirst { rootProject.file("docs/phase7-artifacts").mkdirs() }
}

// ---- Phase 8: validation fixtures/corpora artifacts, memory harness, JMH
// ---- Phase 9: evidence artifacts, memory harness, JMH
tasks.register<JavaExec>("evidenceFixtures") {
    description = "Phase 9 evidence artifacts under docs/phase9-artifacts: the false-evidence gate over the Phase 8 legitimate corpus, the impossible-movement evidence corpus, duplicate-delivery, window/ordering/epoch results and the deterministic evidence fixtures. Fails on any positive evidence from a legitimate transition."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.EvidenceFixtureMain")
    maxHeapSize = "2g"
    args(rootProject.file("docs/phase9-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase9-artifacts").mkdirs() }
}
tasks.register<JavaExec>("evidenceMemoryHarness") {
    description = "Empirical (forced-GC heap diff + thread allocation) Phase 9 memory estimate: empty / typical / full bounded session evidence state, per event, per snapshot, 100 retained snapshots, per-ingest allocation. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.EvidenceMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase9-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase9-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkEvidence") {
    description = "Phase 9 JMH kernels: evidence-only (VALID/UNCERTAIN/INVALID/duplicate ingest, snapshots 0/1/many/full, window expiry, world-epoch segment, session replacement, mixed 100-record stream, full bounded ingest) and end to end (Phase 8 validation + projection + ingest). Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val include = project.findProperty("jmhInclude")?.toString() ?: "EvidenceBenchmarks"
    val outFile = project.findProperty("jmhOut")?.toString() ?: rootProject.file("docs/phase9-artifacts/benchmarks.json").absolutePath
    args(include, "-prof", "gc", "-rf", "json", "-rff", outFile)
    if (project.hasProperty("jmhSmoke")) args("-f", "1", "-wi", "1", "-i", "1", "-w", "200ms", "-r", "200ms")
    doFirst { rootProject.file("docs/phase9-artifacts").mkdirs() }
}
tasks.register<JavaExec>("validationMemoryHarness") {
    description = "Empirical (forced-GC heap diff + thread allocation) Phase 8 memory estimate: validator, live runner empty/anchored, context ledger empty/at bounds, retained results, per-result allocation classifier-only versus end to end. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ValidationMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase8-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase8-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkValidation") {
    description = "Phase 8 JMH kernels: classifier-only (VALID simple/velocity, INVALID horizontal, UNCERTAIN world, worst bounded) and end to end (VALID simple/ambiguous/velocity, UNCERTAIN world-incomplete/search-limit, INVALID horizontal/vertical, 2/4 branches, 2/3-tick gaps, worst bounded). Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val include = project.findProperty("jmhInclude")?.toString() ?: "ValidationBenchmarks"
    val outFile = project.findProperty("jmhOut")?.toString() ?: rootProject.file("docs/phase8-artifacts/benchmarks.json").absolutePath
    args(include, "-prof", "gc", "-rf", "json", "-rff", outFile)
    if (project.hasProperty("jmhSmoke")) args("-f", "1", "-wi", "1", "-i", "1", "-w", "200ms", "-r", "200ms") // fixture sanity only, never the record
    doFirst { rootProject.file("docs/phase8-artifacts").mkdirs() }
}
tasks.register<JavaExec>("validationFixtures") {
    description = "Phase 8 evidence artifacts under docs/phase8-artifacts: deterministic validation fixtures, legitimate-corpus / false-positive results, native and timing corpus validation, impossible-movement detection results (summaries only). Fails on any false INVALID."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.ValidationFixtureMain")
    maxHeapSize = "2g"
    args(rootProject.file("docs/phase9-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase9-artifacts").mkdirs() }
}
// ---- Phase 10: TC25 durable decision-capsule gate, mitigation artifacts, memory harness, JMH
tasks.register<JavaExec>("tc25") {
    description = "TESTING TC25 (Phase 10 gate, Decision 3 step 4): sparse movement / dense combat / chunk churn decision capsules through the production DecisionCapture + DurableCapsuleWriter (real fsync) under the declared load; >=99% durable within 400 ms at the configured capacity. Writes docs/phase10-artifacts/tc25-results.json and tc25-result.properties."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.Tc25Main")
    minHeapSize = "2g"; maxHeapSize = "2g"
    val gate = project.findProperty("tc25Gate")?.toString() ?: "32"
    val sweep = project.findProperty("tc25Sweep")?.toString() ?: "128,1000"
    val warm = project.findProperty("tc25Warmup")?.toString() ?: "4"
    val measure = project.findProperty("tc25Seconds")?.toString() ?: "20"
    val out = project.findProperty("tc25Out")?.toString() ?: rootProject.file("docs/phase10-artifacts").absolutePath
    args(out, gate, sweep, warm, measure, rootProject.file("build/tc25-capsules").absolutePath)
    doFirst { file(out).mkdirs() }
}
tasks.register<JavaExec>("mitigationMemoryHarness") {
    description = "Empirical (forced-GC heap diff + thread allocation) Phase 10 memory estimate: empty / at-bounds mitigation actor, policy, anchor, intent, runner, rolling capture at a full share, decision capture, per-step allocation. Not a supported-capacity claim."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.MitigationMemoryMain")
    minHeapSize = "1g"; maxHeapSize = "1g"
    args(rootProject.file("docs/phase10-artifacts/memory-results.json").absolutePath)
    doFirst { rootProject.file("docs/phase10-artifacts").mkdirs() }
}
tasks.register<JavaExec>("benchmarkMitigation") {
    description = "Phase 10 JMH kernels: decision (legitimate / uncertain step, anchor admission, OBSERVE_ONLY eligibility cycle, ENFORCE proposal, action id) and capsule (export+append, full-share promotion, encode+deflate). Descriptive, not a capacity claim."
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val include = project.findProperty("jmhInclude")?.toString() ?: "MitigationBenchmarks"
    val outFile = project.findProperty("jmhOut")?.toString() ?: rootProject.file("docs/phase10-artifacts/benchmarks.json").absolutePath
    args(include, "-prof", "gc", "-rf", "json", "-rff", outFile)
    if (project.hasProperty("jmhSmoke")) args("-f", "1", "-wi", "1", "-i", "1", "-w", "200ms", "-r", "200ms") // fixture sanity only, never the record
    doFirst { rootProject.file("docs/phase10-artifacts").mkdirs() }
}
tasks.register<JavaExec>("mitigationFixtures") {
    description = "Phase 10 artifacts under docs/phase10-artifacts: policy corpus, target selection, suppression and setback results and the deterministic mitigation fixtures (from MitigationCorpus). Fails on any setback/eligibility from a legitimate stream."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.MitigationFixtureMain")
    maxHeapSize = "2g"
    args(rootProject.file("docs/phase10-artifacts").absolutePath)
    doFirst { rootProject.file("docs/phase10-artifacts").mkdirs() }
}
tasks.register<JavaExec>("cadenceCorpus") {
    description = "Phase 14 offline cadence corpus: legitimate false-exclusion count, timer detection latency, hitch effect (docs/phase14-artifacts/cadence-corpus-results.json). Descriptive, deterministic."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("me.nicolas.papea2ac.testing.TickCadenceCorpusMain")
    maxHeapSize = "1g"
    args(rootProject.file("docs/phase14-artifacts/cadence-corpus-results.json").absolutePath, (project.findProperty("cadenceSeeds") ?: "2000").toString())
    doFirst { rootProject.file("docs/phase14-artifacts").mkdirs() }
}
