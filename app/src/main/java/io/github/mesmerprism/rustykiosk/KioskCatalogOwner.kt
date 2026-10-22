package io.github.mesmerprism.rustykiosk

import android.content.Context

/** Read-only adapter. It never starts an Activity, modifies tags, or evaluates launch policy. */
internal class KioskCatalogOwner(context: Context) {
  private val repository = InstalledAppRepository(context.applicationContext)
  private val tagStore = TagFileStore(context.applicationContext)

  fun snapshot(ownerEpoch: String): KioskCatalogSnapshot {
    val installed = repository.snapshot()
    val document = tagStore.loadPassive()
    return KioskCatalogSnapshotFactory.create(
      ownerEpoch = ownerEpoch,
      catalogEntries = CatalogAssembler.assemble(installed, document),
      observedAtMs = System.currentTimeMillis(),
      tagRevision = document.documentDigest,
      installedObservationRevision = catalogDigest(
        "rusty.kiosk.installed_observation.v1",
        installed.packages.sortedBy(InstalledPackage::packageName).joinToString("|") {
          listOf(it.packageName, normalizeLookup(it.label), it.signingEvidence?.lineageDigest.orEmpty())
            .joinToString("\u0000")
        },
        installed.launchableApps.sortedBy(InstalledApp::packageName).joinToString("|") {
          listOf(it.packageName, it.target.activityName, it.target.action,
            it.target.categories.sorted().joinToString(",")).joinToString("\u0000")
        },
      ),
    )
  }
}
