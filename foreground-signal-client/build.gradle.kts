plugins {
  alias(libs.plugins.android.library)
}

val productChannel =
  providers.gradleProperty("rustyKioskProductChannel").orElse("stable").get().also {
    require(it == "stable" || it == "labs") {
      "rustyKioskProductChannel must be exactly stable or labs"
    }
  }
val providerAuthority =
  if (productChannel == "labs") {
    "io.github.mesmerprism.rustykiosk.labs.foreground-signal"
  } else {
    "io.github.mesmerprism.rustykiosk.foreground-signal"
  }

android {
  namespace = "io.github.mesmerprism.rustykiosk.foregroundsignal"
  compileSdk = 34

  defaultConfig {
    minSdk = 29
    consumerProguardFiles("consumer-rules.pro")
    manifestPlaceholders["foregroundSignalProviderAuthority"] = providerAuthority
    buildConfigField("String", "PRODUCT_CHANNEL", "\"$productChannel\"")
    buildConfigField("String", "PROVIDER_AUTHORITY", "\"$providerAuthority\"")
  }

  buildFeatures {
    buildConfig = true
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
