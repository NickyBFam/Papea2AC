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
