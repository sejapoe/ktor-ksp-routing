plugins {
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    `java-library`
}

group = "ru.sejapoe.routing"
version = "0.0.1"

repositories {
    mavenCentral()
}


publishing {
    publications {
        create<MavenPublication>("maven") {
            group = "com.github.sejapoe"
            artifactId = "ktor-ksp-routing"
            version = "1.0.5"

            from(components["java"])
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-core-jvm:2.2.4")
    implementation("com.google.devtools.ksp:symbol-processing-api:1.8.20-1.0.11")
    implementation("com.squareup:kotlinpoet:1.13.0")
    implementation("com.squareup:kotlinpoet-ksp:1.13.0")
    implementation(project(":core"))
}
