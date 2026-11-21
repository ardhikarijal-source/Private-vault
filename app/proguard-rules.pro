# Jetpack Security (Tink) memakai refleksi pada protobuf-lite; tanpa ini build release bisa crash saat enkripsi.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
