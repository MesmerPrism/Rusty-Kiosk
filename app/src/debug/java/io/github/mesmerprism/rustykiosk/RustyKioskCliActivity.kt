package io.github.mesmerprism.rustykiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import java.nio.charset.StandardCharsets

/** ADB-shell-only debug bridge for typed, user-action-equivalent commands. */
class RustyKioskCliActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val encodedValue = intent.getStringExtra(RustyKioskCliProtocol.EXTRA_VALUE_BASE64)
    val decodedValue =
      encodedValue?.let { value ->
        runCatching {
            Base64.decode(value, Base64.NO_WRAP).toString(StandardCharsets.UTF_8)
          }
          .getOrElse {
            finish()
            return
          }
      }
    val parsed =
      RustyKioskCliProtocol.parse(
        requestId = intent.getStringExtra(RustyKioskCliProtocol.EXTRA_REQUEST_ID),
        command = intent.getStringExtra(RustyKioskCliProtocol.EXTRA_COMMAND),
        value = decodedValue,
      )
    parsed.onSuccess { request ->
      if (RustyKioskCliStore(this).enqueue(request)) {
        startActivity(
          Intent(this, RustyKioskActivity::class.java)
            .putExtra(RustyKioskCliProtocol.EXTRA_PENDING_REQUEST_ID, request.requestId)
            .addFlags(
              Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
      }
    }
    finish()
  }
}
