package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject

internal object TagFileCodec {
  const val SCHEMA_V1 = "rusty.kiosk.app_tags.v1"
  const val SCHEMA_V2 = "rusty.kiosk.app_tags.v2"
  const val SCHEMA = SCHEMA_V1
  const val MAX_BYTES = 256 * 1024
  private const val MAX_APPS = 500
  private const val MAX_TAGS_PER_APP = 64

  fun parse(json: String): List<TagRecord> = parseDocument(json).records

  /**
   * v1 remains the passive-tag legacy format and always means `any`. v2 is strict and stores one
   * dedicated launch requirement independently from ordinary searchable tags.
   */
  fun parseDocument(json: String): TagFileDocument {
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
      "Tag file exceeds $MAX_BYTES bytes."
    }
    val root = runCatching { JSONObject(json) }
      .getOrElse { throwable -> throw IllegalArgumentException("Tag file is not valid JSON.", throwable) }
    val schema = root.optString("schema")
    require(schema == SCHEMA_V1 || schema == SCHEMA_V2) { "Unsupported tag-file schema." }
    if (schema == SCHEMA_V2) {
      root.requireFields(setOf("schema", "apps"), setOf("schema", "apps"))
      require(root.get("schema") is String) { "Tag-file schema must be a string." }
      require(root.get("apps") is JSONArray) { "Tag-file apps must be an array." }
    }
    val apps = root.optJSONArray("apps") ?: JSONArray()
    require(apps.length() <= MAX_APPS) { "Tag file contains too many app records." }
    val seenV2Identities = mutableSetOf<String>()
    val definitions = buildList {
      for (index in 0 until apps.length()) {
        val item = apps.getJSONObject(index)
        if (schema == SCHEMA_V2) {
          item.requireFields(
            required = setOf("name"),
            allowed = setOf("name", "package", "tags", "requirements"),
          )
        }
        val rawName = item.opt("name")
        if (schema == SCHEMA_V2) require(rawName is String) {
          "App name must be a string at index $index."
        }
        val name = item.optString("name").trim().replace(Regex("\\s+"), " ")
        require(name.isNotEmpty() && name.length <= 160) { "Invalid app name at index $index." }
        val packageName = item.takeIf { it.has("package") }?.let {
          val value = it.get("package")
          if (schema == SCHEMA_V2) require(value is String) {
            "App package must be a string at index $index."
          }
          value.toString().trim().also { packageValue ->
            if (schema == SCHEMA_V2) require(packageValue.isNotEmpty()) {
              "App package must not be empty at index $index."
            }
          }
        }?.takeIf(String::isNotEmpty)?.also { value ->
          require(PACKAGE_NAME.matches(value)) { "Invalid package at index $index." }
        }
        if (schema == SCHEMA_V2) {
          val identity = packageName?.let { "package:$it" } ?: "name:${normalizeLookup(name)}"
          require(seenV2Identities.add(identity)) { "Duplicate app identity at index $index." }
        }
        if (schema == SCHEMA_V2 && item.has("tags")) require(item.get("tags") is JSONArray) {
          "App tags must be an array at index $index."
        }
        val tagArray = item.optJSONArray("tags") ?: JSONArray()
        require(tagArray.length() <= MAX_TAGS_PER_APP) { "Too many tags at app index $index." }
        val tags = buildSet {
          for (tagIndex in 0 until tagArray.length()) {
            val rawTag = tagArray.get(tagIndex)
            if (schema == SCHEMA_V2) require(rawTag is String) {
              "Tag values must be strings at app index $index."
            }
            normalizeTag(rawTag.toString()).takeIf(String::isNotEmpty)?.let(::add)
          }
        }
        val handlers = if (schema == SCHEMA_V2 && item.has("requirements")) {
          require(item.get("requirements") is JSONArray) {
            "App requirements must be an array at index $index."
          }
          val array = item.getJSONArray("requirements")
          require(array.length() <= 1) { "An app may have at most one launch requirement." }
          buildSet {
            for (requirementIndex in 0 until array.length()) {
              val raw = array.get(requirementIndex)
              require(raw is String) { "Active requirement IDs must be strings at app index $index." }
              require(add(ActiveRequirementHandlerId.parseStrict(raw))) {
                "Duplicate active requirement at app index $index."
              }
            }
          }
        } else emptySet()
        add(
          TagAppDefinition(
            record = TagRecord(name, packageName, tags),
            launchRequirement = AppLaunchRequirement.fromHandlers(handlers),
          )
        )
      }
    }
    return TagFileDocument(
      schema = schema,
      apps = definitions,
      documentDigest = stableDigest("rusty.kiosk.app_tags_document.v2", json),
    )
  }

  fun encode(records: List<TagRecord>): String = encode(
    TagFileDocument(
      schema = SCHEMA_V1,
      apps = records.map { TagAppDefinition(it) },
      documentDigest = "",
    )
  )

  fun encode(document: TagFileDocument): String {
    require(document.schema == SCHEMA_V1 || document.schema == SCHEMA_V2) {
      "Unsupported tag-file schema."
    }
    val apps = JSONArray()
    document.apps.sortedWith(
      compareBy<TagAppDefinition> { normalizeLookup(it.record.name) }
        .thenBy { it.record.packageName.orEmpty() }
    ).forEach { definition ->
      val record = definition.record
      val item = JSONObject().put("name", record.name)
      record.packageName?.let { item.put("package", it) }
      item.put("tags", JSONArray(record.tags.sorted()))
      if (document.schema == SCHEMA_V2) {
        val requirements = definition.launchRequirement.handler?.let { listOf(it.wireName) }.orEmpty()
        item.put("requirements", JSONArray(requirements))
      } else {
        require(definition.launchRequirement == AppLaunchRequirement.ANY) {
          "The v1 tag schema cannot encode an active launch requirement."
        }
      }
      apps.put(item)
    }
    val encoded = JSONObject().put("schema", document.schema).put("apps", apps).toString(2) + "\n"
    require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
      "Encoded tag file exceeds $MAX_BYTES bytes."
    }
    parseDocument(encoded)
    return encoded
  }

  private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
}

