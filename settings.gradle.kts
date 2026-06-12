pluginManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")   // 腾讯 Maven Central 镜像
        maven("https://mirrors.tencent.com/nexus/repository/maven-google/")    // 腾讯 Google 镜像
        maven("https://mirrors.tencent.com/nexus/repository/gradle-plugins/") // 腾讯 Gradle Plugin Portal 镜像
        // 备选源
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-google/")    // 腾讯 Google 镜像
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")    // 腾讯 Maven Central 镜像
        // 备选源
        google()
        mavenCentral()
    }
}
rootProject.name = "HeartRateComparison"
include(":app")
