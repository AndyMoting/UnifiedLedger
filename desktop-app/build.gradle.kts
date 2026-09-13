import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
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

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":app-ui"))
            implementation(project(":ledger-application"))
            implementation(project(":ledger-data"))
            // F-2 (IMP-1): the desktop SQLite driver is declared in this module's jvmMain;
            // the ledger-data build script stays unchanged.
            implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            // P7-03.C/D: the ledger-view composition-root tests construct the frozen
            // kotlinx.datetime.YearMonth month cursor types through the facade surface.
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.unifiedledger.desktop.MainKt"
    }
}