private fun JSONObject.requireFields(required: Set<String>, allowed: Set<String>) {
  val actual = keys().asSequence().toSet()
  require(required.all(actual::contains) && actual.all(allowed::contains)) {
    "Tag-file fields do not match the strict v2 schema."
  }
}

internal object TagDocumentEditor {
  fun setTags(document: TagFileDocument, entry: CatalogEntry, tags: Set<String>): TagFileDocument =
    edit(document, entry) { existing ->
      val normalizedTags = tags.map(::normalizeTag).filter(String::isNotEmpty).toSet()
      definition(entry, normalizedTags, existing?.launchRequirement ?: AppLaunchRequirement.ANY)
        .takeUnless { normalizedTags.isEmpty() && it.launchRequirement == AppLaunchRequirement.ANY }
    }

  fun setLaunchRequirement(
    document: TagFileDocument,
    entry: CatalogEntry,
    requirement: AppLaunchRequirement,
  ): TagFileDocument {
    val upgraded = if (document.schema == TagFileCodec.SCHEMA_V1) {
      document.copy(schema = TagFileCodec.SCHEMA_V2)
    } else document
    return edit(upgraded, entry) { existing ->
      val tags = existing?.record?.tags ?: entry.tags
      definition(entry, tags, requirement)
        .takeUnless { tags.isEmpty() && requirement == AppLaunchRequirement.ANY }
    }
  }

  private fun edit(
    document: TagFileDocument,
    entry: CatalogEntry,
    transform: (TagAppDefinition?) -> TagAppDefinition?,
  ): TagFileDocument {
    val apps = document.apps.toMutableList()
    var matchIndex = apps.indexOfFirst { definition -> matches(definition.record, entry) }
    if (matchIndex < 0 && entry.packageName != null) {
      val nameOnlyMatches = apps.withIndex().filter { (_, definition) ->
        definition.record.packageName == null &&
          normalizeLookup(definition.record.name) == normalizeLookup(entry.label)
      }
      if (nameOnlyMatches.size == 1) matchIndex = nameOnlyMatches.single().index
    }
    val existing = apps.getOrNull(matchIndex)
    val replacement = transform(existing)
    when {
      replacement == null && matchIndex >= 0 -> apps.removeAt(matchIndex)
      replacement != null && matchIndex >= 0 -> apps[matchIndex] = replacement
      replacement != null -> apps.add(replacement)
    }
    return document.copy(apps = apps)
  }

  private fun matches(record: TagRecord, entry: CatalogEntry): Boolean =
    if (entry.packageName != null) record.packageName == entry.packageName
    else record.packageName == null && normalizeLookup(record.name) == normalizeLookup(entry.label)

  private fun definition(
    entry: CatalogEntry,
    tags: Set<String>,
    requirement: AppLaunchRequirement,
  ) = TagAppDefinition(
    TagRecord(entry.label, entry.packageName, tags),
    requirement,
  )
}
