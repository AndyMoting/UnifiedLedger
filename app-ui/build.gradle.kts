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
            // D-134 D2-D3: glass effect layer, pinned exactly per the D2 adoption gate
            // (spec section 3/E-1). The flag defaults off with zero call sites in this
            // batch, so this artifact is compiled against but never executed.
            implementation("io.github.kyant0:backdrop:2.0.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
