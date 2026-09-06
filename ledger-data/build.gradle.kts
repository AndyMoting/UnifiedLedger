import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
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
        namespace = "com.unifiedledger.data"
        // v13→v14 uses ALTER TABLE DROP COLUMN (SQLite >= 3.35.0); Android
        // system SQLite satisfies it only from API 34 (Android 14).
        minSdk = 34
        compileSdk = 37

        // P5-04.5-FOUND-001 T-A: AGP 9's KMP library plugin disables Android host unit tests by
        // default; opting in creates the androidHostTest source set (this plugin renamed the
        // legacy androidUnitTest source set) so the corruption override stays module-visible.
        // No extra settings: the T-A test calls no android.jar methods (returnDefaultValues
        // stays false) and uses no Android resources.
        withHostTest {
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ledger-application"))
            implementation("app.cash.sqldelight:runtime:2.3.2")
        }

        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.3.2")
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
}

sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("com.unifiedledger.data.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.3.2")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
