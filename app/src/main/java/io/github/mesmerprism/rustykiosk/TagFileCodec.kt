package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject

internal object TagFileCodec {
  const val SCHEMA = "rusty.kiosk.app_tags.v1"
  const val MAX_BYTES = 256 * 1024

  fun parse(json: String): List<TagRecord> {
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Tag file exceeds $MAX_BYTES bytes." }
    val root = JSONObject(json)
    require(root.optString("schema") == SCHEMA) { "Unsupported tag-file schema." }
    val apps = root.optJSONArray("apps") ?: JSONArray()
    return buildList {
      for (index in 0 until apps.length()) {
        val item = apps.getJSONObject(index)
        val name = item.optString("name").trim().replace(Regex("\\s+"), " ")
        require(name.isNotEmpty() && name.length <= 160) { "Invalid app name at index $index." }
        val packageName =
          item.optString("package").trim().takeIf(String::isNotEmpty)?.also { value ->
            require(PACKAGE_NAME.matches(value)) { "Invalid package at index $index." }
          }
        val tagArray = item.optJSONArray("tags") ?: JSONArray()
        val tags =
          buildSet {
            for (tagIndex in 0 until tagArray.length()) {
              val tag = normalizeTag(tagArray.getString(tagIndex))
              if (tag.isNotEmpty()) add(tag)
            }
          }
        add(TagRecord(name = name, packageName = packageName, tags = tags))
      }
    }
  }

  fun encode(records: List<TagRecord>): String {
    val apps = JSONArray()
    records
      .sortedWith(
        compareBy<TagRecord> { normalizeLookup(it.name) }
          .thenBy { it.packageName.orEmpty() }
      )
      .forEach { record ->
        val item = JSONObject().put("name", record.name)
        record.packageName?.let { item.put("package", it) }
        item.put("tags", JSONArray(record.tags.sorted()))
        apps.put(item)
      }
    return JSONObject().put("schema", SCHEMA).put("apps", apps).toString(2) + "\n"
  }

  private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
}
