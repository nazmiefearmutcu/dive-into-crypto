# Dive Into Crypto — R8 / ProGuard keep rules (release build, isMinifyEnabled=true)

# ---- kotlinx.serialization ----
# Binance DTOs are (de)serialized via generated $$serializer classes; R8 must
# not strip them or the JSON parsing breaks at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer {
    *;
}

# App models — keep all serializers + Companions in our package.
-keep class com.diveintocrypto.android.**$$serializer { *; }
-keepclassmembers class com.diveintocrypto.android.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Ktor / OkHttp ----
# Both ship consumer rules; these are defensive against missing optional deps.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn io.ktor.**
