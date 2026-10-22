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

rootProject.name = "dmanime"

include(":app")
include(":core:catalog")
include(":core:danmaku")
include(":core:rules")
include(":core:sniff")
include(":core:update")
include(":tools:rule-cli")
