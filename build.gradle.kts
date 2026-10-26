plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

group = "net.gravtech"
version = "1.0.0+26.3"

repositories {
    // Loom adds essential repositories automatically
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.165.0+26.3")

    // ModMenu integration (compileOnly; entrypoint is only invoked when
    // ModMenu is installed alongside)
    compileOnly("com.terraformersmc:modmenu:21.0.0")
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
