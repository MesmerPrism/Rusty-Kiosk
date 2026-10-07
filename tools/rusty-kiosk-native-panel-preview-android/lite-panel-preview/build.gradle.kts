plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.paparazzi)
}

// Stage only the selected, byte-identical Java sources. The Android/Kotlin
// integration otherwise adds whole Java roots to its compiler inputs.
val stageProductionJava by tasks.registering(Sync::class) {
  from(rootProject.file("../../launcher-lite/src/main/java")) {
    include("**/LiteApp.java", "**/LiteAppAdapter.java", "**/LitePreferenceStore.java",
      "**/LiteTagPolicy.java", "**/LiteRecordPolicy.java", "**/LiteBrowsingState.java", "**/WifiRequirement.java")
  }
  from(rootProject.file("../../shared/catalog-search/src/main/java")) { include("**/CatalogSearch.java") }
  into(layout.buildDirectory.dir("production-java"))
}

android {
  namespace = "io.github.mesmerprism.rustykiosk.launcher.lite"
  compileSdk = 34
  defaultConfig { minSdk = 34 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  sourceSets.getByName("main") {
    res.srcDir(rootProject.file("../../launcher-lite/src/main/res"))
    java.setSrcDirs(listOf(layout.buildDirectory.dir("production-java")))
  }
  testOptions.unitTests.all {
    it.systemProperty("lite.preview.evidence", layout.buildDirectory.dir("preview-evidence").get().asFile.absolutePath)
  }
}
tasks.named("preBuild") { dependsOn(stageProductionJava) }
dependencies { testImplementation(libs.junit) }
