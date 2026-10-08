plugins {
    id("fabric-loom") version "1.6-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "net.gravtech"
version = "1.0.0+26.1.2"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
    maven("https://jitpack.io")
}

dependencies {
    // Fabric Loader & API for 26.1.2
    minecraft "com.mojang:minecraft:26.1.2"
    mappings "net.fabricmc:yarn:26.1.2+build.1:v2"
    modImplementation "net.fabricmc:fabric-loader:0.19.5"
    modImplementation "net.fabricmc:fabric-api:26.1.2"
    modApi "net.fabricmc:fabric-api:26.1.2"
    
    // OkHttp for WebSocket client
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
    implementation "com.squareup.okio:okio:3.6.0"
    
    // Kotlin stdlib
    implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.23"
}

loom {
    mappings(loom.officialMojangMappings())
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "net.gravtech.Craftify"
    }
    mergeServiceFiles()
}
