pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android, the OCR's engine (docs D-032), is published on JitPack only. JitPack builds
        // what it serves from a Git tag on demand: it serves that ONE group and nothing else may resolve
        // there, and its bytes are pinned by SHA-256 in gradle/verification-metadata.xml — an artefact
        // rebuilt or replaced fails the build instead of entering the APK.
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io") { name = "JitPack" }
            }
            filter {
                includeGroup("cz.adaptech.tesseract4android")
            }
        }
    }
}

rootProject.name = "Read Files Tech"
include(":app")
