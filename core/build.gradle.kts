plugins {
    kotlin("jvm")
    id("maven-publish")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.ktor:ktor-server-core-jvm:2.2.4")
}