// CI-only test parallelism.
//
// docs/CONTRIBUTING.md "本机 Gradle 资源限制" requires serial Gradle execution on the
// 16 GB development host, so this init script is never applied by local commands:
// only .github/workflows/ci.yml passes it through --init-script.
//
// CI_TEST_MAX_PARALLEL_FORKS selects the number of forked test processes per Test
// task. The default of 4 matches the 4 vCPU hosted runner, measured on 2026-09-30 at
// a 2.10x wall-time speedup for :ledger-data:jvmTest (forks=1 -> forks=4).
allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        maxParallelForks = (System.getenv("CI_TEST_MAX_PARALLEL_FORKS") ?: "4").toInt()
    }
}
