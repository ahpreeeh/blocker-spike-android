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

rootProject.name = "BlockerSpike"
include(":app")
// Harnais PC (test T3 sur Windows via litertlm-jvm) : valide le pipeline
// prompt -> Gemma -> validateur sans téléphone. Pas une cible produit.
include(":pc-harness")
