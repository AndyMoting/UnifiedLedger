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

        // P4-03 (D-099) / P7-04.A (D-146): Apache POI is JVM-only and must never move to
        // commonMain: a future Android target must not inherit the POI classpath. `poi`
        // stays in jvmMain for the CCB XLS parser (HSSF, spec section 3.1.3); since P7-04.A
        // the WeChat XLSX production read is the bounded minimal XLSX reader
        // (java.util.zip + javax.xml.parsers), so `poi-ooxml` (XSSF) is jvmTest only — the
        // WechatBillParserJvmTest fixture generator is its sole consumer.
        val jvmMain by getting {
            dependencies {
                implementation("org.apache.poi:poi:5.5.1")
            }
        }

        jvmTest {
            dependencies {
                implementation("org.apache.poi:poi-ooxml:5.5.1")
            }
        }
    }
}
