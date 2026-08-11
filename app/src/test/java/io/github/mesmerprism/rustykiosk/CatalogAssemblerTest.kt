package io.github.mesmerprism.rustykiosk

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAssemblerTest {
  private val target =
    LaunchTarget(
      packageName = "com.example.motion",
      activityName = "com.example.motion.MainActivity",
      action = Intent.ACTION_MAIN,
      categories = setOf(Intent.CATEGORY_LAUNCHER),
    )

  @Test
  fun nameOnlyTagsMatchInstalledLabelAndMissingEntriesRemainVisible() {
    val snapshot =
      InstalledSnapshot(
        launchableApps =
          listOf(
            InstalledApp(
              label = "Motion App",
              packageName = target.packageName,
              target = target,
              source = "android-launcher",
            )
          ),
        packages = listOf(InstalledPackage("Motion App", target.packageName)),
      )
    val records =
      listOf(
        TagRecord("motion app", null, setOf("movement")),
        TagRecord("Planned App", null, setOf("movement", "demo")),
      )

    val entries = CatalogAssembler.assemble(snapshot, records)
    val installed = entries.single { it.packageName == target.packageName }
    val missing = entries.single { it.label == "Planned App" }

    assertTrue(installed.installed)
    assertEquals(setOf("movement"), installed.tags)
    assertFalse(missing.installed)
    assertEquals(setOf("movement", "demo"), missing.tags)
    assertTrue(CatalogFilter.apply(entries, "", "movement").contains(missing))
  }

  @Test
  fun packageTagCanRepresentInstalledAppWithoutPublicLauncher() {
    val snapshot =
      InstalledSnapshot(
        launchableApps = emptyList(),
        packages = listOf(InstalledPackage("Hidden App", "com.example.hidden")),
      )

    val entry =
      CatalogAssembler.assemble(
          snapshot,
          listOf(TagRecord("External Name", "com.example.hidden", setOf("lab"))),
        )
        .single()

    assertTrue(entry.installed)
    assertFalse(entry.launchable)
    assertEquals("Hidden App", entry.label)
  }

  @Test
  fun searchCoversLabelsPackagesAndTags() {
    val entries =
      listOf(
        CatalogEntry(
          key = "one",
          label = "Motion App",
          packageName = "com.example.motion",
          target = target,
          installed = true,
          tags = setOf("movement"),
          source = "test",
        ),
        CatalogEntry(
          key = "two",
          label = "Quiet App",
          packageName = "com.example.quiet",
          target = null,
          installed = false,
          tags = setOf("calm"),
          source = "test",
        ),
      )

    assertEquals("Motion App", CatalogFilter.apply(entries, "example.motion", null).single().label)
    assertEquals("Quiet App", CatalogFilter.apply(entries, "calm", null).single().label)
    assertEquals("Motion App", CatalogFilter.apply(entries, "", "movement").single().label)
    assertEquals("Motion App", CatalogFilter.apply(entries, "motion movement", null).single().label)
    assertEquals("Quiet App", CatalogFilter.apply(entries, "quiet calm", null).single().label)
    assertEquals("Motion App", CatalogFilter.apply(entries, "example-motion", null).single().label)
    assertEquals("Quiet App", CatalogFilter.apply(entries, "quiet/calm", null).single().label)
    assertEquals("Motion App", CatalogFilter.apply(entries, "\"example motion\"", null).single().label)
    assertEquals("Motion App", CatalogFilter.apply(entries, "\"example/motion\"", null).single().label)
    assertEquals("Quiet App", CatalogFilter.apply(entries, "\"quiet app\"", null).single().label)
    assertTrue(CatalogFilter.apply(entries, "motion calm", null).isEmpty())
    assertTrue(CatalogFilter.apply(entries, "motion/calm", null).isEmpty())
    assertTrue(CatalogFilter.apply(entries, "\"quiet calm\"", null).isEmpty())
    assertTrue(CatalogFilter.apply(entries, "\"example movement\"", null).isEmpty())
  }
}
