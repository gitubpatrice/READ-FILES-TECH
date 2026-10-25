// Root build.gradle.kts — Read Files Tech (Kotlin port)
// No version numbers here: all versions live in gradle/libs.versions.toml.

// The Kotlin compiler version, as a real cap — same mechanism as App Manager Tech, where it was
// measured. With AGP 9 the Kotlin Gradle Plugin is a dependency of AGP itself and Gradle keeps the
// HIGHEST version requested, so a future AGP could raise the compiler past what CodeQL accepts
// without any file of this repository changing. `strictly` holds it at the catalog's version: Gradle
// downgrades every higher request to it, and an AGP that truly needs a newer KGP breaks loudly.
buildscript {
    dependencies {
        constraints {
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") {
                version { strictly(libs.versions.kotlin.get()) }
                because("CodeQL 2.27 refuses Kotlin 2.4.20+; the compiler must be the catalog's version")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose)      apply false
    alias(libs.plugins.hilt)                apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.detekt)
}

// detekt, blocking, no baseline: config/detekt/detekt.yml.
allprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    detekt {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        // Every source set under src/: a fixed list of directories would leave the next one
        // (src/debug, src/main/kotlin) outside any analysis — found on App Manager Tech.
        source.setFrom(files("src"))
        autoCorrect = false
        parallel = true
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }
}
