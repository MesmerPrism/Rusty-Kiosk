plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.meta.spatial.plugin)
}

android {
  namespace = "io.github.mesmerprism.rustykiosk"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustykiosk"
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"

    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  packaging {
    resources.excludes.add("META-INF/LICENSE")
    resources.excludes.add("META-INF/LICENSE.md")
    resources.excludes.add("META-INF/LICENSE-notice.md")
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
  implementation(libs.meta.spatial.sdk.vr)

  testImplementation(libs.junit)
  testImplementation(libs.json)
}

spatial {
  allowUsageDataCollection.set(false)
}
