plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
}

group = providers.gradleProperty("maven_group").get()
version = "1.0.0"
base { archivesName = providers.gradleProperty("archives_base_name").get() }

repositories {
    maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
    maven { name = "Meteor"; url = uri("https://maven.meteordev.org/releases") }
    maven { name = "MeteorSnapshots"; url = uri("https://maven.meteordev.org/snapshots") }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.3:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.2")

    // Thay thế file jar local bằng dependency lấy trực tiếp từ Meteor Maven
    modImplementation("meteordevelopment:meteor-client:1.21.1-SNAPSHOT")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
