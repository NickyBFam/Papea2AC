dependencies {
    implementation(project(":anticheat-api"))
    implementation(project(":anticheat-model"))
    implementation(project(":anticheat-version"))
    implementation(project(":anticheat-protocol"))
    implementation(project(":anticheat-packet"))
    implementation(project(":anticheat-world"))
    implementation(project(":anticheat-mitigation"))
    implementation(project(":anticheat-core"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")
    compileOnly("io.netty:netty-transport:4.2.15.Final")
}

tasks.register<Jar>("pluginJar") {
    archiveClassifier.set("plugin")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
}
tasks.named("assemble") { dependsOn("pluginJar") }
