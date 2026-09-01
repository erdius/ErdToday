import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

// Release signing is configured from a gitignored keystore.properties (created locally or by CI).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "com.erdman.erdtoday"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.erdman.erdtoday"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"

        // arm64-only: Mudita Kompakt is Helio A22 (arm64-v8a). Drops other-ABI native code.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Separate package + name so a test build installs alongside the real Today on a
            // Kompakt instead of clashing with it (different signature, can't co-exist otherwise).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 full mode: strips unused code + resources (notably the huge material-icons-extended
            // set down to the handful actually referenced) for a small bundle.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Real release key when keystore.properties is present (local or CI); falls back to the
            // debug key so the project still builds without it.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // MMD components (TopAppBarMMD, ModalBottomSheetMMD, DatePickerMMD, …) wrap experimental
        // Material3 APIs. Opt in project-wide since nearly every screen uses them.
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// :caldav's dav4jvm/Ktor dependencies transitively require kotlin-stdlib 2.4.10 and
// kotlinx-coroutines-core 1.11.0 (newer than this project's 1.9.22 Kotlin toolchain). Once merged
// into :app's combined dependency graph (via implementation(project(":caldav"))), that runs into
// two, opposite constraints:
//  - COMPILE TIME: Room's kapt annotation processor breaks on them. Its bundled
//    kotlinx-metadata-jvm reader caps out at metadata format 2.0.0 and throws a hard error
//    inspecting suspend/Flow<T>/Deferred<T> DAO return types (which resolve kotlin-stdlib's
//    Continuation and coroutines-core's Deferred) once either resolves to a 2.x-metadata build.
//  - RUNTIME: dav4jvm's compiled bytecode directly calls symbols (e.g.
//    kotlin.coroutines.jvm.internal.SpillingKt) that only exist in the newer versions --
//    confirmed by a NoClassDefFoundError during the on-device CalDAV discovery test when this
//    force was first applied blanket (configurations.all), which pinned the *packaged* jars down
//    too, not just the compile-time ones.
//
// Scoping the force to *CompileClasspath configurations only (not runtime/packaging) satisfies
// both: kapt/kotlinc type-check our code against the older, Room-compatible API surface (a strict
// subset of the newer one, since Kotlin/coroutines evolve additively), while the actual jars
// bundled into the APK stay at the newer versions dav4jvm's bytecode needs to run.
//
// kotlinx-coroutines-test is a third case of the same underlying problem, surfaced only after
// bumping AGP/Gradle in Task 5b: it isn't itself a direct dependency of :caldav, but kotlinx's
// coroutines artifacts publish a self-aligning constraint against a shared virtual
// "kotlinx-coroutines-bom" platform, so once :caldav's Ktor/dav4jvm graph requests
// kotlinx-coroutines-core 1.11.0 anywhere in a *CompileClasspath configuration, Gradle's version
// alignment pulls kotlinx-coroutines-test up to match -- even though coroutines-core itself is
// forced back down to 1.8.1 above, leaving coroutines-test alone at a newer, Kotlin-2.x-metadata
// build that :app's test sources (which call kotlinx-coroutines-test's runTest) can't compile
// against under this project's Kotlin 1.9.22. Forced down here for the same compile-classpath-only
// reason as kotlin-stdlib/coroutines-core above -- this dependency is test-only and never packaged,
// so there's no runtime/device counterpart to worry about (unlike okio/coroutines-core below).
//
// okio is a separate case of the same underlying problem, caught only after sealing Ktor types
// out of :caldav's public API (see CalDavDiscovery/CalDavHttpClient): :app already depended on
// okio transitively via androidx.datastore (requesting 3.4.0, metadata-compatible, predating this
// task entirely) -- but AGP's "consistent resolution" feature forces a module's version to match
// between a variant's compile and runtime classpaths, and :caldav's Ktor OkHttp engine pulls a
// newer okio (3.17.0, metadata 2.1.0) onto :app's *runtime* classpath, which then forces that same
// 3.17.0 back onto :app's *compile* classpath too, breaking compileDebugKotlin's classpath scan.
// Forced to datastore's own already-declared 3.4.0 for the same reason as kotlin-stdlib/coroutines
// above: additive API evolution means compiling against the older version is safe, and this is
// compile-classpath-only so the packaged jar still gets 3.17.0.
configurations.matching { it.name.endsWith("CompileClasspath") }.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:${libs.versions.coroutines.get()}",
            "com.squareup.okio:okio-jvm:3.4.0",
            "com.squareup.okio:okio:3.4.0",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.android.material)
    implementation(libs.mmd)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Secure credential storage (Fastmail CalDAV account email + app password)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // CalDAV discovery + task-collection sync. dav4jvm/Ktor live in their own module (:caldav,
    // not applying kapt) because dav4jvm 4.0.1's published jar is JVM-21 bytecode, which kapt's
    // javac stub-compilation pass refuses to link against under this project's Java 17 toolchain
    // ("bad class file ... has wrong version 65.0, should be 61.0") -- unlike Kotlin's own
    // compiler, which reads it fine. Isolating it keeps :app's kapt/Room pipeline untouched by
    // that constraint. See :caldav/build.gradle.kts for the dav4jvm/Ktor dependency declarations.
    implementation(project(":caldav")) {
        // xpp3 (dav4jvm's XmlPullParser implementation, needed for :caldav's own JVM unit tests)
        // duplicates org.xmlpull.v1.XmlPullParser, which Android's platform SDK already provides
        // and implements natively (android.content.res.XmlResourceParser) -- R8 refuses to
        // process an app-bundled ("program") class that a platform ("library") class implements:
        // "Library class android.content.res.XmlResourceParser implements program class
        // org.xmlpull.v1.XmlPullParser". Excluded here (only from what :app packages, not from
        // :caldav's own dependency graph) since Android's built-in xmlpull implementation already
        // satisfies dav4jvm's XmlUtils.newSerializer() lookup at runtime on-device.
        exclude(group = "org.ogce", module = "xpp3")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    debugImplementation(libs.compose.ui.tooling)
}

kapt {
    correctErrorTypes = true
}
