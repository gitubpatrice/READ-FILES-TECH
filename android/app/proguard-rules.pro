# Flutter / Dart
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.plugin.editing.** { *; }
-dontwarn io.flutter.embedding.**
-dontwarn io.flutter.**

# Plugins natifs utilisés
-keep class com.it_nomads.fluttersecurestorage.** { *; }
-keep class com.mr.flutter.plugin.filepicker.** { *; }
-keep class dev.fluttercommunity.plus.share.** { *; }

# Syncfusion (PDF parsing utilise reflection)
-keep class com.syncfusion.** { *; }
-dontwarn com.syncfusion.**

# WebView
-keep class androidx.webkit.** { *; }
-keep class android.webkit.** { *; }

# Conserver les annotations utilisées par certains plugins via reflection
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
