plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

val releaseKeystorePath = providers.environmentVariable("RUSTY_KIOSK_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("RUSTY_KIOSK_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RUSTY_KIOSK_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RUSTY_KIOSK_KEY_PASSWORD").orNull

android {
  namespace = "io.github.mesmerprism.rustykiosk.setuphelper"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustykiosk.setuphelper"
    minSdk = 34
    targetSdk = 34
    versionCode = 5
    versionName = "0.5.0"
  }

  signingConfigs {
    if (!releaseKeystorePath.isNullOrBlank()) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release")
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
