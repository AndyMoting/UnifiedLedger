import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
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
        compileSdk = 36
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ledger-application"))
            implementation("app.cash.sqldelight:runtime:2.3.2")
        }

        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.3.2")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
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
