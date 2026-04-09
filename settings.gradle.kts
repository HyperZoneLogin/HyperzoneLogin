pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://plugins.gradle.org/m2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "HyperzoneLogin"

include("velocity")
include("api")
include("auth-yggd")
include("auth-offline")
include("data-merge")
include("profile-skin")
