pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy { eachPlugin {
        if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}")
        if (requested.id.id.startsWith("org.jetbrains.kotlin.")) useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    } }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(providers.gradleProperty("weappSdkMavenDir").get()) }
        google(); mavenCentral()
    }
}
rootProject.name = "weapp-container-independent-consumer"
