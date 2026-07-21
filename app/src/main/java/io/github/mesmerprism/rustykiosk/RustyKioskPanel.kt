package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun RustyKioskPanel(
  state: KioskUiState,
  onSearchChanged: (String) -> Unit,
  onTagSelected: (String?) -> Unit,
  onAppSelected: (String) -> Unit,
  onRefresh: () -> Unit,
  onAddTag: (String) -> Unit,
  onRemoveTag: (String) -> Unit,
  onNormalLaunch: () -> Unit,
  onKioskLaunch: () -> Unit,
  onOpenUserControls: () -> Unit,
  onCloseUserControls: () -> Unit,
  onCheckSetupHelper: () -> Unit,
  onRequestWifiAdb: () -> Unit,
  onEnableWifiAfterBoot: () -> Unit,
  onDisableWifiAfterBoot: () -> Unit,
  onDisableWifiAdb: () -> Unit,
  onEnableAccessibility: () -> Unit,
  onDisableAccessibility: () -> Unit,
  onUseNaturalPassthrough: () -> Unit,
  onUseContourPassthrough: () -> Unit,
  onExitToMetaHome: () -> Unit,
) {
  Surface(
    modifier =
      Modifier.fillMaxSize().testTag(RustyKioskPanelControls.ROOT),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text("Rusty Kiosk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
          Text(
            state.statusLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(8.dp)) {
          Text("Reload")
        }
      }

      UserControlStatusBar(
        controls = state.userControls,
        controlsOpen = state.userControlsOpen,
        onOpenUserControls = onOpenUserControls,
        onCloseUserControls = onCloseUserControls,
      )

      if (state.userControlsOpen) {
        UserControlCenter(
          controls = state.userControls,
          onCheckSetupHelper = onCheckSetupHelper,
          onRequestWifiAdb = onRequestWifiAdb,
          onEnableWifiAfterBoot = onEnableWifiAfterBoot,
          onDisableWifiAfterBoot = onDisableWifiAfterBoot,
          onDisableWifiAdb = onDisableWifiAdb,
          onEnableAccessibility = onEnableAccessibility,
          onDisableAccessibility = onDisableAccessibility,
          onUseNaturalPassthrough = onUseNaturalPassthrough,
          onUseContourPassthrough = onUseContourPassthrough,
          onExitToMetaHome = onExitToMetaHome,
          modifier = Modifier.fillMaxWidth().weight(1.0f),
        )
      } else {
        QuestImeTextField(
          value = state.searchQuery,
          label = "Search apps, packages, or tags",
          onValueChange = onSearchChanged,
          onSubmit = {},
          focusRequest = state.searchFocusRequest,
          imeAction = EditorInfo.IME_ACTION_SEARCH,
          controlName = "search",
          modifier =
            Modifier.fillMaxWidth().height(76.dp)
              .testTag(RustyKioskPanelControls.SEARCH),
        )

        LazyRow(
          modifier = Modifier.testTag(RustyKioskPanelControls.TAG_FILTERS),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            TagFilterButton(
              label = "All apps",
              selected = state.selectedTag == null,
              onClick = { onTagSelected(null) },
            )
          }
          items(state.tags, key = { it }) { tag ->
            TagFilterButton(
              label = tag,
              selected = state.selectedTag == tag,
              onClick = { onTagSelected(tag) },
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().weight(1.0f),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          AppList(
            entries = state.visibleEntries,
            selectedKey = state.selectedKey,
            onSelected = onAppSelected,
            modifier = Modifier.weight(0.46f),
          )
          AppDetails(
            entry = state.selectedEntry,
            guardEnabled = state.guardEnabled,
            tagFocusRequest = state.tagFocusRequest,
            onAddTag = onAddTag,
            onRemoveTag = onRemoveTag,
            onNormalLaunch = onNormalLaunch,
            onKioskLaunch = onKioskLaunch,
            onOpenUserControls = onOpenUserControls,
            modifier = Modifier.weight(0.54f),
          )
        }
      }

      Text(
        state.tagFilePath,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun TagFilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
  if (selected) {
    Button(onClick = onClick, shape = RoundedCornerShape(8.dp)) { Text(label) }
  } else {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(8.dp)) { Text(label) }
  }
}

@Composable
private fun AppList(
  entries: List<CatalogEntry>,
  selectedKey: String?,
  onSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxHeight().testTag(RustyKioskPanelControls.APP_LIST)
  ) {
    Text("Apps (${entries.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    if (entries.isEmpty()) {
      Text("No apps match the current search and tag filter.")
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().border(1.dp, Divider, RoundedCornerShape(8.dp)),
      ) {
        items(entries, key = { it.key }) { entry ->
          AppListRow(
            entry = entry,
            selected = entry.key == selectedKey,
            onClick = { onSelected(entry.key) },
          )
          HorizontalDivider(color = Divider)
        }
      }
    }
  }
}

