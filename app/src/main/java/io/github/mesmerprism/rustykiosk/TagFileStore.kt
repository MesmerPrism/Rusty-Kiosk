package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.os.FileObserver
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class TagFileStore(context: Context) {
  private val appContext = context.applicationContext
  private val tagDirectory: File =
    File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "tags")
  val tagFile: File = File(tagDirectory, TAG_FILE_NAME)
  private var observer: FileObserver? = null

  fun ensureExists() {
    tagDirectory.mkdirs()
    if (!tagFile.exists()) {
      val defaultJson =
        appContext.assets.open(DEFAULT_ASSET).use { input ->
          input.readBytes().toString(StandardCharsets.UTF_8)
        }
      writeAtomically(defaultJson)
    }
  }

  fun load(): List<TagRecord> {
    return loadDocument().records
  }

  fun loadDocument(): TagFileDocument {
    ensureExists()
    require(tagFile.length() <= TagFileCodec.MAX_BYTES) {
      "Tag file exceeds ${TagFileCodec.MAX_BYTES} bytes."
    }
    return TagFileCodec.parseDocument(tagFile.readText(StandardCharsets.UTF_8))
  }

  fun setTags(entry: CatalogEntry, tags: Set<String>) {
    val current = loadDocument()
    writeAtomically(TagFileCodec.encode(TagDocumentEditor.setTags(current, entry, tags)))
  }

  fun setLaunchRequirement(entry: CatalogEntry, requirement: AppLaunchRequirement) {
    val current = loadDocument()
    writeAtomically(
      TagFileCodec.encode(TagDocumentEditor.setLaunchRequirement(current, entry, requirement))
    )
  }

  fun replaceJson(json: String) {
    TagFileCodec.parseDocument(json)
    writeAtomically(json)
  }

  fun startWatching(onChanged: () -> Unit) {
    stopWatching()
    ensureExists()
    observer =
      object : FileObserver(tagDirectory, WATCH_MASK) {
        override fun onEvent(event: Int, path: String?) {
          if (path == TAG_FILE_NAME) onChanged()
        }
      }.also(FileObserver::startWatching)
  }

  fun stopWatching() {
    observer?.stopWatching()
    observer = null
  }

  private fun writeAtomically(json: String) {
    tagDirectory.mkdirs()
    val temp = File(tagDirectory, "$TAG_FILE_NAME.tmp")
    temp.writeText(json, StandardCharsets.UTF_8)
    runCatching {
        Files.move(
          temp.toPath(),
          tagFile.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
      .recoverCatching {
        Files.move(temp.toPath(), tagFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      .getOrElse {
        temp.delete()
        error("Could not activate tag file.")
      }
  }

  private companion object {
    const val TAG_FILE_NAME = "app-tags.v1.json"
    const val DEFAULT_ASSET = "tags/default-app-tags.v1.json"
    const val WATCH_MASK =
      FileObserver.CLOSE_WRITE or
        FileObserver.CREATE or
        FileObserver.DELETE or
        FileObserver.MOVED_TO
  }
}
