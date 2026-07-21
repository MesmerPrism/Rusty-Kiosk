plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "io.github.mesmerprism.rustykiosk.setuphelper"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustykiosk.setuphelper"
    minSdk = 34
    targetSdk = 34
    versionCode = 4
    versionName = "0.4.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  testImplementation(libs.junit)
}
