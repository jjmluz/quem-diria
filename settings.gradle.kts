pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "QuemDiria"
// app: the Google TV game, which hosts the room. core: the rules and the phones' web server, in plain Kotlin.
include(":app", ":core")
