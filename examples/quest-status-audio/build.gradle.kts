plugins { id("com.android.application") version "8.11.1" }

android {
  namespace = "io.github.mesmerprism.queststatusaudioexample"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.queststatusaudioexample"
    minSdk = 31
    targetSdk = 34
    versionCode = 2
    versionName = "0.2"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies { testImplementation("junit:junit:4.13.2") }
