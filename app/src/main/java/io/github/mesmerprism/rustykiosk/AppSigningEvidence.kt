package io.github.mesmerprism.rustykiosk

internal data class AppSigningEvidence(
  val currentSignerSha256: List<String>,
  val signingLineageSha256: List<String> = emptyList(),
  val lineageVerifiedByAndroid: Boolean = false,
) {
  init {
    require(currentSignerSha256.isNotEmpty()) { "At least one current signing certificate is required." }
    require((currentSignerSha256 + signingLineageSha256).all(SHA256::matches)) {
      "Signing evidence must contain only SHA-256 certificate digests."
    }
    require(currentSignerSha256.distinct().size == currentSignerSha256.size) {
      "Current signing evidence contains duplicates."
    }
    require(lineageVerifiedByAndroid == signingLineageSha256.isNotEmpty()) {
      "A signing lineage is accepted only with Android verification."
    }
    if (lineageVerifiedByAndroid) {
      require(
        currentSignerSha256.size == 1 &&
          signingLineageSha256.distinct().size == signingLineageSha256.size &&
          signingLineageSha256.last() == currentSignerSha256.single()
      ) {
        "Verified signing lineage must be ordered from original through the current signer."
      }
    }
  }

  val canonicalIdentitySigners: List<String>
    get() =
      if (lineageVerifiedByAndroid) {
        listOf(signingLineageSha256.first())
      } else {
        currentSignerSha256.sorted()
      }

  val lineageDigest: String
    get() =
      catalogDigest(
        "rusty.kiosk.signing_lineage.v1",
        lineageVerifiedByAndroid.toString(),
        signingLineageSha256.joinToString(","),
        currentSignerSha256.sorted().joinToString(","),
      )

  companion object {
    private val SHA256 = Regex("^[0-9a-f]{64}$")
  }
}

