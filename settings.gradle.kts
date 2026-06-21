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
        // 【必须添加】Xposed 官方 Maven 仓库
        maven { url = uri("https://api.xposed.info/") }
    }
}
rootProject.name = "HapticAudio"
include(":app")

