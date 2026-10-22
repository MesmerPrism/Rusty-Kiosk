package io.github.mesmerprism.rustykiosk

import org.json.JSONObject

internal data class DirectOperatorV2SettingsState(
  val pairingId: String,
  val keyEpoch: String,
  val kdfSalt: String,
  val summaryGranted: Boolean,
  val summaryGrantEpoch: String,
  val detailGranted: Boolean,
  val detailGrantEpoch: String,
) {
  init {
    require(TOKEN.matches(pairingId) && TOKEN.matches(keyEpoch))
    require(DirectOperatorV2Base64.decodeExact(kdfSalt, DirectOperatorV2Crypto.KDF_SALT_BYTES) != null)
    require(TOKEN.matches(summaryGrantEpoch) && TOKEN.matches(detailGrantEpoch))
    require(!detailGranted || summaryGranted)
  }

  fun toJson(): JSONObject =
    JSONObject()
      .put("schema", SCHEMA)
      .put("pairing_id", pairingId)
      .put("key_epoch", keyEpoch)
      .put("kdf_salt", kdfSalt)
      .put("catalog_summary_granted", summaryGranted)
      .put("catalog_summary_grant_epoch", summaryGrantEpoch)
      .put("catalog_detail_granted", detailGranted)
      .put("catalog_detail_grant_epoch", detailGrantEpoch)

  companion object {
    const val SCHEMA = "rusty.kiosk.direct_operator.settings.v2"
    private val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")

    fun parseStrict(raw: String): DirectOperatorV2SettingsState? =
      runCatching {
          val root = DirectOperatorV2Json.parse(raw)
          require(
            root.keys().asSequence().toSet() ==
              setOf(
                "schema",
                "pairing_id",
                "key_epoch",
                "kdf_salt",
                "catalog_summary_granted",
                "catalog_summary_grant_epoch",
                "catalog_detail_granted",
                "catalog_detail_grant_epoch",
              )
          )
          require(root.get("schema") == SCHEMA)
          require(listOf("catalog_summary_granted", "catalog_detail_granted").all { root.get(it) is Boolean })
          require(listOf("pairing_id", "key_epoch", "kdf_salt", "catalog_summary_grant_epoch", "catalog_detail_grant_epoch").all { root.get(it) is String })
          DirectOperatorV2SettingsState(
            pairingId = root.getString("pairing_id"),
            keyEpoch = root.getString("key_epoch"),
            kdfSalt = root.getString("kdf_salt"),
            summaryGranted = root.getBoolean("catalog_summary_granted"),
            summaryGrantEpoch = root.getString("catalog_summary_grant_epoch"),
            detailGranted = root.getBoolean("catalog_detail_granted"),
            detailGrantEpoch = root.getString("catalog_detail_grant_epoch"),
          )
        }
        .getOrNull()
  }
}
