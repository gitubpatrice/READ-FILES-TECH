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

# ML Kit : on n'utilise QUE le recognizer latin → ignorer les options des autres scripts
# (Chinese, Japanese, Korean, Devanagari) que R8 ne trouve pas car non incluses.
-dontwarn com.google.mlkit.vision.text.chinese.**
-dontwarn com.google.mlkit.vision.text.japanese.**
-dontwarn com.google.mlkit.vision.text.korean.**
-dontwarn com.google.mlkit.vision.text.devanagari.**
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**

# Quick Tiles : preserve les TileService classes (référencées par le manifest seulement)
-keep class com.readfilestech.read_files_tech.ScannerTileService { *; }
-keep class com.readfilestech.read_files_tech.OcrTileService { *; }
-keep class com.readfilestech.read_files_tech.VaultTileService { *; }

# local_auth (BiometricPrompt via FragmentActivity)
-keep class androidx.biometric.** { *; }
-keep class androidx.fragment.app.** { *; }
