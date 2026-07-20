plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.paparazzi)
}

val productionSources =
  rootProject.file(
    "../../app/src/main/java/io/github/mesmerprism/rustykiosk"
  )

android {
  namespace = "io.github.mesmerprism.rustykiosk"
  compileSdk = 34

  defaultConfig { minSdk = 34 }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = "17" }
}

kotlin {
  sourceSets.named("main") {
    kotlin.srcDir(productionSources)
    kotlin.include(
      "CatalogModels.kt",
      "RustyKioskPanel.kt",
      "RustyKioskPanelContract.kt",
      "RustyKioskTheme.kt",
    )
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.foundation)
  implementation(libs.androidx.material3)
  testImplementation(libs.junit)
}
