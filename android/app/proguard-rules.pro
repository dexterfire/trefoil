# Release shrinking rules. Libraries with bundled consumer rules (Compose, Glance, WorkManager,
# OkHttp, kotlinx.serialization) need nothing here.

# BouncyCastle: the JCA provider registers its SPI implementations by class name, and sshj
# resolves algorithms through Security providers, so R8 cannot see these references.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# sshj: algorithm factories and key formats are looked up by name; keep the library intact.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# WebView bridge methods are called from JavaScript.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
