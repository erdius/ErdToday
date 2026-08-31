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
        // The :caldav module's Ktor surface (HttpClient, Url) is compiled with a newer Kotlin
        // (metadata format 2.3.0) than this project's 1.9.22 compiler natively accepts, which
        // otherwise hard-fails compileDebugKotlin's classpath scan wherever AccountSetupViewModel
        // references those types. Ktor's own classes are plain JVM 8 bytecode (unlike dav4jvm's,
        // which is JVM 21 -- see :caldav/build.gradle.kts for why that's isolated into its own
        // module instead of also needing this flag's kapt-side counterpart here), so skipping the
        // metadata version gate and reading it anyway is safe.
        freeCompilerArgs += "-Xskip-metadata-version-check"
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
configurations.matching { it.name.endsWith("CompileClasspath") }.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:${libs.versions.coroutines.get()}",
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
    implementation(project(":caldav"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    debugImplementation(libs.compose.ui.tooling)
}

kapt {
    correctErrorTypes = true
}
