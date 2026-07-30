plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "io.github.mesmerprism.rustykiosk.foregroundsignal"
  compileSdk = 34

  defaultConfig {
    minSdk = 29
    consumerProguardFiles("consumer-rules.pro")
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
}
