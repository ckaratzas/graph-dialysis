plugins {
    kotlin("jvm") version "2.2.20"
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "graph-research"
version = "1.0"

repositories {
    mavenCentral()
}

// The standalone benchmark CLI (BENCHMARK_SPEC.md) -- `./gradlew run --args="--family=... ..."`,
// or `./gradlew shadowJar` then `java -jar build/libs/graph-dialysis-1.0-all.jar <args>` for a
// single self-contained jar (dependencies + vendored native libraries all bundled in) that a
// benchmark VM only needs a JDK to run -- no repo, no Gradle, no rebuild required on the VM.
application {
    mainClass.set("dialysis.benchmark.BenchmarkRunnerKt")
}

dependencies {
    // Used by dialysis.util.GraphIO for DIMACS parsing.
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    testImplementation(kotlin("test"))
}

// Build the native JNI library and place it on the resource path so NativePhase1 can load it.
val buildNative = tasks.register<Exec>("buildNative") {
    commandLine("bash", "src/main/cpp/build.sh")
    inputs.file("src/main/cpp/colored_ahu.cpp")
    outputs.file("src/main/resources/libcoloredahu.so")
}

// Build the 1-WL JNI library: a hand-rolled Paige-Tarjan style partition
// refinement, exposed via src/main/cpp/paige_tarjan_wl1.cpp. No nauty dependency.
val buildNativeWl1 = tasks.register<Exec>("buildNativeWl1") {
    commandLine("bash", "src/main/cpp/build_wl1.sh")
    inputs.file("src/main/cpp/paige_tarjan_wl1.cpp")
    outputs.file("src/main/resources/libwl1jni.so")
}

// Build the exact canonical labeler: the Traces algorithm (Piperno), exposed via
// src/main/cpp/nauty_traces.cpp — same vendored nauty/Traces library as buildNativeWl1.
val buildNativeTraces = tasks.register<Exec>("buildNativeTraces") {
    commandLine("bash", "src/main/cpp/build_traces.sh")
    inputs.dir("src/main/cpp/nauty")
    inputs.file("src/main/cpp/nauty_traces.cpp")
    outputs.file("src/main/resources/libtracesjni.so")
}

// Build CaDiCaL (Biere et al., vendored under src/main/cpp/cadical/) plus the thin JNI shim
// exposed via src/main/cpp/cadical_jni.cpp. Runs CaDiCaL's own ./configure --shared && make
// rather than hand-replicating its compiler flags/platform feature-detection (same rationale as
// the vendored nauty build, just via its own build system instead of a flat gcc loop since
// CaDiCaL ships one).
val buildNativeCadical = tasks.register<Exec>("buildNativeCadical") {
    commandLine("bash", "src/main/cpp/build_cadical.sh")
    inputs.dir("src/main/cpp/cadical/src")
    inputs.dir("src/main/cpp/cadical/contrib")
    inputs.dir("src/main/cpp/cadical/scripts")
    inputs.file("src/main/cpp/cadical/configure")
    inputs.file("src/main/cpp/cadical/makefile.in")
    inputs.file("src/main/cpp/cadical_jni.cpp")
    outputs.file("src/main/resources/libcadicaljni.so")
}

// Links the JNI shim (src/main/cpp/cryptominisat_jni.cpp) against the prebuilt CryptoMiniSat +
// cadiback + cadical stack under src/main/cpp/cms-stack/ -- see build_cryptominisat.sh's own doc
// for why this is a real "umbrella" (CMS's own inprocessing calls into an embedded CaDiCaL via
// cadiback), why that requires THREE separately vendored/built repos pinned to specific
// commits/tags, and why this task does NOT build that stack from scratch (each piece's own build
// is its own multi-step, occasionally network-dependent process not worth re-deriving on every
// `processResources` run -- see the script's own error message if a piece is missing).
val buildNativeCryptoMiniSat = tasks.register<Exec>("buildNativeCryptoMiniSat") {
    commandLine("bash", "src/main/cpp/build_cryptominisat.sh")
    inputs.dir("src/main/cpp/cms-stack/cryptominisat/src")
    inputs.file("src/main/cpp/cryptominisat_jni.cpp")
    outputs.file("src/main/resources/libcryptominisatjni.so")
}

tasks.processResources { dependsOn(buildNative, buildNativeWl1, buildNativeTraces, buildNativeCadical, buildNativeCryptoMiniSat) }

// cadiback (linked into libcryptominisatjni.so, see build_cryptominisat.sh) calls CaDiCaL's own
// Signal::set() on every backboneSimplify() call -- plain libc signal(), for SIGABRT/SIGINT/
// SIGSEGV/SIGTERM, replacing whatever the JVM itself had installed (HotSpot installs its OWN
// SIGSEGV handler at startup, for the implicit-null-check fast path JIT-compiled code relies on).
// Signal::reset() restores the previous handler afterward, but signal()-based save/restore does
// NOT correctly round-trip a handler HotSpot installed via sigaction() (extra flags/mask info is
// lost) -- confirmed directly: without this preload, a JVM running this test crashed (SIGSEGV,
// corrupted siginfo, deep inside HotSpot/Gradle-internal code, always AFTER cadiback's own call
// had already returned normally) on every single run that called backboneSimplify(). `libjsig.so`
// is the JVM's own answer to exactly this: preloaded, it intercepts native code's signal()/
// sigaction() calls and CHAINS them behind HotSpot's own handler instead of replacing it, so
// HotSpot's implicit exceptions keep working no matter what a natively-loaded library does.
val libjsig = "${System.getProperty("java.home")}/lib/server/libjsig.so"

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "6g"
    environment("LD_PRELOAD", libjsig)
    // FullCampaignSatPiTest sweeps an entire graph family (unbounded by design -- see
    // BENCHMARK_SPEC.md), meant to be run deliberately (via the standalone BenchmarkRunner CLI or
    // explicitly by name), not as part of the default fast test run.
    filter {
        excludeTestsMatching("dialysis.FullCampaignSatPiTest")
    }
}

// `./gradlew run`'s JavaExec had no heap bound at all (JVM default, ~1/4 of physical RAM) --
// discovered the hard way: an OS-level cgroup wrapped around `./gradlew` does NOT contain this
// task's actual worker JVM (Gradle's persistent daemon forks it as ITS OWN child, and that daemon
// process gets reparented by systemd to the user's default app slice regardless of what cgroup
// spawned it, fresh daemon or not -- verified directly via /proc/<pid>/cgroup). An explicit -Xmx
// here, enforced by the JVM itself, is unaffected by that cgroup-escaping behaviour and is the
// actual safety mechanism for an unattended BenchmarkRunner campaign.
tasks.named<JavaExec>("run") {
    maxHeapSize = "4g"
    environment("LD_PRELOAD", libjsig) // see tasks.test's own comment on why
}
