plugins {
    kotlin("jvm")
    id("maven-publish")
    `java-library`
}

group = "ru.sejapoe.routing"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.ktor:ktor-server-core-jvm:2.2.4")
}