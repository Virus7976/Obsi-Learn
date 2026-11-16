# kotlinx.serialization keeps its own generated serializers; keep annotations readable.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Vosk speech recognition calls its native library through JNA, which finds classes by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.**
-dontwarn com.google.protobuf.**
-dontwarn org.jsoup.**

# Wireless ADB and Shizuku installers.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.security.** { *; }
-keep class org.conscrypt.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn com.github.MuntashirAkon.**
-dontwarn org.conscrypt.**
-dontwarn android.sun.**

# Shared folders (SMB). jcifs-ng signs in with NTLM, which needs MD4 and other hashes from
# BouncyCastle. BouncyCastle finds each algorithm by class name at run time, so the shrinker
# cannot see them and removed them ("no such algorithm: MD4 for provider BC").
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class jcifs.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn jcifs.**
