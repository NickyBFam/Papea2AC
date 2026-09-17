plugins { base }
allprojects {
    group = "me.nicolas.papea2ac"
    version = "1.0-SNAPSHOT"
    repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
}
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror")) }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); maxHeapSize = "1g"; systemProperty("repo.root", rootDir.absolutePath) }
    tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
    dependencyLocking { lockAllConfigurations() }
}
