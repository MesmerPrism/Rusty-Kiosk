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

rootProject.name = "RustyKiosk"
include(":app")
include(":navigator-visibility")
include(":foreground-signal-client")
include(":launcher")
include(":setup-helper")
include(":launcher-lite")
