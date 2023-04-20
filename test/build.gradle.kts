plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp") version "1.8.20-1.0.11"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(project(":core"))
    ksp(project(":processor"))
    implementation("io.insert-koin:koin-ktor:3.3.1")
    implementation("io.ktor:ktor-server-core-jvm:2.2.4")
    implementation("io.ktor:ktor-server-netty:2.2.4")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.ktor:ktor-server-test-host:2.2.4")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.1")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}