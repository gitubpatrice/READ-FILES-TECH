import com.android.build.api.variant.FilterConfiguration
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // No `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// -------------------------------------------------------------------------------------------------
// Version — `version.properties` is the single source, read without fallback (see its header).
// -------------------------------------------------------------------------------------------------
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    require(f.exists()) { "version.properties not found at the root of the project." }
    f.inputStream().use { load(it) }
}
val appVersionCode: Int = requireNotNull(versionProps.getProperty("versionCode")?.trim()?.toIntOrNull()) {
    "versionCode missing or not an integer in version.properties."
}
val appVersionName: String = requireNotNull(versionProps.getProperty("versionName")?.trim()?.ifBlank { null }) {
    "versionName missing or blank in version.properties."
}

// -------------------------------------------------------------------------------------------------
// Taking the place of the installed app — OPT-IN, never by default (docs D-008)
//
// Under the real applicationId, a plain install of this build would overwrite the Flutter Read Files
// Tech: same package, same sandbox, same vault. Until the port is proven safe it must not be able to.
// By default the package therefore carries the `.next` suffix: a different sandbox, so this build
// CANNOT see the user's vault, preferences or trash. The isolation is a property of the system, not a
// discipline to keep. The Flutter manifest has no <activity-alias>, so the suffix breaks no alias
// (checked 2026-10-10 against the 2.15.3 manifest).
//
// The switch (phase 9, after parity is checked on a device):
//     ./gradlew assembleRelease -Prft.replaceFlutter=true
// ⚠️ Irreversible for the user: that build installs OVER Read Files Tech and opens its real data.
//
// Read from the COMMAND LINE only (`-P`), never from a gradle.properties file: `providers.gradleProperty`
// also reads ~/.gradle/gradle.properties and ORG_GRADLE_PROJECT_* variables, so a line forgotten
// there would turn every build of this machine into one that replaces the app (security review of the
// skeleton, 2026-10-10, B3).
// -------------------------------------------------------------------------------------------------
val replaceFlutter: Boolean = gradle.startParameter.projectProperties["rft.replaceFlutter"] == "true"

val baseApplicationId = "com.readfilestech.read_files_tech"
val effectiveApplicationId = if (replaceFlutter) baseApplicationId else "$baseApplicationId.next"

if (replaceFlutter) {
    logger.lifecycle(
        """
        ⚠️  BUILD TAKING THE PLACE OF THE INSTALLED APP — applicationId = $baseApplicationId
            This APK installs OVER the Flutter Read Files Tech and opens the user's real data.
        """.trimIndent(),
    )
}

// -------------------------------------------------------------------------------------------------
// Signing — keystore.properties at the root, never committed (.gitignore, docs D-010). Absent, the
// ISOLATED release build falls back to the debug key: that is how the CI exercises R8 without the
// real key. A release that takes the Flutter app's place never does (see the guard below): signed with
// the public debug key, it could not update anyone, and whoever installed it would be stuck with it.
// -------------------------------------------------------------------------------------------------
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
if (keystoreFile.exists()) {
    // Every key named: a partial file would otherwise fail late, on a null path or password.
    signingKeys.forEach { key ->
        require(!keystoreProps.getProperty(key).isNullOrBlank()) { "keystore.properties has no $key." }
    }
}
val hasReleaseKey = keystoreFile.exists()

