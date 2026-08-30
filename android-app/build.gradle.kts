plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    filter {
        exclude("**/build/**")
        // Patterns apply relative to each source-directory root, so a literal
        // "**/build/**" does not reach generated sources (their root is already
        // inside build/). Match the absolute path to exclude build/generated.
        exclude {
            it.file.absolutePath
                .replace('\\', '/')
                .contains("/build/")
        }
    }
}

android {
    namespace = "com.unifiedledger.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unifiedledger.android"
        minSdk = 34
        targetSdk = 36
    }

    // AGP 9 built-in Kotlin (no org.jetbrains.kotlin.android): the Kotlin jvmTarget
    // defaults to android.compileOptions.targetCompatibility; align both with JDK 21.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":app-ui"))
    implementation(project(":ledger-application"))
    implementation(project(":ledger-data"))
    // F-2 analog (IMP-11): the composition root consumes the Android system SQLite driver
    // itself; ledger-data declares it as implementation and does not expose it to consumers.
    implementation("app.cash.sqldelight:android-driver:2.3.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(compose.runtime)
}
