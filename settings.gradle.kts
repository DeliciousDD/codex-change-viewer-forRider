import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "codex-change-viewer"

pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.0"
        id("org.jetbrains.intellij.platform") version "2.18.1"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform { defaultRepositories() }
    }
}
