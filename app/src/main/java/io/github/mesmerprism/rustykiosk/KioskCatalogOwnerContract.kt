package io.github.mesmerprism.rustykiosk

import org.json.JSONArray
import org.json.JSONObject

internal data class KioskCatalogOwnerEntry(
  val catalogEntryId: String,
  val entryDigest: String,
  val appIdentity: String,
  val disambiguator: String,
  val label: String,
  val packageName: String,
  val tags: List<String>,
  val source: String,
  val target: LaunchTarget,
  val signingEvidence: AppSigningEvidence,
)

internal data class KioskCatalogSnapshot(
  val ownerEpoch: String,
  val catalogRevision: String,
  val snapshotDigest: String,
  val observedAtMs: Long,
  val freshUntilMs: Long,
  val tagRevision: String,
  val installedObservationRevision: String,
  val complete: Boolean,
  val totalCandidateCount: Int,
  val permissionLimitedCount: Int,
  val entries: List<KioskCatalogOwnerEntry>,
) {
  fun toSummaryJson(): JSONObject {
    return JSONObject()
      .put("schema", SCHEMA)
      .put("owner_epoch", ownerEpoch)
      .put("catalog_revision", catalogRevision)
      .put("snapshot_digest", snapshotDigest)
      .put("observed_at_ms", observedAtMs)
      .put("fresh_until_ms", freshUntilMs)
      .put("tag_revision", tagRevision)
      .put("installed_observation_revision", installedObservationRevision)
      .put("search_semantics", SEARCH_SEMANTICS)
      .put("complete", complete)
      .put("truncated", !complete && totalCandidateCount > MAX_ENTRIES)
      .put("total_candidate_count", totalCandidateCount)
      .put("permission_limited_count", permissionLimitedCount)
      .put(
        "entries",
        JSONArray().also { array ->
          entries.forEach { entry ->
            array.put(
              JSONObject()
                .put("catalog_entry_id", entry.catalogEntryId)
                .put("entry_digest", entry.entryDigest)
                .put("app_identity", entry.appIdentity)
                .put("disambiguator", entry.disambiguator)
                .put("label", entry.label)
                .put("tags", JSONArray(entry.tags))
                .put("installed", true)
                .put("launchable", true)
                .put("source", entry.source)

            )
          }
        },
      )
  }

  companion object {
    const val SCHEMA = "rusty.kiosk.catalog_snapshot.v1"
    const val SEARCH_SEMANTICS = "rusty.kiosk.catalog_search.v1"
    const val MAX_ENTRIES = 500
  }
}

internal object KioskCatalogIdentity {
  fun appIdentity(entry: CatalogEntry): String? {
    val packageName = entry.packageName ?: return null
    val signing = signingEvidence(entry) ?: return null
    return catalogDigest(
      "rusty.kiosk.cross_device_app_identity.v1",
      packageName,
      signing.canonicalIdentitySigners.joinToString(","),
    )
  }

  fun signingEvidence(entry: CatalogEntry): AppSigningEvidence? =
    entry.signingEvidence?.takeIf { entry.target?.packageName == entry.packageName }
}

internal object KioskCatalogSnapshotFactory {
  const val FRESHNESS_MS = 5 * 60 * 1000L

