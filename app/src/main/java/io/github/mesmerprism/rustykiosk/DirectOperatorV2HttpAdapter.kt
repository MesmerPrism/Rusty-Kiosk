package io.github.mesmerprism.rustykiosk

import java.io.InputStream

/** Fixed-length HTTP framing for the production v2 routes; independent of Android and sockets. */
internal class DirectOperatorV2HttpAdapter(private val endpoint: DirectOperatorV2Endpoint) {
  fun serve(method: String, path: String, query: String?, headers: Map<String, String>, input: InputStream): DirectOperatorV2HttpResponse =
    runCatching {
      require(query.isNullOrEmpty())
      require(headers["transfer-encoding"] == null)
      val rawLength = headers["content-length"]
      require(rawLength == null || Regex("^(0|[1-9][0-9]{0,9})$").matches(rawLength))
      val length = rawLength?.toLong() ?: if (method == "GET") 0L else error("Fixed Content-Length required.")
      require(length in 0..DirectOperatorV2Endpoint.MAX_BODY_BYTES.toLong())
      if (method == "GET") require(length == 0L) else {
        require(headers["content-type"]?.substringBefore(';')?.trim() == "application/json")
      }
      val body = ByteArray(length.toInt())
      var offset = 0
      while (offset < body.size) {
        val count = input.read(body, offset, body.size - offset)
        require(count > 0) { "Truncated encrypted request." }
        offset += count
      }
      endpoint.handle(method, path, body)
    }.getOrElse {
      DirectOperatorV2HttpResponse(400, "{\"accepted\":false}".toByteArray(Charsets.UTF_8))
    }
}
