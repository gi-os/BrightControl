pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // libadb-android + its cert helper are published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "BrightControl"
include(":app")
