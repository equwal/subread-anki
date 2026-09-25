// Plugin versions live in gradle/libs.versions.toml; each module applies its own.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
}
