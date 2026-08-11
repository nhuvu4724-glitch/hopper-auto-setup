pluginManagement {
    repositories {
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
        maven { name = "Meteor"; url = uri("https://maven.meteordev.org/releases") }
        maven { name = "MeteorSnapshots"; url = uri("https://maven.meteordev.org/snapshots") }
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "hopper-auto-setup"
