package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TagFileCodecTest {
  @Test
  fun parsesNameOnlyRecordsAndNormalizesTags() {
    val records =
      TagFileCodec.parse(
        """
        {
          "schema": "rusty.kiosk.app_tags.v1",
          "apps": [
            {"name": "  Planned   App ", "tags": [" Demo ", "demo", "Movement"]}
          ]
        }
        """.trimIndent()
      )

    assertEquals(1, records.size)
    assertEquals("Planned App", records.single().name)
    assertNull(records.single().packageName)
    assertEquals(setOf("demo", "movement"), records.single().tags)
  }

  @Test
  fun encodeRoundTripsPackageRecords() {
    val input =
      listOf(
        TagRecord(
          name = "Installed App",
          packageName = "com.example.installed",
          tags = setOf("demo", "favorite"),
        )
      )

    assertEquals(input, TagFileCodec.parse(TagFileCodec.encode(input)))
  }

  @Test
  fun rejectsUnknownSchema() {
    assertThrows(IllegalArgumentException::class.java) {
      TagFileCodec.parse("""{"schema":"other","apps":[]}""")
    }
  }
}
