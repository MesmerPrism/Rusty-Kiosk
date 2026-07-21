package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorBridgeAuthTest {
  @Test
  fun requestSignatureMatchesPublicCrossClientVector() {
    val signature =
      OperatorBridgeAuth.sign(
        pairingKey = KEY,
        method = "POST",
        requestTarget = "/v1/kiosk/invoke",
        requestId = REQUEST_ID,
        timestampSeconds = TIMESTAMP,
        contentSha256 = EMPTY_SHA,
      )

    assertEquals("f35ef975435590bf944f26e5055267d3615c6f1916f4a8b3986389900b588989", signature)
  }

  @Test
  fun responseSignatureMatchesPublicCrossClientVector() {
    assertEquals(
      "0a4418fe4677bfac1a12047ef8ea842e3ebaca7e758b8a190a4de009eaf9babb",
      OperatorBridgeAuth.signResponse(KEY, REQUEST_ID, 200, EMPTY_SHA),
    )
  }

  @Test
  fun expiredAndTamperedEnvelopesAreRejected() {
    val headers =
      OperatorBridgeAuthHeaders(
        requestId = REQUEST_ID,
        timestampSeconds = TIMESTAMP,
        contentSha256 = EMPTY_SHA,
        signature = OperatorBridgeAuth.sign(KEY, "GET", "/v1/status", REQUEST_ID, TIMESTAMP, EMPTY_SHA),
      )

    assertTrue(
      OperatorBridgeAuth.verify(KEY, "GET", "/v1/status", byteArrayOf(1), headers, TIMESTAMP).isFailure
    )
    assertTrue(
      OperatorBridgeAuth.verify(KEY, "GET", "/v1/status", byteArrayOf(), headers, TIMESTAMP + 91).isFailure
    )
  }

  private companion object {
    const val KEY = "0123-4567-89AB-CDEF"
    const val REQUEST_ID = "http_12345678"
    const val TIMESTAMP = 1_784_650_000L
    const val EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
