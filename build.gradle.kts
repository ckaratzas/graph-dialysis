plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "graph-research"
version = "1.0"

repositories {
    mavenCentral()
}

// The standalone benchmark CLI (BENCHMARK_SPEC.md) -- `./gradlew run --args="--family=... ..."`,
// or `./gradlew installDist` then `build/install/graph-dialysis/bin/graph-dialysis <args>` for
// running unattended on a benchmark VM without going through Gradle each time.
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

tasks.processResources { dependsOn(buildNative, buildNativeWl1, buildNativeTraces, buildNativeCadical) }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
    // FullCampaignSatPiTest sweeps an entire graph family (unbounded by design -- see
    // BENCHMARK_SPEC.md), meant to be run deliberately (via the standalone BenchmarkRunner CLI or
    // explicitly by name), not as part of the default fast test run.
    filter {
        excludeTestsMatching("dialysis.configcomparison.FullCampaignSatPiTest")
    }
}
