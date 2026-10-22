// CI-only test parallelism and test-cache policy.
//
// docs/CONTRIBUTING.md "本机 Gradle 资源限制" requires serial Gradle execution on the
// 16 GB development host, so this init script is never applied by local commands:
// only .github/workflows/ci.yml passes it through --init-script.
//
// CI_TEST_MAX_PARALLEL_FORKS selects the number of forked test processes per Test
// task. The default of 4 matches the 4 vCPU hosted runner, measured on 2026-09-30 at
// a 2.10x wall-time speedup for :ledger-data:jvmTest (forks=1 -> forks=4).
//
// Test tasks are excluded from the Gradle build cache on purpose. The build cache is
// enabled in CI for compilation, but a cached Test task reports green without
// executing anything: a re-run of the same commit logged
// "> Task :ledger-data:jvmTest FROM-CACHE" and finished an entire shard in 42 s. The
// required check must mean "these tests ran and passed now", so only compile and
// analysis tasks may be served from the cache.
allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        maxParallelForks = (System.getenv("CI_TEST_MAX_PARALLEL_FORKS") ?: "4").toInt()
        outputs.cacheIf { false }
    }
}
