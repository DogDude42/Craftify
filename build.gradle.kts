plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

group = "net.gravtech"
version = "1.0.0+26.1.2"

repositories {
    // Loom adds essential repositories automatically
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2")
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
