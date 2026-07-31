plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

val releaseKeystorePath = providers.environmentVariable("RUSTY_KIOSK_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("RUSTY_KIOSK_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RUSTY_KIOSK_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RUSTY_KIOSK_KEY_PASSWORD").orNull
val requestedReleaseVersion = providers.gradleProperty("rustyKioskReleaseVersion").orNull
val productChannel =
  providers.gradleProperty("rustyKioskProductChannel").orElse("stable").get().also {
    require(it == "stable" || it == "labs") {
      "rustyKioskProductChannel must be exactly stable or labs"
    }
  }
val isLabs = productChannel == "labs"
val kioskApplicationId =
  if (isLabs) "io.github.mesmerprism.rustykiosk.labs" else "io.github.mesmerprism.rustykiosk"
val helperApplicationId =
  if (isLabs) "io.github.mesmerprism.rustykiosk.setuphelper.labs" else
    "io.github.mesmerprism.rustykiosk.setuphelper"
val setupControlPermission = "$kioskApplicationId.permission.SETUP_CONTROL"
val setupControlAction = "$helperApplicationId.action.CONTROL"
val releaseVersionPattern =
  Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-alpha\.([1-9]|[1-8]\d|9[0-8]))?""")
val selectedVersionName = requestedReleaseVersion ?: "0.5.0"
val selectedVersionCode =
  if (requestedReleaseVersion == null) {
    5
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
  namespace = "io.github.mesmerprism.rustykiosk.setuphelper"
  compileSdk = 34

  defaultConfig {
    applicationId = helperApplicationId
    minSdk = 34
    targetSdk = 34
    versionCode = selectedVersionCode
    versionName = selectedVersionName
    manifestPlaceholders["setupLabel"] = if (isLabs) "Rusty Kiosk Labs Setup" else "Rusty Kiosk Setup"
    manifestPlaceholders["setupControlPermission"] = setupControlPermission
    manifestPlaceholders["setupControlAction"] = setupControlAction
    buildConfigField("String", "PRODUCT_CHANNEL", "\"$productChannel\"")
    buildConfigField("String", "KIOSK_PACKAGE", "\"$kioskApplicationId\"")
    buildConfigField("String", "CONTROL_ACTION", "\"$setupControlAction\"")
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
  testImplementation(libs.junit)
}
