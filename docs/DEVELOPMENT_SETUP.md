# Development setup

Open the repository root `settings.gradle.kts` as a Gradle project in IntelliJ. Use the checked-in Gradle wrapper, not a manually selected Gradle distribution. This is a 20-module Gradle Kotlin DSL build; the former Maven scaffold is retired.

Compilation and tests require Java 25. The Gradle launcher can use the machine's installed Java 21. These are separate settings: a Java 21 Gradle JVM does not satisfy the Java 25 compilation toolchain.

On this workstation the verified Temurin 25 toolchain is in `.phase1-tools/jdk-25.0.4.1+1`. Root `gradle.properties` explicitly registers that relative location so IDE imports discover it without inheriting a terminal's `JAVA_HOME`. The directory remains ignored. A different workstation can install Java 25 in a standard discoverable location or override `org.gradle.java.installations.paths` in its user Gradle properties. Automatic toolchain downloads remain disabled.

In IntelliJ, open the Gradle tool window and select **Reload All Gradle Projects**. In Settings → Build, Execution, Deployment → Build Tools → Gradle, use the wrapper and an installed Java 21 or Java 25 Gradle JVM. If the project SDK is unset, add the local Java 25 directory under Project Structure → SDKs, then select it as Project SDK. No machine-specific IntelliJ files belong in version control.

From the repository root:

```powershell
./gradlew.bat projects
./gradlew.bat javaToolchains
./gradlew.bat clean test
./gradlew.bat :anticheat-platform-paper:pluginJar
```

Native fixtures additionally require the pinned local Paper runtime described in `anticheat-testing/native-scenario/README.md`; run `:anticheat-testing:nativeTest` after preparing it. `:anticheat-testing:verifyMandatoryCoverage` is a release gate and currently fails because mandatory semantic coverage remains incomplete. That failure is distinct from IDE setup or compilation.

The project is an unqualified Phase 1 development implementation. Consult `docs/PHASE_1_IMPLEMENTATION.md` for current readiness and remaining gates.
