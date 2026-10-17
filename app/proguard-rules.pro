# Giữ số dòng để đọc được stack trace bản release (dùng kèm build/outputs/mapping/release/mapping.txt)
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# ADB protocol classes dùng reflection ở vài chỗ nhạy cảm với tên trường — giữ nguyên để an toàn.
-keep class com.min.adb.adb.** { *; }
