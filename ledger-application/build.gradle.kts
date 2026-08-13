import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
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
