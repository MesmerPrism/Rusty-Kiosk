plugins {
  alias(libs.plugins.android.application)
}

val trustedKioskReleaseManifest =
  providers.fileContents(
    layout.projectDirectory.file("trust/rusty-kiosk-v0.6.4-bundle-manifest.json"),
  ).asText
val expectedTargetSignerSha256 =
  trustedKioskReleaseManifest.map { manifest ->
    Regex("\"signer_sha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
      .find(manifest)
      ?.groupValues
      ?.get(1)
      ?: error("Trusted Rusty Kiosk release manifest is missing signer_sha256")
  }

val launcherDistribution =
  providers.environmentVariable("RUSTY_KIOSK_LAUNCHER_DISTRIBUTION")
    .orElse("Store")
val launcherApplicationId =
  launcherDistribution.map { distribution ->
    when (distribution) {
      "Store" -> "io.github.mesmerprism.rustykiosk.launcher"
      "LabsStore" -> "io.github.mesmerprism.rustykiosk.launcher.labstore"
      "Business" -> "io.github.mesmerprism.rustykiosk.launcher.business"
      else -> error(
        "RUSTY_KIOSK_LAUNCHER_DISTRIBUTION must be exactly Store, LabsStore, or Business",
      )
    }
  }
val targetPackage =
  launcherDistribution.map { distribution ->
    when (distribution) {
      "Store" -> "io.github.mesmerprism.rustykiosk"
      "LabsStore" -> "io.github.mesmerprism.rustykiosk.labs"
      "Business" ->
        providers.environmentVariable("RUSTY_KIOSK_LAUNCHER_TARGET_PACKAGE")
          .orElse("io.github.mesmerprism.rustykiosk").get()
      else -> error("unreachable launcher distribution")
    }
  }
val launcherProductChannel =
  launcherDistribution.map { if (it == "LabsStore") "labs" else "stable" }
val launcherLabel =
  launcherDistribution.map { if (it == "LabsStore") "Rusty Kiosk Lab Launcher" else "Rusty Kiosk Launcher" }

val releaseKeystorePath =
  providers.environmentVariable("RUSTY_KIOSK_LAUNCHER_KEYSTORE_PATH").orNull
val releaseKeystorePassword =
  providers.environmentVariable("RUSTY_KIOSK_LAUNCHER_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias =
  providers.environmentVariable("RUSTY_KIOSK_LAUNCHER_KEY_ALIAS").orNull
val releaseKeyPassword =
  providers.environmentVariable("RUSTY_KIOSK_LAUNCHER_KEY_PASSWORD").orNull
val releaseSigningReady =
  listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
  ).all { !it.isNullOrBlank() }

android {
  namespace = "io.github.mesmerprism.rustykiosk.launcher"
  compileSdk = 34

  defaultConfig {
    applicationId = launcherApplicationId.get()
    minSdk = 34
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    manifestPlaceholders["kioskTargetPackage"] = targetPackage.get()
    manifestPlaceholders["launcherLabel"] = launcherLabel.get()

    buildConfigField("String", "TARGET_PACKAGE", "\"${targetPackage.get()}\"")
    buildConfigField("String", "PRODUCT_CHANNEL", "\"${launcherProductChannel.get()}\"")
    buildConfigField(
      "String",
      "EXPECTED_TARGET_SIGNER_SHA256",
      "\"${expectedTargetSignerSha256.get()}\"",
    )
  }

  signingConfigs {
    if (releaseSigningReady) {
      create("release") {
        storeFile = file(releaseKeystorePath!!)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    getByName("release") {
      isDebuggable = false
      isMinifyEnabled = false
      if (releaseSigningReady) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }

  buildFeatures {
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
}

dependencies {
  testImplementation(libs.junit)
}
