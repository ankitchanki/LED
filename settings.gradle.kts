pluginManagement {
    repositories {
        google()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}
rootProject.name = "GlowLED"
include(":app")
