# Release build has R8 minification + resource shrinking enabled (isMinifyEnabled/isShrinkResources
# = true in app/build.gradle.kts).

# Tink/Google Crypto transitive dependency (via security-crypto) uses JSR-305 annotations
# (javax.annotation.Nullable, javax.annotation.concurrent.GuardedBy) which are not on the runtime
# classpath. These are compile-time-only hints; warnings can be safely ignored.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Ktor (io.ktor:ktor-client-core-jvm, added for Vikunja sync) transitively depends on
# org.slf4j:slf4j-api, which optionally binds to a logging backend via
# org.slf4j.impl.StaticLoggerBinder at runtime -- absent here since nothing in this app
# configures slf4j. R8 fails hard on the missing reference during release minification
# unless told this is expected.
-dontwarn org.slf4j.impl.StaticLoggerBinder
