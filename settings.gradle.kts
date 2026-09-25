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
        // The AnkiDroid API library is published on JitPack only.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.ankidroid") }
        }
    }
}

rootProject.name = "SubReadAnki"

// Plain Kotlin: the request, the sentence, the clock, the field mapping. Builds with a JDK alone.
include(":core")
include(":app")
