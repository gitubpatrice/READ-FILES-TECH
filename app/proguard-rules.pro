# R8 rules for Read Files Tech. Hilt, Compose and AndroidX ship their own consumer rules. Each rule
# added here must say which class it keeps and what broke without it.

# ---- Apache POI 5.5.1 (HSSF, the .xls: docs D-006) ----------------------------------------------
# The EXACT list R8 wrote (build/outputs/mapping/release/missing_rules.txt), no wildcard: each line is a
# class really referenced and absent from Android. No -keep was needed (measured by the prototype:
# reading, formats, dates and RC4 decryption work in the minified APK).
#
# Compile-time annotations of log4j-api (bnd, SpotBugs, JSpecify) and its OSGi loader: never run on Android.
-dontwarn aQute.bnd.annotation.baseline.BaselineIgnore
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn edu.umd.cs.findbugs.annotations.Nullable
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn org.jspecify.annotations.NullMarked
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.osgi.framework.wiring.BundleRevision
# java.awt does not exist on Android. Silencing R8 does NOT make the problem go away: java.awt.Color is
# created by CellFormatPart.<clinit> (formats of 3+ sections), which the viewer never reaches (CellText).
-dontwarn java.awt.Color
-dontwarn java.awt.Dimension
-dontwarn java.awt.Rectangle
-dontwarn java.awt.color.ColorSpace
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.Dimension2D
-dontwarn java.awt.geom.Path2D
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ColorModel
-dontwarn java.awt.image.ComponentColorModel
-dontwarn java.awt.image.DirectColorModel
-dontwarn java.awt.image.IndexColorModel
-dontwarn java.awt.image.PackedColorModel

# ---- PdfBox-Android 2.0.27.0 (the PDF viewer's password and outline: docs D-005) ----------------------
# Its consumer rules keep the SecurityHandlers it builds by reflection. They do not cover its optional
# JPEG 2000 decoder (com.gemalto.jp2, not shipped): R8 fails on the missing classes without this line
# (measured by the prototype, also in negative). Never reached: the viewer renders with PdfRenderer.
-dontwarn com.gemalto.jp2.**

# ---- Tesseract4Android 4.9.0 (the OCR: docs D-032) -----------------------------------------------------
# No rule of ours. Its native code finds the Java side by name: the `native` methods, kept with their
# classes by proguard-android-optimize.txt, and the one method it calls back, TessBaseAPI.onProgressValues,
# annotated @Keep (androidx.annotation's own rules keep it). The OCR's oracle runs on the `check` build,
# the release as R8 makes it: it reads its pictures there (tools/oracles/ocr_oracle.py).
