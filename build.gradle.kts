plugins {
    id("java")
    id("fabric-loom") version ("1.7.3") apply (false)
}

val MINECRAFT_VERSION by extra { "1.21.1" }
val NEOFORGE_VERSION by extra { "21.1.46" }
val FABRIC_LOADER_VERSION by extra { "0.16.4" }
val FABRIC_API_VERSION by extra { "0.103.0+1.21.1" }

// This value can be set to null to disable Parchment.
// TODO: Re-add Parchment
val PARCHMENT_VERSION by extra { null }

// https://semver.org/
val MOD_VERSION by extra { "0.6.0-beta.2" }

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Disable the default publish task if it exists
tasks.replace("publish").dependsOn(":fabric:publish", ":neoforge:publish")
tasks.replace("publishToMavenLocal").dependsOn(":fabric:publishToMavenLocal", ":neoforge:publishToMavenLocal")

tasks.jar {
    enabled = false
}

subprojects {
    apply(plugin = "maven-publish")

    java.toolchain.languageVersion = JavaLanguageVersion.of(21)


    fun createVersionString(): String {
        val builder = StringBuilder()

        val isReleaseBuild = project.hasProperty("build.release")
        val buildId = System.getenv("GITHUB_RUN_NUMBER")

        if (isReleaseBuild) {
            builder.append(MOD_VERSION)
        } else {
            builder.append(MOD_VERSION.substringBefore('-'))
            builder.append("-snapshot")
        }

        builder.append("+mc").append(MINECRAFT_VERSION)

        if (!isReleaseBuild) {
            if (buildId != null) {
                builder.append("-build.${buildId}")
            } else {
                builder.append("-local")
            }
        }

        return builder.toString()
    }

    tasks.processResources {
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(mapOf("version" to createVersionString()))
        }
    }

    version = createVersionString()
    group = "net.caffeinemc.mods"

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }
}
