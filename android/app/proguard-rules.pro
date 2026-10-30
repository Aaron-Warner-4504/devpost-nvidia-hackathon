# kotlinx.serialization: keep generated serializers and the @Serializable model classes' companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.enthral.sentinel.**$$serializer { *; }
-keepclassmembers class ai.enthral.sentinel.** {
    *** Companion;
}
-keepclasseswithmembers class ai.enthral.sentinel.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp ships its own consumer rules; silence optional TLS providers.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# SQLCipher: JNI calls back into these classes by name.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
