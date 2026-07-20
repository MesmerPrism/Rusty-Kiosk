package io.github.mesmerprism.rustykiosk

import java.util.Locale

internal data class LaunchTarget(
  val packageName: String,
  val activityName: String,
  val action: String,
  val categories: Set<String>,
)

internal data class InstalledApp(
  val label: String,
  val packageName: String,
  val target: LaunchTarget,
  val source: String,
)

internal data class InstalledPackage(
  val label: String,
  val packageName: String,
)

internal data class InstalledSnapshot(
  val launchableApps: List<InstalledApp>,
  val packages: List<InstalledPackage>,
)

internal data class TagRecord(
  val name: String,
  val packageName: String?,
  val tags: Set<String>,
)

internal data class CatalogEntry(
  val key: String,
  val label: String,
  val packageName: String?,
  val target: LaunchTarget?,
  val installed: Boolean,
  val tags: Set<String>,
  val source: String,
) {
  val launchable: Boolean
    get() = installed && target != null

  val statusLabel: String
    get() =
      when {
        !installed -> "Not installed"
        target == null -> "Installed, no public launch activity"
        else -> "Installed"
      }
}

internal data class KioskUiState(
  val entries: List<CatalogEntry> = emptyList(),
  val searchQuery: String = "",
  val selectedTag: String? = null,
  val selectedKey: String? = null,
  val statusLine: String = "Loading installed apps",
  val tagFilePath: String = "",
  val guardEnabled: Boolean = false,
) {
  val tags: List<String>
    get() = entries.flatMap { it.tags }.distinct().sorted()

  val visibleEntries: List<CatalogEntry>
    get() = CatalogFilter.apply(entries, searchQuery, selectedTag)

  val selectedEntry: CatalogEntry?
    get() = entries.firstOrNull { it.key == selectedKey }
}

internal object CatalogFilter {
  fun apply(
    entries: List<CatalogEntry>,
    searchQuery: String,
    selectedTag: String?,
  ): List<CatalogEntry> {
    val query = normalizeLookup(searchQuery)
    val tag = selectedTag?.let(::normalizeTag)
    return entries
      .asSequence()
      .filter { entry -> tag == null || tag in entry.tags }
      .filter { entry ->
        query.isEmpty() ||
          normalizeLookup(entry.label).contains(query) ||
          normalizeLookup(entry.packageName.orEmpty()).contains(query) ||
          entry.tags.any { normalizeLookup(it).contains(query) }
      }
      .sortedWith(
        compareByDescending<CatalogEntry> { it.installed }
          .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
          .thenBy { it.packageName.orEmpty() }
      )
      .toList()
  }
}

internal object CatalogAssembler {
  fun assemble(snapshot: InstalledSnapshot, tagRecords: List<TagRecord>): List<CatalogEntry> {
    val entries =
      snapshot.launchableApps
        .associate { app ->
          app.packageName to
            CatalogEntry(
              key = "package:${app.packageName}",
              label = app.label,
              packageName = app.packageName,
              target = app.target,
              installed = true,
              tags = emptySet(),
              source = app.source,
            )
        }
        .toMutableMap()
    val installedByPackage = snapshot.packages.associateBy { it.packageName }
    val installedByLabel = snapshot.packages.groupBy { normalizeLookup(it.label) }

    tagRecords.forEach { record ->
      val matches =
        if (record.packageName != null) {
          listOfNotNull(record.packageName.takeIf(installedByPackage::containsKey))
        } else {
          installedByLabel[normalizeLookup(record.name)].orEmpty().map { it.packageName }
        }

      if (matches.isEmpty()) {
        val missingKey =
          record.packageName?.let { "missing-package:$it" }
            ?: "missing-name:${normalizeLookup(record.name)}"
        val previous = entries[missingKey]
        entries[missingKey] =
          CatalogEntry(
            key = missingKey,
            label = record.name,
            packageName = record.packageName,
            target = null,
            installed = false,
            tags = previous?.tags.orEmpty() + record.tags,
            source = "tag-file",
          )
      } else {
        matches.forEach { packageName ->
          val previous = entries[packageName]
          val installedPackage = installedByPackage.getValue(packageName)
          val updated =
            previous?.copy(tags = previous.tags + record.tags)
              ?: CatalogEntry(
                key = "package:$packageName",
                label = installedPackage.label,
                packageName = packageName,
                target = null,
                installed = true,
                tags = record.tags,
                source = "tag-file-installed-package",
              )
          entries[packageName] = updated
        }
      }
    }

    return entries.values
      .distinctBy { it.key }
      .sortedWith(
        compareByDescending<CatalogEntry> { it.installed }
          .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
          .thenBy { it.packageName.orEmpty() }
      )
  }
}

internal fun normalizeLookup(value: String): String =
  value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

internal fun normalizeTag(value: String): String = normalizeLookup(value).take(MAX_TAG_LENGTH)

private const val MAX_TAG_LENGTH = 40
