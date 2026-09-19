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
