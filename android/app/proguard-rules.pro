# Flutter / Dart
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.plugin.editing.** { *; }
-dontwarn io.flutter.embedding.**
-dontwarn io.flutter.**

# Plugins natifs utilisés
-keep class com.mr.flutter.plugin.filepicker.** { *; }
-keep class dev.fluttercommunity.plus.share.** { *; }

# Syncfusion (PDF parsing utilise reflection sur PdfBitmap/PdfDocument).
# P1.4 v2.13.0 — Narrow : avant `com.syncfusion.**` (200+ classes), maintenant
# seul le sous-package PDF est `-keep`. Gain estimé ~3-5 Mo APK final.
-keep class com.syncfusion.flutter.pdf.** { *; }
-keep class com.syncfusion.flutter.pdfviewer.** { *; }
-dontwarn com.syncfusion.**

# WebView
-keep class androidx.webkit.** { *; }
-keep class android.webkit.** { *; }

# Conserver les annotations utilisées par certains plugins via reflection
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# ML Kit : on n'utilise QUE le recognizer latin → ignorer les options des autres scripts
# (Chinese, Japanese, Korean, Devanagari) que R8 ne trouve pas car non incluses.
# P1.4 v2.13.0 — Narrow : `com.google.mlkit.**` était trop large (couvre
# barcode / face / pose / digital-ink non utilisés). Désormais on garde
# seulement vision.text.* (text recognition latin uniquement).
-dontwarn com.google.mlkit.vision.text.chinese.**
-dontwarn com.google.mlkit.vision.text.japanese.**
-dontwarn com.google.mlkit.vision.text.korean.**
-dontwarn com.google.mlkit.vision.text.devanagari.**
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Quick Tiles : preserve les TileService classes (référencées par le manifest seulement)
-keep class com.readfilestech.read_files_tech.ScannerTileService { *; }
-keep class com.readfilestech.read_files_tech.OcrTileService { *; }
-keep class com.readfilestech.read_files_tech.VaultTileService { *; }
