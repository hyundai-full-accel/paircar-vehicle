pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK (NaviHelper 등)
        // https://document.pleos.ai/docs/pleos-only/getting-started
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            // TextToSpeech 는 ai.pleos.playground.security 등 하위 그룹에도 의존한다
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..+)?") }
        }
    }
}

rootProject.name = "PairCar"
include(":app")
