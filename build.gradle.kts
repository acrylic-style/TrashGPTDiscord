plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

allprojects {
    apply {
        plugin("org.jetbrains.kotlin.jvm")
        plugin("org.jetbrains.kotlin.plugin.serialization")
        plugin("com.github.johnrengelman.shadow")
    }

    group = "xyz.acrylicstyle"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

dependencies {
    implementation("dev.kord:kord-core:0.12.0")
    implementation("org.slf4j:slf4j-simple:2.0.1")
    implementation("com.charleskorn.kaml:kaml:0.55.0") // YAML support for kotlinx.serialization
    implementation("com.aallam.openai:openai-client:3.6.0")
}

tasks {
    shadowJar {
        isZip64 = true
        manifest {
            attributes(
                "Main-Class" to "xyz.acrylicstyle.trashgptdiscord.MainKt",
            )
        }
        archiveFileName.set("TrashGPTDiscord.jar")
    }
}

kotlin {
    jvmToolchain(17)
}
