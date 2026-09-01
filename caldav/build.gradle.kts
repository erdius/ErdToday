plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        // dav4jvm 4.0.1 and its Ktor 3.x transitive deps are compiled with a newer Kotlin
        // (metadata format up to 2.4.0) than this project's 1.9.22 compiler natively accepts,
        // which otherwise hard-fails the classpath scan. This module only ever reads these
        // classes with Kotlin's own compiler (no kapt/annotation processing here -- that's the
        // whole reason this module exists, see the comment on the dav4jvm dependency below), and
        // the classes involved (dav4jvm, Ktor, okio, kotlinx-io) use only long-stable
        // suspend/Flow/collections surface, so skipping the version gate and reading the
        // metadata anyway is safe.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

// buildMkCalendarXml (exercised by CalDavDiscoveryTest) calls real dav4jvm code at test-runtime,
// not just compile-time -- and dav4jvm's jar is genuinely JVM-21 bytecode (see the dependency
// comment below), so the forked test-worker JVM itself must be >=21, or it fails to *load* the
// class at all ("UnsupportedClassVersionError ... class file version 65.0"). Gradle's Test task
// picks its worker JVM independently of both JAVA_HOME and the Gradle daemon's own JVM (it
// defaults to org.gradle.java.home, a project- or user-level Gradle property, not an environment
// one) -- so explicitly honor JAVA_HOME here if it's set, rather than silently running tests
// against whatever the ambient org.gradle.java.home happens to be.
tasks.withType<Test>().configureEach {
    System.getenv("JAVA_HOME")?.let { javaHome -> executable = "$javaHome/bin/java" }
}

dependencies {
    components {
        // dav4jvm 4.0.1's published Gradle module metadata declares its variants "compatible
        // with Java 21", which fails Gradle's variant-aware resolution against this project's
        // Java 17 toolchain outright (a separate, earlier gate than the classfile-major-version
        // mismatch this module otherwise works around -- see the dav4jvm dependency comment
        // below). The actual classes only use long-stable JVM/Kotlin surface (confirmed by the
        // on-device CalDAV test in Task 5's brief succeeding), so this rewrites the declared
        // attribute down to 17 rather than bumping this whole project's toolchain to 21 to
        // satisfy a mismatch that isn't really there.
        withModule("com.github.bitfireAT:dav4jvm") {
            allVariants {
                attributes {
                    attribute(
                        org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                        17,
                    )
                }
            }
        }
    }

    // `implementation`, not `api`: this module's only public surface (CalDavDiscovery) takes
    // plain String parameters and returns Result<String>, so no Ktor type is ever part of a
    // public signature -- these stay off :app's compile classpath entirely (still on its runtime
    // classpath, where they're needed to actually run). That's what lets :app build without
    // -Xskip-metadata-version-check: :caldav still needs it to read these classes' own newer
    // Kotlin metadata, but :app never has to.
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")
    implementation("io.ktor:ktor-client-auth:3.5.1")

    // bitfireAT's CalDAV/CardDAV library (the same one DAVx5 is built on), Ktor-based as of 4.x.
    // Its published jar (jitpack.io) is JVM-21 bytecode, which is why this dependency lives in
    // its own module rather than :app directly: :app applies kapt for Room, and kapt's javac
    // stub-compilation pass refuses to link against JVM-21 class files under this project's Java
    // 17 toolchain ("bad class file ... has wrong version 65.0, should be 61.0") even though
    // Kotlin's own compiler reads them fine. This module has no kapt, so it never hits that gate.
    implementation("com.github.bitfireAT:dav4jvm:4.0.1")

    testImplementation(libs.junit)
}
