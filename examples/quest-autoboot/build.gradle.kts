plugins { id("com.android.application") version "8.11.1" }

android {
  namespace = "io.github.mesmerprism.questautobootexample"
  compileSdk = 34

  defaultConfig {
    applicationId = "io.github.mesmerprism.questautobootexample"
    minSdk = 29
    targetSdk = 34
    versionCode = 1
    versionName = "0.1"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies { testImplementation("junit:junit:4.13.2") }
