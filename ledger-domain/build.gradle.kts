import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
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
        namespace = "com.unifiedledger.domain"
        minSdk = 34
        compileSdk = 37

        // D-238 A02 gap (iii) / spec 2.3: the shared commonTest suite must also run on the
        // Android host-test compilation face (both ends run the same commonTest property
        // test). AGP 9's KMP library plugin disables Android host unit tests by default;
        // opting in creates the androidHostTest source set (this plugin renamed the legacy
        // androidUnitTest source set), so commonTest compiles and runs there through
        // :ledger-domain:testAndroidHostTest in CI. The module keeps no platform source
        // sets: domain code stays common-only, with no androidMain sources or dependencies.
        withHostTest {
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
