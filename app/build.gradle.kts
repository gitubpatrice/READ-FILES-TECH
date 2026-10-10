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
// -------------------------------------------------------------------------------------------------
val replaceFlutter: Boolean = providers.gradleProperty("rft.replaceFlutter").orNull == "true"

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
// release build falls back to the debug key: that is how the CI exercises R8 without the real key.
// -------------------------------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

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
            if (keystoreProps.isNotEmpty()) {
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
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
}
