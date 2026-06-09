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
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.rokid.com/repository/maven-public/")
            content {
                includeGroup("com.rokid.cxr")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RokidLiveStudioAndroid"
include(":shared")
include(":phone-app")
include(":glasses-helper")
