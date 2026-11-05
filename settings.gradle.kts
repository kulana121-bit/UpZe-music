pluginManagement {
    repositories {
        // Maven Central answers HTTP 429 from this sandbox at times; Google's mirror of it is tried first.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // NewPipeExtractor and its nanojson fork are published only on JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "UpZe"
include(":app")
