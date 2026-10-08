plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

group = "net.gravtech"
version = "1.0.0+26.1.2"

repositories {
    // Loom adds essential repositories automatically
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2")

    // ModMenu integration (compileOnly; entrypoint is only invoked when
    // ModMenu is installed alongside)
    compileOnly("com.terraformersmc:modmenu:18.0.2")
}

// No splitEnvironmentSourceSets: this mod is 100% client-only
// ("environment": "client") so all code lives in the main source set.
loom {
    mods {
        create("craftify-ytm-web") {
            sourceSet("main")
        }
    }
}

// Substitute ${version} in fabric.mod.json with the project version
tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
