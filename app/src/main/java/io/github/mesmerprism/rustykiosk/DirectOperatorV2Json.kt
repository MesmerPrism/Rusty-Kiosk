package io.github.mesmerprism.rustykiosk

import org.json.JSONObject

/** Validate RFC JSON before Android's permissive JSONObject parser can normalize bad input. */
internal object DirectOperatorV2Json {
  fun parse(text: String): JSONObject {
    require(text.length <= DirectOperatorV2Endpoint.MAX_BODY_BYTES)
    Scanner(text).validate()
    return JSONObject(text)
  }

  private class Scanner(private val text: String) {
    private var offset = 0

    fun validate() {
      whitespace()
      require(peek() == '{')
      value(0)
      whitespace()
      require(offset == text.length) { "Trailing JSON content." }
    }

    private fun value(depth: Int) {
      require(depth <= 8) { "JSON nesting exceeds its fixed bound." }
      whitespace()
      when (peek()) {
        '{' -> {
          offset++
          whitespace()
          val names = HashSet<String>()
          if (peek() != '}') {
            do {
              whitespace()
              val name = string()
              require(names.add(name)) { "Duplicate JSON member." }
              whitespace()
              require(take() == ':')
              value(depth + 1)
              whitespace()
            } while (consume(','))
          }
          require(take() == '}')
        }
        '[' -> {
          offset++
          whitespace()
          if (peek() != ']') {
            do { value(depth + 1); whitespace() } while (consume(','))
          }
          require(take() == ']')
        }
        '"' -> string()
        't' -> literal("true")
        'f' -> literal("false")
        'n' -> literal("null")
        else -> number()
      }
    }

    private fun string(): String {
      require(take() == '"')
      val result = StringBuilder()
      while (true) {
        val next = take()
        if (next == '"') return result.toString()
        require(next >= ' ') { "Unescaped JSON control character." }
        if (next != '\\') {
          result.append(next)
          continue
        }
        result.append(when (val escaped = take()) {
          '"', '\\', '/' -> escaped
          'b' -> '\b'
          'f' -> '\u000c'
          'n' -> '\n'
          'r' -> '\r'
          't' -> '\t'
          'u' -> {
            require(offset + 4 <= text.length)
            val hex = text.substring(offset, offset + 4)
            require(hex.all { it in "0123456789abcdefABCDEF" })
            offset += 4
            hex.toInt(16).toChar()
          }
          else -> error("Invalid JSON escape.")
        })
      }
    }

    private fun number() {
      val start = offset
      consume('-')
      if (!consume('0')) {
        require(peek() in '1'..'9')
        while (peek() in '0'..'9') offset++
      }
      if (consume('.')) {
        require(peek() in '0'..'9')
        while (peek() in '0'..'9') offset++
      }
      if (peek() == 'e' || peek() == 'E') {
        offset++
        if (peek() == '+' || peek() == '-') offset++
        require(peek() in '0'..'9')
        while (peek() in '0'..'9') offset++
      }
      require(offset > start)
    }

    private fun literal(value: String) {
      require(text.startsWith(value, offset))
      offset += value.length
    }

    private fun whitespace() {
      while (peek() in listOf(' ', '\t', '\r', '\n')) offset++
    }

    private fun consume(value: Char): Boolean = (peek() == value).also { if (it) offset++ }
    private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
    private fun take(): Char = text.getOrNull(offset++) ?: error("Truncated JSON.")
  }
}
