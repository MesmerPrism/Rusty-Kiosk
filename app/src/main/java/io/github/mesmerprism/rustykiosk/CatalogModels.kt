package io.github.mesmerprism.rustykiosk

import java.util.Locale

internal enum class KioskPassthroughStyle(
  val wireName: String,
  val label: String,
) {
  NATURAL("natural", "Natural"),
  CONTOUR_LUT("contour-lut", "Contour LUT");

  companion object {
    fun parse(value: String?): KioskPassthroughStyle =
      entries.firstOrNull { it.wireName == value } ?: NATURAL
  }
}

internal data class KioskPassthroughState(
  val style: KioskPassthroughStyle = KioskPassthroughStyle.NATURAL,
  val systemPassthroughEnabled: Boolean = false,
  val lutApplied: Boolean = false,
  val message: String = "Starting system passthrough…",
)

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

/** Dedicated launch policy. Ordinary searchable tags never imply or modify this value. */
internal enum class AppLaunchRequirement(
  val wireName: String,
  val handler: ActiveRequirementHandlerId?,
) {
  ANY("any", null),
  WIFI_ON("wifi-on", ActiveRequirementHandlerId.WIFI_ON),
  WIFI_OFF("wifi-off", ActiveRequirementHandlerId.WIFI_OFF);

  companion object {
    fun parseStrict(value: String): AppLaunchRequirement =
      entries.singleOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("Unknown app launch requirement.")

    fun fromHandlers(handlers: Set<ActiveRequirementHandlerId>): AppLaunchRequirement =
      when (handlers) {
        emptySet<ActiveRequirementHandlerId>() -> ANY
        setOf(ActiveRequirementHandlerId.WIFI_ON) -> WIFI_ON
        setOf(ActiveRequirementHandlerId.WIFI_OFF) -> WIFI_OFF
        else -> throw IllegalArgumentException("Conflicting app launch requirements.")
      }
  }
}

internal enum class ActiveRequirementHandlerId(val wireName: String) {
  WIFI_ON("wifi-on"),
  WIFI_OFF("wifi-off");

  companion object {
    fun parseStrict(value: String): ActiveRequirementHandlerId =
      entries.singleOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("Unknown active requirement handler.")
  }
}

internal data class TagAppDefinition(
  val record: TagRecord,
  val launchRequirement: AppLaunchRequirement = AppLaunchRequirement.ANY,
)

internal data class TagFileDocument(
  val schema: String,
  val apps: List<TagAppDefinition>,
  val documentDigest: String,
) {
  val records: List<TagRecord>
    get() = apps.map(TagAppDefinition::record)

  fun requirementFor(entry: CatalogEntry): AppLaunchRequirement {
    if (schema != "rusty.kiosk.app_tags.v2") return AppLaunchRequirement.ANY
    val definition = entry.packageName?.let { packageName ->
      apps.singleOrNull { it.record.packageName == packageName }
    } ?: apps.singleOrNull {
      it.record.packageName == null &&
        normalizeLookup(it.record.name) == normalizeLookup(entry.label)
    }
    return definition?.launchRequirement ?: AppLaunchRequirement.ANY
  }
}

internal data class CatalogEntry(
  val key: String,
  val label: String,
  val packageName: String?,
  val target: LaunchTarget?,
  val installed: Boolean,
  val tags: Set<String>,
  val source: String,
  val launchRequirement: AppLaunchRequirement = AppLaunchRequirement.ANY,
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
  val userControlsOpen: Boolean = false,
  val userControls: UserControlState = UserControlState(),
  val searchFocusRequest: Long = 0L,
  val tagFocusRequest: Long = 0L,
  val pendingRequirementLaunchId: String? = null,
  val pendingRequirementMessage: String? = null,
  val selectedLaunchOptions: AppLaunchOptionsUiState = AppLaunchOptionsUiState(),
  val lastDispatchedOptionId: String? = null,
  val lastDispatchedOptionPackage: String? = null,
) {
  val tags: List<String>
    get() = entries.flatMap { it.tags }.distinct().sorted()

  val visibleEntries: List<CatalogEntry>
    get() = CatalogFilter.apply(entries, searchQuery, selectedTag)

  val selectedEntry: CatalogEntry?
    get() = entries.firstOrNull { it.key == selectedKey }
}

internal data class UserControlState(
  val passthroughStyle: KioskPassthroughStyle = KioskPassthroughStyle.NATURAL,
  val systemPassthroughEnabled: Boolean = false,
  val passthroughLutApplied: Boolean = false,
  val passthroughMessage: String = "Starting system passthrough…",
  val setupHelperInstalled: Boolean = false,
  val setupHelperReady: Boolean = false,
  val requestWifiAfterBoot: Boolean = false,
  val wirelessDebuggingEnabled: Boolean = false,
  val accessibilityEnabled: Boolean = false,
  val operatorBridgeEnabled: Boolean = false,
  val operatorBridgeRunning: Boolean = false,
  val operatorBridgeEndpoint: String? = null,
  val operatorBridgePairingCode: String = "",
  val installerAllowed: Boolean = false,
  val operatorBridgeError: String? = null,
  val operationInProgress: String? = null,
  val message: String =
    "Wi-Fi ADB, Accessibility, the direct link, and local installs are separate opt-ins.",
) {
  val passthroughStatusLabel: String
    get() =
      if (systemPassthroughEnabled && passthroughLutApplied) {
        passthroughStyle.label
      } else {
        "Unavailable"
      }

  val setupStatusLabel: String
    get() =
      when {
        !setupHelperInstalled -> "Not installed"
        !setupHelperReady -> "Needs USB-C setup"
        else -> "Ready"
      }

  val wifiStatusLabel: String
    get() =
      when {
        operationInProgress == "request_wifi_adb" -> "Requesting"
        operationInProgress == "disable_wifi_adb" -> "Turning off"
        !wirelessDebuggingEnabled -> "Off"
        else -> "On"
      }

  val accessibilityStatusLabel: String
    get() = if (accessibilityEnabled) "Enabled" else "Disabled"

  val operatorBridgeStatusLabel: String
    get() =
      when {
        !operatorBridgeEnabled -> "Off"
        operatorBridgeRunning -> "Ready"
        operatorBridgeError != null -> "Error"
        else -> "Starting"
      }
}

internal object CatalogFilter {
  fun apply(
    entries: List<CatalogEntry>,
    searchQuery: String,
    selectedTag: String?,
  ): List<CatalogEntry> {
    val terms =
      normalizeLookup(searchQuery)
        .takeIf(String::isNotEmpty)
        ?.split(SEARCH_TERM_SEPARATOR)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    val tag = selectedTag?.let(::normalizeTag)
    return entries
      .asSequence()
      .filter { entry -> tag == null || tag in entry.tags }
      .filter { entry ->
        val searchable =
          listOf(normalizeLookup(entry.label), normalizeLookup(entry.packageName.orEmpty())) +
            entry.tags.map(::normalizeLookup)
        terms.all { term -> searchable.any { value -> value.contains(term) } }
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
  fun assemble(snapshot: InstalledSnapshot, document: TagFileDocument): List<CatalogEntry> =
    assemble(snapshot, document.records).map { entry ->
      entry.copy(launchRequirement = document.requirementFor(entry))
    }

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

private val SEARCH_TERM_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
private const val MAX_TAG_LENGTH = 40
