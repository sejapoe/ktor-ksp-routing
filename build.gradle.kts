allprojects {
    group = "ru.sejapoe.routing"
    version = "1.0.7"
}

plugins {
    id("maven-publish")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()

                from(components["java"])
            }
        }
    }
}