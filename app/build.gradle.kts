plugins {
    alias(libs.plugins.android.application)
}

// Release signing comes from the environment, so the key never lives in the repository.
val keystorePath = providers.environmentVariable("SUBREAD_KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("SUBREAD_KEYSTORE_PASSWORD").orNull
val signingReady = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() &&
    file(keystorePath).isFile

android {
    namespace = "space.subread.anki"
    compileSdk = 36

    // No list of dependencies, encrypted for Google alone, inside the app: F-Droid
    // does not accept a part of the app that nobody else can read.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "space.subread.anki"
        minSdk = 26
        targetSdk = 36
        // Plain numbers, in this file: F-Droid reads them from here to find a new release.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // A link to the Ko-fi page. Google Play is not given it (-PplayStore=true), the same
        // as in the other SubRead apps.
        buildConfigField("boolean", "DONATE_LINK", (providers.gradleProperty("playStore").orNull != "true").toString())
    }

    if (signingReady) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "subread"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }

    lint {
        // The AnkiDroid API library ships the lint rules of the AnkiDroid app itself. This one
        // wants the clock of the Anki collection; this app has no collection.
        disable += "DirectSystemCurrentTimeMillisUsage"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.ankidroid.api)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