android {
    // AGP slips an ENCRYPTED list of our dependencies into the APK signing block, for the Play
    // console. Read Files Tech is not on Play: an unreadable blob has no place in a binary whose
    // whole argument is to be verifiable (measured on Agenda Tech: 9,085 bytes).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // The `namespace` (package of the code and of the R class) is the published app's package and
    // NEVER follows the applicationId: the manifest's relative component names resolve on it, and
    // the launcher and the quick-settings panel remember those full names (docs D-012).
    namespace = "com.readfilestech.read_files_tech"
    // compileSdk 37 as in App Manager Tech: the AndroidX releases built for AGP 9 require it.
    compileSdk = 37

    defaultConfig {
        // ⚠️ NOT the real applicationId by default — see the block above.
        applicationId = effectiveApplicationId
        // docs D-002: Android 7.x (API 24-25) keeps the 2.15.x line.
        minSdk = 26
        // The value the 2.15.3 ships with (read by `dumpsys package` on the S24): lowering it would
        // change the app's behaviour on phones where it already runs.
        targetSdk = 36

        versionCode = appVersionCode
        versionName = appVersionName

        // Two apps side by side in the launcher must be told apart at a glance. A manifest
        // placeholder and NOT a `resValue`: `app_name` is localised, and a generated string in
        // values/ loses against values-fr/ on a French phone — the rename would silently do nothing
        // (measured on SMS Tech, 2026-09-07).
        manifestPlaceholders["appLabel"] = if (replaceFlutter) "Read Files Tech" else "Read Files Tech (Kotlin)"
        // Lets a test state the isolation rule (applicationId = base + ".next" unless replacing) instead
        // of only observing whichever variant it runs on.
        buildConfigField("boolean", "REPLACES_FLUTTER_APP", replaceFlutter.toString())
        buildConfigField("String", "FLUTTER_APPLICATION_ID", "\"$baseApplicationId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // No applicationIdSuffix on debug: the phase 2 test installs a DEBUG port over a debug
        // Flutter build signed by the same key, which a `.debug` suffix would make impossible. The
        // `.next` suffix above is what keeps a debug build away from the real app.
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // The phase 2 exit criterion: the release as R8 makes it, plus the check entry point of src/check
        // (picked up by the build type's own source set convention, `src/<type>/`). Signed with the DEBUG
        // key, so that it installs over a seeded debug Flutter build; never published (no release manifest
        // may carry the provider: tools/check-manifest-shape.py, run by the CI on this very variant).
        // Under the real package it needs -Prft.phase2Check=true as well (guard below), and it never
        // passes for a release: "-check" version, its own label.
        create("check") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-check"
            manifestPlaceholders["appLabel"] = "Read Files Tech (check)"
        }
    }

    // The debug build carries the check too: faster to iterate on than R8, and debuggable (run-as).
    sourceSets {
        getByName("debug") {
            kotlin.srcDir("src/check/java")
            manifest.srcFile("src/check/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // Java resources of POI's dependencies that reading an .xls never loads (docs D-006: 208 877 bytes of
    // APK): the list, the classes that would load each and the proof are in java-resources-excludes.txt.
    packaging {
        resources {
            excludes += file("java-resources-excludes.txt").readLines()
                .map { it.substringBefore("<-").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            // bcprov, bcpkix and bcutil 1.86 each ship the SAME `META-INF/LICENSE.md`: one is kept (prototype).
            pickFirsts += "META-INF/LICENSE.md"
        }
        // The universal APK holds the ABIs the splits make and no other: Tesseract4Android also ships 32-bit
        // x86, which no split covers and the 2.15.3 never shipped (7.8 MB stored in the universal APK).
        jniLibs {
            excludes += "lib/x86/**"
        }
    }

    // The predefined CJK CMaps of PdfBox-Android (docs D-005: -1.26 MB of APK). Kept: Identity-H/V, which
    // the EMBEDDED CID fonts of Edge, Word and LibreOffice use. Their price, measured by the prototype: the
    // text of a CJK PDF whose font is not embedded (`UniJIS-UCS2-H`) is lost to extraction; its rendering is
    // PdfRenderer's and stays whole. Only `prefix*` or exact names: what ignoreAssetsPatterns understands.
    // FILES only, `<file>`: the patterns ignore case, and the prototype's bare `Uni*` also dropped FontBox's
    // `unicode/` folder (its Scripts.txt, 167 KB) — seen in the APK of this branch.
    androidResources {
        ignoreAssetsPatterns += listOf(
            "83pv-*", "90ms-*", "90msp-*", "90pv-*", "Add-*", "Ext-*", "Adobe-*", "B5pc-*", "CNS-*", "ETen*",
            "EUC-*", "GB-*", "GBK-*", "GBK2K-*", "GBKp-*", "GBpc-*", "HKscs-*", "KSC-*", "KSCms-*", "KSCpc-*",
            "Uni*", "H", "V",
        ).map { "<file>$it" }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.useJUnitPlatform() }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// -------------------------------------------------------------------------------------------------
// versionCode per APK = base * 10 + ABI rank (docs D-011) — the scheme of Notes Tech 3.0.0.
// `version.properties` stays the single source: the rank is applied to the OUTPUTS only.
// -------------------------------------------------------------------------------------------------
val abiRanks = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
// A rank must be a single digit (rank 10 of base N would equal rank 0 of base N + 1), and distinct.
require(abiRanks.values.all { it in 1..9 }) { "ABI ranks must be single digits: $abiRanks" }
require(abiRanks.values.toSet().size == abiRanks.size) { "ABI ranks must be distinct: $abiRanks" }
val universalRank = 0
require(universalRank !in abiRanks.values) { "The universal rank must differ from every ABI rank" }

// ⚠️ A FLOOR, not a proof: the highest code published by the Flutter line when this was written
// (2.15.3, x86_64 split, docs D-016). Even the universal APK of the port (rank 0, the lowest) must
// sit above it, or a user who installed that split gets INSTALL_FAILED_VERSION_DOWNGRADE.
val highestFlutterCodePublished = 25503
require(appVersionCode * 10 + universalRank > highestFlutterCodePublished) {
    "versionCode base $appVersionCode gives ${appVersionCode * 10}, not above $highestFlutterCodePublished " +
        "(Read Files Tech 2.15.3, x86_64): the update would be refused on the phones that installed it."
}

// A release that takes the Flutter app's place must be signed by the real key: refused otherwise,
// before anything is packaged (security review of the skeleton, 2026-10-10, B1). Debug builds keep the
// debug key, which the phase 2 test needs (debug port over a debug Flutter build).
// Every task that writes a signed release artefact, read from `:app:tasks --all` under AGP 9.4.1: an AAB
// (`bundleRelease`) goes through packageReleaseBundle and signReleaseBundle, never through packageRelease.
val releasePackagingTasks = setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "packageReleaseUniversalApk")

// The `check` type is a release signed with the debug key: under the real package it escaped the guard
// above (security review of 2026-10-11, S1). It is packaged there only on an explicit, second request.
val phase2Check: Boolean = gradle.startParameter.projectProperties["rft.phase2Check"] == "true"
val checkPackagingTasks = releasePackagingTasks.map { it.replace("Release", "Check") }.toSet()
gradle.taskGraph.whenReady {
    val packagesARelease = allTasks.any { it.project == project && it.name in releasePackagingTasks }
    if (replaceFlutter && packagesARelease && !hasReleaseKey) {
        throw GradleException(
            "A release build with -Prft.replaceFlutter=true needs keystore.properties: signed with the debug " +
                "key, it would install over nobody's Read Files Tech and could never be updated.",
        )
    }
    val packagesACheck = allTasks.any { it.project == project && it.name in checkPackagingTasks }
    if (replaceFlutter && packagesACheck && !phase2Check) {
        throw GradleException(
            "A `check` build under the real package (the phase 2 criterion only, over a seeded debug Flutter " +
                "build) needs -Prft.phase2Check=true as well: it is a release signed with the debug key.",
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            // An output WITHOUT an ABI filter is the universal APK. An ABI with no rank must FAIL the
            // configuration, never ship an APK whose code nobody chose.
            val rank = if (abi == null) universalRank else abiRanks[abi] ?: error("ABI output without a rank: $abi")
            output.versionCode.set(appVersionCode * 10 + rank)
        }
    }
}

// ONE BouncyCastle in the APK (docs D-005). PdfBox-Android 2.0.27.0 pulls the `jdk15to18` family in 1.72
// (10 OSV advisories), which duplicates 3 504 classes of `bcprov-jdk18on` (the vault's Argon2):
// `checkReleaseDuplicateClasses` fails. The whole family is replaced by `jdk18on` at the catalog's version
// rather than excluded: excluded, PdfBox cannot even build its certificate handler — the one that refuses
// a `/Adobe.PubSec` file in words (PdfUnlock) — `NoClassDefFoundError: org/bouncycastle/cms/CMSException`,
// measured on the JVM. A password never reaches BC (javax.crypto of the platform, read in the bytecode).
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.bouncycastle:bcprov-jdk15to18")).using(module(libs.bcprov.get().toString()))
            .because("duplicate of bcprov-jdk18on; 1.72 has 10 OSV advisories")
        substitute(module("org.bouncycastle:bcpkix-jdk15to18")).using(module(libs.bcpkix.get().toString()))
            .because("the same family as bcprov-jdk18on")
        substitute(module("org.bouncycastle:bcutil-jdk15to18")).using(module(libs.bcutil.get().toString()))
            .because("the same family as bcprov-jdk18on")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // The EXIF orientation of a photo below API 28 (docs: libs.versions.toml).
    implementation(libs.androidx.exifinterface)
    // The HTML viewer's WebViewAssetLoader: a page and its folder served by the app, never by file:// (D20).
    implementation(libs.androidx.webkit)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // R8 keeps only the icons the screens use.
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.coroutines.android)

    // Argon2id and PBKDF2 of the vault, byte-compatible with the pointycastle the Flutter app used.
    implementation(libs.bcprov)

    // The .xls, by HSSF alone (docs D-006): read from a stream, its awkward formats never given to CellFormat.
    implementation(libs.poi)

    // The Markdown viewer's parser, to a tree laid out by the app (never HTML, never an image fetched).
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    // The patterns the user types (content search, bulk rename): matched in linear time, never backtracking.
    implementation(libs.re2j)

    // The PDF viewer's password and outline (docs D-005), in the `:pdf` process only (D-029).
    implementation(libs.pdfbox.android)

    // The OCR (docs D-032): Tesseract, its trained data in the assets — offline, nothing of Google.
    implementation(libs.tesseract4android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
    testImplementation(libs.xmlpull)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
}
