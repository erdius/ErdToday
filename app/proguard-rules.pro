# Release build has R8 minification + resource shrinking enabled (isMinifyEnabled/isShrinkResources
# = true in app/build.gradle.kts).

# dav4jvm's XmlUtils.newSerializer()/newPullParser() look up an XmlPullParser implementation
# reflectively via XmlPullParserFactory -- keep dav4jvm's own classes so R8 doesn't strip anything
# it only reaches reflectively, and silence warnings about the org.xmlpull.v1 API surface (Android's
# platform provides the actual implementation at runtime; xpp3, dav4jvm's own declared dependency
# for non-Android JVM targets, is excluded from what :app packages -- see app/build.gradle.kts).
-keep class at.bitfire.dav4jvm.** { *; }
-dontwarn org.xmlpull.v1.**
-dontwarn org.ogce.xpp3.**
