# Giữ số dòng để đọc được stack trace bản release (dùng kèm build/outputs/mapping/release/mapping.txt)
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# ADB protocol classes dùng reflection ở vài chỗ nhạy cảm với tên trường — giữ nguyên để an toàn.
-keep class com.min.adb.adb.** { *; }

# Pairing: JNI registers natives by class name; BouncyCastle/Conscrypt are loaded reflectively by providers.
-keep class com.min.adb.adb.PairingContext { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-keep class org.conscrypt.** { *; }
