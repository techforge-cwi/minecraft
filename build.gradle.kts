import xyz.jpenilla.runpaper.task.RunServer

plugins {
    `java-base` // gives the root project access to Java toolchains, without making it a plugin itself
    // Starts a local Paper server with every plugin in this repo: ./gradlew runServer
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val mcVersion = providers.gradleProperty("mcVersion").get()
val paperApiVersion = providers.gradleProperty("paperApiVersion").get()

// ForgeCore is the shared AI gateway. Every other plugin can use it.
val corePlugin = "forge-core"

subprojects {
    apply(plugin = "java")
    group = "io.github.techforgecwi"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:$paperApiVersion")
        if (project.name != corePlugin) {
            "compileOnly"(project(":$corePlugin"))
        }
        "testImplementation"("io.papermc.paper:paper-api:$paperApiVersion")
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.add("-Xlint:deprecation")
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }

    // Stable file names (tf-<folder>.jar) so a deploy replaces the old copy instead of adding a second one.
    tasks.named<Jar>("jar") {
        archiveFileName = "tf-${project.name}.jar"
    }
}

tasks.named<RunServer>("runServer") {
    minecraftVersion(mcVersion)
    // Paper 26.x needs Java 25. Use the same toolchain the plugins compile with, not whatever runs Gradle.
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    subprojects.forEach { sub ->
        pluginJars.from(sub.tasks.named<Jar>("jar").flatMap { it.archiveFile })
    }
    // Running this means you accept the Minecraft EULA: https://aka.ms/MinecraftEULA
    jvmArgs("-Dcom.mojang.eula.agree=true", "-Xmx2G")
}

// ./gradlew newPlugin -Pplugin=my-plugin -Pauthor=your-github-username
tasks.register("newPlugin") {
    group = "techforge"
    description = "Creates plugins/<name> from templates/plugin."

    val templateDir = layout.projectDirectory.dir("templates/plugin").asFile
    val pluginsDir = layout.projectDirectory.dir("plugins").asFile
    val requestedId = providers.gradleProperty("plugin")
    val requestedAuthor = providers.gradleProperty("author")

    doLast {
        val id = requestedId.orNull?.trim()
            ?: throw GradleException("Name your plugin: ./gradlew newPlugin -Pplugin=my-plugin -Pauthor=your-github-username")
        if (!Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$").matches(id) || id.length > 30) {
            throw GradleException("'$id' isn't a valid name. Use lowercase letters, numbers and dashes, like 'trivia-bot'.")
        }
        val target = File(pluginsDir, id)
        if (target.exists()) throw GradleException("plugins/$id already exists.")

        val pascal = id.split("-").joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val packageName = "io.github.techforgecwi." + id.replace("-", "")
        val tokens = mapOf(
            "__ID__" to id,
            "__NAME__" to pascal,
            "__MAIN__" to "${pascal}Plugin",
            "__PACKAGE__" to packageName,
            "__PACKAGE_PATH__" to packageName.replace('.', '/'),
            "__AUTHOR__" to (requestedAuthor.orNull?.trim()?.removePrefix("@") ?: "YOUR-GITHUB-USERNAME"),
        )
        fun fill(text: String) = tokens.entries.fold(text) { acc, (key, value) -> acc.replace(key, value) }

        templateDir.walkTopDown().filter { it.isFile }.forEach { source ->
            val out = File(target, fill(source.relativeTo(templateDir).path))
            out.parentFile.mkdirs()
            out.writeText(fill(source.readText()))
        }
        println("Created plugins/$id. Next: edit plugins/$id/README.md, then run ./gradlew runServer to try it.")
    }
}
