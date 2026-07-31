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
val requestedReleaseVersion = providers.gradleProperty("rustyKioskReleaseVersion").orNull
val releaseVersionPattern =
  Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-alpha\.([1-9]|[1-8]\d|9[0-8]))?""")
val selectedVersionName = requestedReleaseVersion ?: "0.6.5"
val selectedVersionCode =
  if (requestedReleaseVersion == null) {
    15
  } else {
    val match =
      requireNotNull(releaseVersionPattern.matchEntire(requestedReleaseVersion)) {
        "rustyKioskReleaseVersion must be canonical X.Y.Z or X.Y.Z-alpha.N; major <= 2099, minor/patch <= 99, alpha N 1..98"
      }
    val major = match.groupValues[1].toLong()
    val minor = match.groupValues[2].toLong()
    val patch = match.groupValues[3].toLong()
    val suffix = match.groupValues[4].takeIf(String::isNotEmpty)?.toLong() ?: 99L
    val calculated = major * 1_000_000L + minor * 10_000L + patch * 100L + suffix
    require(major <= 2099 && calculated <= 2_100_000_000L) {
      "rustyKioskReleaseVersion exceeds the supported Android version-code range"
    }
    calculated.toInt()
  }

android {
  namespace = "io.github.mesmerprism.rustykiosk"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustykiosk"
    minSdk = 34
    targetSdk = 34
    versionCode = selectedVersionCode
    versionName = selectedVersionName

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
  implementation(project(":foreground-signal-client"))
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
