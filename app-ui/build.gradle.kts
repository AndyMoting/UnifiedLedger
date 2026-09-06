import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
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

kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    android {
        namespace = "com.unifiedledger.ui"
        minSdk = 34
        compileSdk = 37
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ledger-application"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // D-131 R2: fixed Asia/Shanghai TimeZone/LocalDateTime conversion for the
            // occurred-at picker and the confirmation display (spec 3.1; the only new
            // direct dependency of this batch).
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
