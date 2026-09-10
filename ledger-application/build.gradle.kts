import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
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
        commonMain.dependencies {
            api(project(":ledger-domain"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            // D-138: the lenient occurred-at parser converts wall-clock input through the
            // fixed Asia/Shanghai zone (spec 2.1). The coordinate is already gated by D-131
            // section 3.1 and present in the app-ui resolution graph; this declares it for
            // the application layer instead of adding a new dependency.
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // P4-03 (D-099, spec section 6): Apache POI is JVM-only. It must never move to
        // commonMain: a future Android target must not inherit the POI classpath.
        val jvmMain by getting {
            dependencies {
                implementation("org.apache.poi:poi-ooxml:5.5.1")
            }
        }
    }
}