@Composable
private fun AppListRow(entry: CatalogEntry, selected: Boolean, onClick: () -> Unit) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(if (selected) SelectedSurface else Color.Transparent)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        entry.label,
        modifier = Modifier.weight(1.0f),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        if (entry.installed) "Installed" else "Not installed",
        style = MaterialTheme.typography.bodySmall,
        color = if (entry.installed) InstalledColor else MissingColor,
      )
    }
    Text(
      entry.packageName ?: "Name-only tag-file entry",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (entry.tags.isNotEmpty()) {
      Text(
        entry.tags.sorted().joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun AppDetails(
  entry: CatalogEntry?,
  guardEnabled: Boolean,
  tagFocusRequest: Long,
  onAddTag: (String) -> Unit,
  onRemoveTag: (String) -> Unit,
  onNormalLaunch: () -> Unit,
  onKioskLaunch: () -> Unit,
  onOpenUserControls: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var tagInput by remember(entry?.key) { mutableStateOf("") }
  Column(
    modifier =
      modifier.fillMaxHeight()
        .testTag(RustyKioskPanelControls.APP_DETAILS)
        .border(1.dp, Divider, RoundedCornerShape(8.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (entry == null) {
      Text("Select an app")
      return@Column
    }

    Text(entry.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
      entry.packageName ?: "No package supplied",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      entry.statusLabel,
      color = if (entry.installed) InstalledColor else MissingColor,
      fontWeight = FontWeight.SemiBold,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      QuestImeTextField(
        value = tagInput,
        label = "Add tag",
        onValueChange = { tagInput = it },
        onSubmit = {
          if (tagInput.isNotBlank()) {
            onAddTag(tagInput)
            tagInput = ""
          }
        },
        focusRequest = tagFocusRequest,
        imeAction = EditorInfo.IME_ACTION_DONE,
        controlName = "tag-editor",
        modifier = Modifier.weight(1.0f).height(76.dp),
      )
      Button(
        onClick = {
          onAddTag(tagInput)
          tagInput = ""
        },
        enabled = tagInput.isNotBlank(),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text("Add")
      }
    }

    if (entry.tags.isNotEmpty()) {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entry.tags.sorted(), key = { it }) { tag ->
          OutlinedButton(
            onClick = { onRemoveTag(tag) },
            shape = RoundedCornerShape(8.dp),
            contentPadding = ButtonDefaults.TextButtonContentPadding,
          ) {
            Text("$tag ×")
          }
        }
      }
    }

    HorizontalDivider(color = Divider)

    Button(
      onClick = onNormalLaunch,
      enabled = entry.launchable,
      modifier =
        Modifier.fillMaxWidth().testTag(RustyKioskPanelControls.NORMAL_LAUNCH),
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
      shape = RoundedCornerShape(8.dp),
    ) {
      Text("Normal launch")
    }
    Button(
      onClick = onKioskLaunch,
      enabled = entry.launchable && guardEnabled,
      modifier =
        Modifier.fillMaxWidth().testTag(RustyKioskPanelControls.KIOSK_LAUNCH),
      shape = RoundedCornerShape(8.dp),
    ) {
      Text("Kiosk launch")
    }

    if (guardEnabled) {
      Text(
        "Soft guard ready. Home #1 and #2 restore the app; Home #3 within five seconds returns here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(
        "Kiosk launch needs the opt-in Accessibility service.",
        style = MaterialTheme.typography.bodySmall,
        color = MissingColor,
      )
      OutlinedButton(
        onClick = onOpenUserControls,
        modifier =
          Modifier.fillMaxWidth()
            .testTag(RustyKioskPanelControls.USER_CONTROLS_OPEN),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text("Manage user controls")
      }
    }

    Text(
      "The guard is inactive in Rusty Kiosk. Press Home here to open Meta Home normally.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun QuestImeTextField(
  value: String,
  label: String,
  onValueChange: (String) -> Unit,
  onSubmit: () -> Unit,
  focusRequest: Long,
  imeAction: Int,
  controlName: String,
  modifier: Modifier = Modifier,
) {
  val latestOnValueChange = rememberUpdatedState(onValueChange)
  val latestOnSubmit = rememberUpdatedState(onSubmit)
  val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
  val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
  val fillColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
  val borderColor = MaterialTheme.colorScheme.outline.toArgb()
  var field by remember(controlName) { mutableStateOf<QuestImeEditText?>(null) }

  LaunchedEffect(focusRequest, field) {
    if (focusRequest > 0L) {
      field?.let { editText -> focusAndRequestQuestKeyboard(editText, controlName) }
    }
  }

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(
      label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
    AndroidView(
      modifier = Modifier.fillMaxWidth().weight(1.0f),
      factory = { context ->
        QuestImeEditText(context).apply {
          inputType = InputType.TYPE_CLASS_TEXT
          setSingleLine(true)
          isFocusable = true
          isFocusableInTouchMode = true
          showSoftInputOnFocus = true
          isCursorVisible = true
          gravity = Gravity.CENTER_VERTICAL
          this.imeOptions = imeAction or EditorInfo.IME_FLAG_NO_EXTRACT_UI
          setText(value)
          setSelection(text.length)
          addTextChangedListener(
            object : TextWatcher {
              override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
              ) = Unit

              override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
              ) {
                if (!applyingModelValue) {
                  latestOnValueChange.value(text?.toString().orEmpty())
                }
              }

              override fun afterTextChanged(text: Editable?) = Unit
            }
          )
          setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) requestQuestKeyboard(this, controlName)
          }
          setOnClickListener { requestQuestKeyboard(this, controlName) }
          setOnEditorActionListener { _, actionId, event ->
            val submitted =
              actionId == imeAction ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (submitted) latestOnSubmit.value()
            submitted
          }
          field = this
        }
      },
      update = { editText ->
        editText.setTextColor(textColor)
        editText.setHintTextColor(hintColor)
        editText.setPadding(editText.dp(12), 0, editText.dp(12), 0)
        editText.background =
          GradientDrawable().apply {
            cornerRadius = editText.dp(8).toFloat()
            setColor(fillColor)
            setStroke(editText.dp(1), borderColor)
          }
        if (editText.text.toString() != value) {
          editText.applyingModelValue = true
          editText.setText(value)
          editText.setSelection(editText.text.length)
          editText.applyingModelValue = false
        }
      },
    )
  }
}

private class QuestImeEditText(context: Context) : EditText(context) {
  var applyingModelValue: Boolean = false

  fun dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
}

private fun focusAndRequestQuestKeyboard(field: EditText, controlName: String) {
  field.post {
    if (!field.hasFocus()) field.requestFocus()
    requestQuestKeyboard(field, controlName)
  }
}

private fun requestQuestKeyboard(field: EditText, controlName: String) {
  field.post {
    val accepted =
      (field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    Log.i(
      QUEST_IME_LOG_TAG,
      "status=keyboard-requested control=$controlName showSoftInputAccepted=$accepted textLogged=false",
    )
  }
}

private const val QUEST_IME_LOG_TAG = "RustyKioskIme"

@Composable
private fun UserControlStatusBar(
  controls: UserControlState,
  controlsOpen: Boolean,
  onOpenUserControls: () -> Unit,
  onCloseUserControls: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .testTag(RustyKioskPanelControls.USER_CONTROL_STATUS)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StatusBadge("Setup", controls.setupStatusLabel, controls.setupHelperReady)
    StatusBadge(
      "Passthrough",
      controls.passthroughStatusLabel,
      controls.systemPassthroughEnabled && controls.passthroughLutApplied,
    )
    StatusBadge("Wi-Fi ADB", controls.wifiStatusLabel, controls.wirelessDebuggingEnabled)
    StatusBadge("Accessibility", controls.accessibilityStatusLabel, controls.accessibilityEnabled)
    Spacer(Modifier.weight(1.0f))
    OutlinedButton(
      onClick = if (controlsOpen) onCloseUserControls else onOpenUserControls,
      modifier = Modifier.testTag(RustyKioskPanelControls.USER_CONTROLS_OPEN),
      shape = RoundedCornerShape(8.dp),
    ) {
      Text(if (controlsOpen) "Back to apps" else "User controls")
    }
  }
}

@Composable
private fun StatusBadge(label: String, value: String, positive: Boolean) {
  Row(
    modifier =
      Modifier.border(1.dp, Divider, RoundedCornerShape(8.dp))
        .padding(horizontal = 9.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Text(
      value,
      style = MaterialTheme.typography.labelMedium,
      color = if (positive) InstalledColor else MissingColor,
    )
  }
}

@Composable
private fun UserControlCenter(
  controls: UserControlState,
  onCheckSetupHelper: () -> Unit,
  onRequestWifiAdb: () -> Unit,
  onEnableWifiAfterBoot: () -> Unit,
  onDisableWifiAfterBoot: () -> Unit,
  onDisableWifiAdb: () -> Unit,
  onEnableAccessibility: () -> Unit,
  onDisableAccessibility: () -> Unit,
  onUseNaturalPassthrough: () -> Unit,
  onUseContourPassthrough: () -> Unit,
  onExitToMetaHome: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.testTag(RustyKioskPanelControls.USER_CONTROLS),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("Transparent, reversible setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
      controls.message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .testTag(RustyKioskPanelControls.PASSTHROUGH_CONTROLS)
          .border(1.dp, Divider, RoundedCornerShape(8.dp))
          .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        "Passthrough appearance",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      ControlFact("System passthrough", controls.passthroughStatusLabel)
      Text(
        "Natural is the default. Contour LUT uses hard color bands to reveal contours; it is not camera edge detection.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onUseNaturalPassthrough,
          enabled =
            controls.passthroughStyle != KioskPassthroughStyle.NATURAL ||
              !controls.systemPassthroughEnabled || !controls.passthroughLutApplied,
          modifier = Modifier.weight(1.0f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text("Natural")
        }
        OutlinedButton(
          onClick = onUseContourPassthrough,
          enabled =
            controls.passthroughStyle != KioskPassthroughStyle.CONTOUR_LUT ||
              !controls.systemPassthroughEnabled || !controls.passthroughLutApplied,
          modifier = Modifier.weight(1.0f),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text("Contour LUT")
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth().weight(1.0f),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      ControlCard("Wi-Fi ADB · explicit opt-in", Modifier.weight(1.0f)) {
        Text(
          "The dedicated setup helper exposes only fixed Rusty Kiosk operations. USB-C provisions it once; no terminal app is needed.",
          style = MaterialTheme.typography.bodySmall,
        )
        ControlFact("Wireless Debugging", if (controls.wirelessDebuggingEnabled) "On" else "Off")
        ControlFact("Setup helper", controls.setupStatusLabel)
        ControlFact("Request after restart", if (controls.requestWifiAfterBoot) "On" else "Off")
        Spacer(Modifier.weight(1.0f))
        OutlinedButton(
          onClick = onCheckSetupHelper,
          enabled = controls.setupHelperInstalled && controls.operationInProgress == null,
          modifier = Modifier.fillMaxWidth().testTag(RustyKioskPanelControls.WIFI_ADB_CONTROLS),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text("Refresh setup status")
        }
        Button(
          onClick = onRequestWifiAdb,
          enabled = controls.setupHelperReady && controls.operationInProgress == null,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text("Request Wi-Fi ADB")
        }
        OutlinedButton(
          onClick =
            if (controls.requestWifiAfterBoot) onDisableWifiAfterBoot else onEnableWifiAfterBoot,
          enabled = controls.setupHelperReady && controls.operationInProgress == null,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(
            if (controls.requestWifiAfterBoot) "Stop asking after restart" else
              "Ask after every restart"
          )
        }
        OutlinedButton(
          onClick = onDisableWifiAdb,
          enabled =
            controls.setupHelperReady && controls.wirelessDebuggingEnabled &&
              controls.operationInProgress == null,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text("Disable Wi-Fi ADB")
        }
      }

      ControlCard("Accessibility · explicit opt-in", Modifier.weight(1.0f)) {
        Text(
          "Enables the soft guard for kiosk launches only. It is disarmed while this Rusty Kiosk panel is visible.",
          style = MaterialTheme.typography.bodySmall,
        )
        ControlFact("Service", controls.accessibilityStatusLabel)
        ControlFact("Inside Rusty Kiosk", "Guard inactive")
        Text(
          "Home #1 and #2 restore a kiosk-launched app. Home #3 returns here. Home from here opens Meta Home.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1.0f))
        if (controls.accessibilityEnabled) {
          OutlinedButton(
            onClick = onDisableAccessibility,
            enabled = controls.operationInProgress == null,
            modifier =
              Modifier.fillMaxWidth().testTag(RustyKioskPanelControls.ACCESSIBILITY_TOGGLE),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text("Disable Accessibility")
          }
        } else {
          Button(
            onClick = onEnableAccessibility,
            enabled =
              controls.setupHelperReady &&
                controls.operationInProgress == null,
            modifier =
              Modifier.fillMaxWidth().testTag(RustyKioskPanelControls.ACCESSIBILITY_TOGGLE),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text("Enable Accessibility")
          }
        }
        HorizontalDivider(color = Divider)
        Text(
          "You can always leave Rusty Kiosk. Exiting disarms any pending launch guard.",
          style = MaterialTheme.typography.bodySmall,
        )
        Button(
          onClick = onExitToMetaHome,
          modifier = Modifier.fillMaxWidth().testTag(RustyKioskPanelControls.META_HOME_EXIT),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text("Exit to Meta Home")
        }
      }
    }
  }
}

@Composable
private fun ControlCard(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier =
      modifier.fillMaxHeight().border(1.dp, Divider, RoundedCornerShape(8.dp)).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    content()
  }
}

@Composable
private fun ControlFact(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
  }
}

private val Divider = Color(0xFF4A4945)
private val SelectedSurface = Color(0xFF34302A)
private val InstalledColor = Color(0xFFA9D6A5)
private val MissingColor = Color(0xFFFFB4A8)
