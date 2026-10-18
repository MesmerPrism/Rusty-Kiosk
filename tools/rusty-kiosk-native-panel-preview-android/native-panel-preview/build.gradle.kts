import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.paparazzi)
}

val productionSources =
  rootProject.file(
    "../../app/src/main/java/io/github/mesmerprism/rustykiosk"
  )

/** Project the canonical UI declarations without copying a separately maintained model. */
abstract class StageLaunchOptionPreviewModels : DefaultTask() {
  @get:InputFile abstract val sourceFile: RegularFileProperty
  @get:OutputFile abstract val destinationFile: RegularFileProperty
  @TaskAction fun stage() {
    val source = sourceFile.get().asFile.readText()
    val start = source.indexOf("\ninternal data class AppLaunchOption(")
    val end = source.indexOf("\ninternal data class RawAppLaunchOption(")
    check(start >= 0 && end > start) { "Canonical launch-option model declaration boundary changed." }
    val selected = "package io.github.mesmerprism.rustykiosk\n" + source.substring(start, end)
    val destination = destinationFile.get().asFile
    destination.parentFile.mkdirs()
    if (!destination.exists() || destination.readText() != selected) destination.writeText(selected)
  }
}
val stageLaunchOptionModels by tasks.registering(StageLaunchOptionPreviewModels::class) {
  sourceFile.set(productionSources.resolve("AppLaunchOptions.kt"))
  destinationFile.set(layout.buildDirectory.file("production-models/AppLaunchOptionModels.kt"))
}

android {
  namespace = "io.github.mesmerprism.rustykiosk"
  compileSdk = 34

  defaultConfig { minSdk = 34 }

  buildFeatures { compose = true }
  sourceSets["main"].java.srcDir(rootProject.file("../../shared/catalog-search/src/main/java"))

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions { jvmTarget = "17" }
}

kotlin {
  sourceSets.named("main") {
    kotlin.srcDir(productionSources)
    kotlin.srcDir(stageLaunchOptionModels.map { it.destinationFile.get().asFile.parentFile })
    kotlin.include(
      "CatalogModels.kt",
      "AppLaunchOptionModels.kt",
      "GuardMode.kt",
      "BootRequestStatus.kt",
      "RustyKioskPanel.kt",
      "RustyKioskPanelContract.kt",
      "RustyKioskTheme.kt",
    )
  }
}
tasks.named("preBuild") { dependsOn(stageLaunchOptionModels) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.foundation)
  implementation(libs.androidx.material3)
  implementation(libs.meta.spatial.sdk.uiset)
  testImplementation(libs.junit)
}
