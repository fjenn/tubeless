# kotlinx.serialization: keep the generated serializers of the library classes
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.tubeless.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.tubeless.data.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
