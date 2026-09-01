# Release build has R8 minification + resource shrinking enabled (isMinifyEnabled/isShrinkResources
# = true in app/build.gradle.kts).

# Tink/Google Crypto transitive dependency (via security-crypto) uses JSR-305 annotations
# (javax.annotation.Nullable, javax.annotation.concurrent.GuardedBy) which are not on the runtime
# classpath. These are compile-time-only hints; warnings can be safely ignored.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
