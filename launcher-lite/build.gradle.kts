plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
}

val trustedKioskReleaseManifest =
  providers.fileContents(
    rootProject.layout.projectDirectory.file("launcher/trust/rusty-kiosk-v0.6.4-bundle-manifest.json"),
  ).asText
val expectedTargetSignerSha256 =
  trustedKioskReleaseManifest.map { manifest ->
    Regex("\"signer_sha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
      .find(manifest)
      ?.groupValues
      ?.get(1)
      ?: error("Trusted Rusty Kiosk release manifest is missing signer_sha256")
  }

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
  namespace = "io.github.mesmerprism.rustykiosk.launcher.lite"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.rustykiosk.launcher"
    minSdk = 34
    targetSdk = 34
    ndk { abiFilters += "arm64-v8a" }
    versionCode = 3
    versionName = "0.3.0"
    testInstrumentationRunner =
      "io.github.mesmerprism.rustykiosk.launcher.lite.LiteStoreAssetInstrumentation"
    buildConfigField("String", "TARGET_PACKAGE", "\"io.github.mesmerprism.rustykiosk\"")
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
    getByName("debug") {
      if (releaseSigningReady) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
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

  sourceSets.getByName("main").java.srcDir(rootProject.file("shared/catalog-search/src/main/java"))

  lint {
    abortOnError = true
    checkReleaseBuilds = true
  }
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  testImplementation(libs.junit)
}

spatial { allowUsageDataCollection.set(false) }
