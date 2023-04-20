plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-core-jvm:2.2.4")
    implementation("com.google.devtools.ksp:symbol-processing-api:1.8.20-1.0.11")
    implementation("com.squareup:kotlinpoet:1.13.0")
    implementation("com.squareup:kotlinpoet-ksp:1.13.0")
    implementation(project(":core"))
}
