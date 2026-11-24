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
        // DaVoice AARs, vendored as a local Maven repository:
        // libs/com/davoice/<artifact>/1.0.0/<artifact>-1.0.0.{aar,pom}
        maven { url = uri("$rootDir/libs") }
    }
}

rootProject.name = "Android_Native_Davoice"
include(":app")
// The DaVoice Car Agent (the same app as the React Native and Flutter example_car_agent).
include(":example_car_agent")
