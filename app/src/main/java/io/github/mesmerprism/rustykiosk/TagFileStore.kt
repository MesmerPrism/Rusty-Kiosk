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
    ensureExists()
    return TagFileCodec.parse(tagFile.readText(StandardCharsets.UTF_8))
  }

  fun setTags(entry: CatalogEntry, tags: Set<String>) {
    val normalizedTags = tags.map(::normalizeTag).filter(String::isNotEmpty).toSet()
    val records = load().toMutableList()
    val matchIndex =
      records.indexOfFirst { record ->
        if (entry.packageName != null) {
          record.packageName == entry.packageName
        } else {
          record.packageName == null && normalizeLookup(record.name) == normalizeLookup(entry.label)
        }
      }
    if (normalizedTags.isEmpty()) {
      if (matchIndex >= 0) records.removeAt(matchIndex)
    } else {
      val replacement =
        TagRecord(
          name = entry.label,
          packageName = entry.packageName,
          tags = normalizedTags,
        )
      if (matchIndex >= 0) records[matchIndex] = replacement else records.add(replacement)
    }
    writeAtomically(TagFileCodec.encode(records))
  }

  fun replaceJson(json: String) {
    TagFileCodec.parse(json)
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
