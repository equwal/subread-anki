import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Plain JVM on purpose: no Android types in here, so the sentence cut and the furigana can be tested on a laptop.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {

    testImplementation(libs.junit)
    // Property tests. It shrinks a failing input to the smallest one and prints the seed.
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
