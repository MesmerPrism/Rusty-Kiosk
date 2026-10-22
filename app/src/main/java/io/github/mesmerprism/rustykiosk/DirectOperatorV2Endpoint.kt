package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

internal data class DirectOperatorV2HttpResponse(val status: Int, val body: ByteArray)

/**
 * Production wire adapter shared by NanoHTTPD and the cross-language conformance host.
 * Each stage re-reads the profile under the caller's transition/grant lock. Collection happens
 * outside that lock; revocation during PackageManager readback prevents sealing its result.
 */
internal class DirectOperatorV2Endpoint(
  private val sessions: DirectOperatorV2SessionManager,
  private val profile: () -> DirectOperatorV2Profile,
  private val pairingCode: () -> String,
  private val snapshot: (String) -> KioskCatalogSnapshot,
  private val currentGeneration: () -> Boolean,
  private val locked: ((() -> DirectOperatorV2HttpResponse) -> DirectOperatorV2HttpResponse),
) {
  fun handle(method: String, path: String, body: ByteArray): DirectOperatorV2HttpResponse {
    if (body.size > MAX_BODY_BYTES) return rejected(413)
    return runCatching {
      when {
        method == "GET" && path == DirectOperatorV2RoutePolicy.CONTRACT_PATH -> locked {
          require(currentGeneration() && body.isEmpty())
          response(DirectOperatorV2RoutePolicy.publicContract(profile()))
        }
        method == "POST" && path == DirectOperatorV2RoutePolicy.SESSION_OPEN_PATH -> locked {
          require(currentGeneration())
          val request = DirectOperatorV2SessionOpenRequest.parseStrict(utf8(body))
          response(sessions.open(request, profile(), pairingCode()).toJson())
        }
        method == "POST" && path == DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH -> {
          val envelope = DirectOperatorV2RequestEnvelope.parseStrict(utf8(body))
          var opened: DirectOperatorV2OpenedRequest? = null
          var epoch: String? = null
          locked {
            require(currentGeneration())
            val current = profile()
            opened = sessions.openRequest(method, path, envelope, current)
            epoch = current.ownerEpoch
            response(JSONObject())
          }
          val request = requireNotNull(opened)
          try {
            locked {
              require(currentGeneration())
              sessions.validateForDispatch(request, profile())
              val payload = DirectOperatorV2Json.parse(utf8(request.plaintext))
              require(payload.keys().asSequence().toSet() == setOf("schema"))
              require(payload.get("schema") == CATALOG_REQUEST_SCHEMA)
              response(JSONObject())
            }
            val collected = snapshot(requireNotNull(epoch))
            locked {
              require(currentGeneration())
              sessions.validateForDispatch(request, profile())
              val bytes = collected.toSummaryJson().toString().toByteArray(StandardCharsets.UTF_8)
              try {
                response(sessions.sealResponse(request, bytes, profile()).toJson())
              } finally {
                bytes.fill(0)
              }
            }
          } finally {
            request.plaintext.fill(0)
          }
        }
        else -> rejected(404)
      }
    }.getOrElse { rejected(401) }
  }

  private fun response(json: JSONObject) =
    DirectOperatorV2HttpResponse(200, json.toString().toByteArray(StandardCharsets.UTF_8))

  private fun rejected(status: Int) = DirectOperatorV2HttpResponse(
    status,
    "{\"accepted\":false,\"message\":\"Encrypted catalogue request rejected.\"}"
      .toByteArray(StandardCharsets.UTF_8),
  )

  private fun utf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

  companion object {
    const val MAX_BODY_BYTES = 2 * 1024 * 1024
    const val CATALOG_REQUEST_SCHEMA = "rusty.kiosk.catalog_request.v2"
  }
}
