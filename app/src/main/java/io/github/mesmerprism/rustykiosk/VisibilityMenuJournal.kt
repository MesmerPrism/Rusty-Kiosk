package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Fixed-size app-owned evidence. Prepared conditional tickets are never restored after restart. */
internal class VisibilityMenuJournal(context: Context, private val config: GuardConfig) {
  private val file = AtomicFile(File(context.filesDir, FILE_NAME))
  private val rows = ArrayDeque<JSONObject>()
  private val prepared = linkedSetOf<String>()
  @Volatile private var snapshot = JSONObject().put("phase", "preparing").toString()

  fun prepare(): List<String> {
    val ids = List(12) { UUID.randomUUID().toString() }
    prepared.addAll(ids)
    appendMemory(JSONObject().put("phase", "PREPARED_CONDITIONAL_TICKETS"))
    commit()
    return ids
  }

  fun record(row: JSONObject) { appendMemory(row); commit() }

  /** One durable transaction records the known return and prepares its replacement slot. */
  fun returned(id: String, row: JSONObject): String {
    check(prepared.remove(id))
    val replacement = UUID.randomUUID().toString()
    prepared.add(replacement)
    appendMemory(row)
    commit()
    return replacement
  }

  private fun appendMemory(row: JSONObject) {
    rows.addLast(row)
    while (rows.size > 64) rows.removeFirst()
  }

  private fun commit() {
    val data = JSONObject()
      .put("schema", 1).put("generation", config.generation)
      .put("target_package", config.target.packageName)
      .put("target_activity", config.target.activityName)
      .put("fixed_action", VisibilityMenuBridge.ACTION)
      .put("condition", "same-live-generation-qualified-false-to-true-one-original")
      .put("restored_tickets_executable", false)
      .put("prepared_conditional_tickets", JSONArray(prepared.toList()))
      .put("rows", JSONArray(rows.toList()))
    val bytes = data.toString().toByteArray(Charsets.UTF_8)
    val stream = file.startWrite()
    try { stream.write(bytes); file.finishWrite(stream) }
    catch (failure: Throwable) { file.failWrite(stream); throw failure }
    snapshot = data.toString()
  }

  fun snapshot(): JSONObject = JSONObject(snapshot)

  companion object { const val FILE_NAME = "visibility-menu-journal.json" }
}
