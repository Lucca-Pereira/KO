# R8 / ProGuard rules.
#
# NOTE: minification is currently OFF (see app/build.gradle.kts). These rules are written and
# ready, but turning R8 on is not something to ship without installing the result on a real
# device first: almost everything here is reflection-driven, and the failure mode is a crash on
# a screen you did not happen to open while testing. For a sideloaded personal app, four
# megabytes is not worth that risk taken blind.
#
# To turn it on: set isMinifyEnabled = true, build a release APK, install it, and walk every
# tab — especially recipe chat and the nutrition screens, which lean hardest on serialization.

# ---- kotlinx.serialization ----------------------------------------------------------
# The compiler plugin generates a Companion.serializer() for each @Serializable type and looks
# it up reflectively. Losing it turns every wire DTO and every navigation route into a runtime
# SerializationException.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.lucca.ko.**$$serializer { *; }
-keepclassmembers class com.lucca.ko.** {
    *** Companion;
}
-keepclasseswithmembers class com.lucca.ko.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Enum entries are matched by name when a stored string is read back.
-keepclassmembers enum com.lucca.ko.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Navigation type-safe routes ------------------------------------------------------
# Routes are @Serializable objects resolved at runtime; a stripped serializer means a crash on
# the first navigation rather than at build time.
-keep class com.lucca.ko.ui.nav.** { *; }

# ---- Room -----------------------------------------------------------------------------
# Room ships consumer rules for the generated code; entities are referenced reflectively by the
# generated DAOs and by clearAllTables().
-keep class com.lucca.ko.data.db.** { *; }

# ---- OkHttp / Okio --------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Coil -----------------------------------------------------------------------------
-dontwarn coil.**

# Keep line numbers so a crash report from a release build is worth reading.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
