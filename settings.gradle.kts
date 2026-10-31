rootProject.name = "techforge-minecraft"

plugins {
    // Downloads JDK 25 for you if you don't have it installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Every folder in plugins/ that has a src/ folder is a plugin. New plugins don't need to be registered here.
file("plugins").listFiles()
    ?.filter { File(it, "src").isDirectory }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
