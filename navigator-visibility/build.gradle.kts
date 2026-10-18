import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

plugins { alias(libs.plugins.android.library) }

val upstream = rootProject.layout.projectDirectory.dir("vendor/quest-navigator-visibility")
abstract class VerifyQuestVisibilityTask : DefaultTask() {
  @get:InputDirectory abstract val upstreamDirectory: DirectoryProperty
  @TaskAction fun verify() {
    val upstream = upstreamDirectory.get().asFile
    @Suppress("UNCHECKED_CAST")
    val lock = JsonSlurper().parse(upstream.resolve("UPSTREAM.json")) as Map<String, Any>
    check(lock["schema"] == "rusty.kiosk.quest_visibility_vendor.v1")
    @Suppress("UNCHECKED_CAST")
    val inventory = lock["files"] as List<Map<String, Any>>
    val paths = inventory.map { it["vendored_path"] as String }
    check(paths.size == paths.toSet().size && paths.size == 14)
    val actual = upstream.walkTopDown().filter { it.isFile }.map {
      it.relativeTo(upstream).invariantSeparatorsPath
    }.toSet()
    check(actual == paths.toSet() + setOf("README.md", "UPSTREAM.json"))
    inventory.forEach { row ->
      val path = row["vendored_path"] as String
      check(!path.startsWith("/") && !path.contains("\\") && !path.split('/').any { it == ".." || it == "." })
      val bytes = upstream.resolve(path).readBytes()
      val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
      check(bytes.size.toLong() == (row["bytes"] as Number).toLong() && sha == row["sha256"]) {
        "Vendored Quest source differs from its exact upstream inventory: $path"
      }
    }
  }
}
val verifyUpstream by tasks.registering(VerifyQuestVisibilityTask::class) {
  upstreamDirectory.set(upstream)
}
val signatures by tasks.registering(JavaCompile::class) {
  source(upstream.dir("compile-only"))
  classpath = files()
  destinationDirectory.set(layout.buildDirectory.dir("compile-only"))
  options.release.set(8)
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
val signatureJar by tasks.registering(Jar::class) {
  dependsOn(signatures)
  from(signatures.flatMap { it.destinationDirectory })
  archiveFileName.set("vendor-signatures-compile-only.jar")
}

android {
  namespace = "io.github.mesmerprism.rustyquest.navigatorvisibility"
  compileSdk = 34
  defaultConfig {
    minSdk = 24
    consumerProguardFiles(upstream.file("consumer-rules.pro"))
  }
  sourceSets.getByName("main") {
    java.setSrcDirs(listOf(upstream.dir("src/main/java")))
    // AGP owns namespace/minSdk; preserve the upstream manifest bytes as provenance.
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  // This exact vendor service is intentionally absent from the stock SDK StringDef.
  // The pinned upstream factory handles missing linkage; keep this exception module-local.
  lint { disable.add("WrongConstant") }
}

dependencies { compileOnly(files(signatureJar)) }
tasks.named("preBuild") { dependsOn(verifyUpstream) }