  fun create(
    ownerEpoch: String,
    catalogEntries: List<CatalogEntry>,
    observedAtMs: Long,
    tagRevision: String,
    installedObservationRevision: String,
  ): KioskCatalogSnapshot {
    require(OWNER_EPOCH.matches(ownerEpoch)) { "Invalid Kiosk owner epoch." }
    require(observedAtMs > 0 && observedAtMs <= Long.MAX_VALUE - FRESHNESS_MS) {
      "Invalid catalog observation time."
    }
    require(SHA256.matches(tagRevision) && SHA256.matches(installedObservationRevision)) {
      "Catalog source revisions must be SHA-256 digests."
    }
    val candidates = catalogEntries.filter { it.installed && it.launchable }
    val ownerEntries =
      candidates.mapNotNull { entry ->
        val packageName = entry.packageName ?: return@mapNotNull null
        val target = entry.target ?: return@mapNotNull null
        val signing = KioskCatalogIdentity.signingEvidence(entry) ?: return@mapNotNull null
        val appIdentity = KioskCatalogIdentity.appIdentity(entry) ?: return@mapNotNull null
        val catalogEntryId =
          catalogDigest(
            "rusty.kiosk.catalog_entry.v1",
            ownerEpoch,
            packageName,
            target.activityName,
            target.action,
            target.categories.sorted().joinToString(","),
          )
        val tags = entry.tags.map(::normalizeTag).filter(String::isNotBlank).distinct().sorted()
        // Metadata outside the consumer's closed shape is an explicit incomplete omission.
        // It is not a signer/permission failure and must not make the whole refresh unusable.
        if (tags.size > 64 || tags.any { value -> value.any(Char::isISOControl) }) return@mapNotNull null
        val label = entry.label.map { if (it.isISOControl()) ' ' else it }.joinToString("")
          .trim().replace(Regex("\\s+"), " ").take(160)
        if (label.isEmpty()) return@mapNotNull null
        val entryDigest =
          catalogDigest(
            "rusty.kiosk.catalog_entry_digest.v1",
            catalogEntryId,
            appIdentity,
            normalizeLookup(label),
            packageName,
            target.activityName,
            target.action,
            target.categories.sorted().joinToString(","),
            tags.joinToString(","),
            signing.lineageDigest,
          )
        KioskCatalogOwnerEntry(
          catalogEntryId = catalogEntryId,
          entryDigest = entryDigest,
          appIdentity = appIdentity,
          disambiguator = appIdentity.take(12),
          label = label,
          packageName = packageName,
          tags = tags,
          source = entry.source.take(64),
          target = target,
          signingEvidence = signing,
        )
      }
      .sortedWith(
        compareBy<KioskCatalogOwnerEntry> { normalizeLookup(it.label) }
          .thenBy(KioskCatalogOwnerEntry::appIdentity)
      )
    val permissionLimitedCount = candidates.count { KioskCatalogIdentity.signingEvidence(it) == null }
    val bounded = ownerEntries.take(KioskCatalogSnapshot.MAX_ENTRIES)
    val complete =
      permissionLimitedCount == 0 && ownerEntries.size == candidates.size &&
        ownerEntries.size <= KioskCatalogSnapshot.MAX_ENTRIES
    val catalogRevision =
      catalogDigest(
        "rusty.kiosk.catalog_revision.v1",
        ownerEpoch,
        tagRevision,
        installedObservationRevision,
        ownerEntries.joinToString("|") { it.entryDigest },
      )
    val snapshotDigest =
      catalogDigest(
        "rusty.kiosk.catalog_snapshot_digest.v1",
        ownerEpoch,
        catalogRevision,
        observedAtMs.toString(),
        (observedAtMs + FRESHNESS_MS).toString(),
        complete.toString(),
        candidates.size.toString(),
        permissionLimitedCount.toString(),
        bounded.joinToString("|") { it.entryDigest },
      )
    return KioskCatalogSnapshot(
      ownerEpoch = ownerEpoch,
      catalogRevision = catalogRevision,
      snapshotDigest = snapshotDigest,
      observedAtMs = observedAtMs,
      freshUntilMs = observedAtMs + FRESHNESS_MS,
      tagRevision = tagRevision,
      installedObservationRevision = installedObservationRevision,
      complete = complete,
      totalCandidateCount = candidates.size,
      permissionLimitedCount = permissionLimitedCount,
      entries = bounded,
    )
  }

  private val OWNER_EPOCH = Regex("^[A-Za-z0-9_-]{16,96}$")
  private val SHA256 = Regex("^[0-9a-f]{64}$")

}

