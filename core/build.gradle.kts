plugins {
    kotlin("jvm")
}

// The room's rules and the web server the phones talk to, in plain Kotlin, so both can be tested on the JVM
// with real sockets.
kotlin {
    // Java 17 bytecode like the Android app, built with whichever JDK runs Gradle.
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew testDebugUnitTest` is how the tests are run (see AGENTS.md), as in the other games.
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}
