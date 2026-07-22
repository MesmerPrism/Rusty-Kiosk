plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.meta.spatial.plugin)
}

val releaseKeystorePath = providers.environmentVariable("RUSTY_KIOSK_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("RUSTY_KIOSK_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RUSTY_KIOSK_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RUSTY_KIOSK_KEY_PASSWORD").orNull

android {
  namespace = "io.github.mesmerprism.rustykiosk"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustykiosk"
    minSdk = 34
    targetSdk = 34
    versionCode = 13
    versionName = "0.6.3"

    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  packaging {
    resources.excludes.add("META-INF/LICENSE")
    resources.excludes.add("META-INF/LICENSE.md")
    resources.excludes.add("META-INF/LICENSE-notice.md")
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

  buildFeatures {
    compose = true
    buildConfig = true
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
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.foundation)
  implementation(libs.androidx.material3)
  debugImplementation(libs.androidx.ui.tooling)

  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.uiset)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.nanohttpd)

  testImplementation(libs.junit)
  testImplementation(libs.json)
}

spatial {
  allowUsageDataCollection.set(false)
}
