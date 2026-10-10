plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.4"
}

group = "dev.tip"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Command line tools for local testing, such as the fake device and the AI eval. Kept out of the app jar.
// They see the app's classes so the AI eval runs the production classifier, not a copy of it.
val tools: SourceSet = sourceSets.create("tools") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations["toolsImplementation"].extendsFrom(configurations.implementation.get())
configurations["toolsRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.paypal.sdk:paypal-server-sdk:2.4.0")
    implementation("com.anthropic:anthropic-java:2.71.0")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("org.assertj:assertj-core")
    testImplementation(tools.output)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        palantirJavaFormat("2.102.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Local runs read .env (KEY=value lines) as environment variables, the way the app is configured in production.
fun dotEnv(): Map<String, String> {
    val envFile = file(".env")
    if (!envFile.exists()) {
        return emptyMap()
    }
    return envFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        .filterValues { it.isNotEmpty() }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment(dotEnv())
}

// Gradle reads a tool's output as UTF-8, but a JVM whose output is a pipe encodes it in the platform
// charset (Cp1252 on Windows), so a non ASCII character arrived as U+FFFD. Make the tools write UTF-8.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.register<JavaExec>("fakeDevice") {
    group = "application"
    description = "Runs a fake panda on the device channel. Pass --args=\"--url=ws://localhost:8080 --device=<uuid>\"."
    classpath = tools.runtimeClasspath
    mainClass = "dev.tip.tools.FakeDevice"
    environment(dotEnv())
}

// Calls the real Claude API, so it is never part of build, check or CI. Run it by hand.
tasks.register<JavaExec>("aiEval") {
    group = "application"
    description = "Runs the labelled messages in src/tools/resources/ai-eval-cases.jsonl through the real model."
    classpath = tools.runtimeClasspath
    mainClass = "dev.tip.tools.AiEval"
    environment(dotEnv())
}
