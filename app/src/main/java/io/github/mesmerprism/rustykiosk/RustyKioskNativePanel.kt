package io.github.mesmerprism.rustykiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings

/**
 * Production Spatial SDK panel. Text input deliberately stays in the native Android View tree:
 * Horizon currently accepts the system keyboard request from this LayoutXML panel boundary but
 * can reject the same request from an EditText nested inside a Compose panel.
 */
internal class RustyKioskNativePanel(
  private val context: Context,
  private val onSearchChanged: (String) -> Unit,
  private val onTagSelected: (String?) -> Unit,
  private val onAppSelected: (String) -> Unit,
  private val onRefresh: () -> Unit,
  private val onAddTag: (String) -> Unit,
  private val onRemoveTag: (String) -> Unit,
  private val onLaunchRequirementSelected: (AppLaunchRequirement) -> Unit,
  private val onCancelPendingRequirementLaunch: () -> Unit,
  private val onNormalLaunch: () -> Unit,
  private val onKioskLaunch: () -> Unit,
  private val onLaunchOption: (String) -> Unit,
  private val onOpenUserControls: () -> Unit,
  private val onCloseUserControls: () -> Unit,
  private val onCheckSetupHelper: () -> Unit,
  private val onRequestWifiAdb: () -> Unit,
  private val onEnableWifiAfterBoot: () -> Unit,
  private val onDisableWifiAfterBoot: () -> Unit,
  private val onDisableWifiAdb: () -> Unit,
  private val onEnableAccessibility: () -> Unit,
  private val onDisableAccessibility: () -> Unit,
  private val onUseNaturalPassthrough: () -> Unit,
  private val onUseContourPassthrough: () -> Unit,
  private val onToggleOperatorBridge: () -> Unit,
  private val onRotateOperatorBridgeCode: () -> Unit,
  private val onRequestInstallerPermission: () -> Unit,
  private val onExitToMetaHome: () -> Unit,
) {
  private var state = KioskUiState()
  private var rootFrame: FrameLayout? = null
  private var applyingSearchValue = false
  private var selectedDetailsKey: String? = null
  private var renderedSearchFocusRequest = 0L
  private var renderedTagFocusRequest = 0L

  private lateinit var statusLine: TextView
  private lateinit var controlStatus: Button
  private lateinit var catalogueSurface: LinearLayout
  private lateinit var controlsSurface: ScrollView
  private lateinit var searchField: EditText
  private lateinit var tagFilters: LinearLayout
  private lateinit var appCount: TextView
  private lateinit var appList: LinearLayout
  private lateinit var detailsTitle: TextView
  private lateinit var detailsPackage: TextView
  private lateinit var detailsStatus: TextView
  private lateinit var tagField: EditText
  private lateinit var addTagButton: Button
  private lateinit var selectedTags: LinearLayout
  private lateinit var requirementStatus: TextView
  private lateinit var requirementAnyButton: Button
  private lateinit var requirementWifiOnButton: Button
  private lateinit var requirementWifiOffButton: Button
  private lateinit var cancelPendingLaunchButton: Button
  private lateinit var launchOptionsStatus: TextView
  private lateinit var launchOptionsRow: LinearLayout
  private lateinit var normalLaunchButton: Button
  private lateinit var kioskLaunchButton: Button
  private lateinit var launchGuidance: TextView
  private lateinit var manageControlsButton: Button
  private lateinit var tagFilePath: TextView
  private lateinit var setupStatus: TextView
  private lateinit var wifiStatus: TextView
  private lateinit var wifiAfterBootStatus: TextView
  private lateinit var accessibilityStatus: TextView
  private lateinit var passthroughStatus: TextView
  private lateinit var passthroughMessage: TextView
  private lateinit var naturalPassthroughButton: Button
  private lateinit var contourPassthroughButton: Button
  private lateinit var controlMessage: TextView
  private lateinit var requestWifiButton: Button
  private lateinit var wifiAfterBootButton: Button
  private lateinit var accessibilityButton: Button
  private lateinit var operatorBridgeStatus: TextView
  private lateinit var operatorBridgeEndpoint: TextView
  private lateinit var operatorBridgeCode: TextView
  private lateinit var installerStatus: TextView
  private lateinit var operatorBridgeButton: Button
  private lateinit var rotateOperatorBridgeButton: Button
  private lateinit var pairingCodeVisibilityButton: Button
  private lateinit var installerPermissionButton: Button
  private var pairingCodeVisible = false
  private var renderedPairingCode = ""

  fun registration(): PanelRegistration =
    LayoutXMLPanelRegistration(
      R.id.kiosk_panel,
      layoutIdCreator = { R.layout.rusty_kiosk_panel },
      settingsCreator = {
        UIPanelSettings(
          shape =
            QuadShapeOptions(
              width = RustyKioskPanelGeometry.WIDTH_METERS,
              height = RustyKioskPanelGeometry.HEIGHT_METERS,
            ),
          display =
            DpDisplayOptions(
              width = RustyKioskPanelGeometry.WIDTH_DP.toFloat(),
              height = RustyKioskPanelGeometry.HEIGHT_DP.toFloat(),
              dpi = RustyKioskPanelGeometry.LAYOUT_DPI,
            ),
          rendering = UIPanelRenderOptions(PanelRenderMode.Layer()),
          style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaque),
          input = PanelInputOptions(),
        )
      },
      panelSetupWithRootView = { rootView, panel, _ ->
        rootFrame = rootView.findViewById(R.id.kiosk_panel)
        panel.layer?.setZIndex(PANEL_LAYER_Z_INDEX)
        buildViewTree(requireNotNull(rootFrame))
        render()
      },
    )

  fun update(newState: KioskUiState) {
    state = newState
    if (rootFrame != null) render()
  }

  fun release() {
    val token = rootFrame?.windowToken
    if (token != null) {
      (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .hideSoftInputFromWindow(token, 0)
    }
    rootFrame?.removeAllViews()
    rootFrame = null
  }

  private fun buildViewTree(root: FrameLayout) {
    root.removeAllViews()
    root.setBackgroundColor(COLOR_BACKGROUND)
    root.tag = RustyKioskPanelControls.ROOT

    val frame = column().apply {
      setPadding(dp(20), dp(18), dp(20), dp(14))
      background = rounded(COLOR_BACKGROUND, COLOR_BORDER, 0, 0)
    }
    root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))

    val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
    val titles = column()
    titles.addView(text("Rusty Kiosk", 25f, COLOR_TEXT, true))
    statusLine = text("", 13f, COLOR_MUTED)
    titles.addView(statusLine)
    header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
    header.addView(button("Reload", onRefresh), LinearLayout.LayoutParams(dp(116), dp(48)))
    frame.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

    controlStatus = button("", onOpenUserControls).apply {
      tag = RustyKioskPanelControls.USER_CONTROL_STATUS
    }
    frame.addView(controlStatus, margin(top = 10, bottom = 10, height = 46))

    catalogueSurface = column()
    frame.addView(catalogueSurface, LinearLayout.LayoutParams(MATCH, 0, 1f))
    buildCatalogueSurface(catalogueSurface)

    controlsSurface = ScrollView(context).apply {
      isFillViewport = true
      visibility = View.GONE
      tag = RustyKioskPanelControls.USER_CONTROLS
    }
    controlsSurface.addView(buildControlsSurface(), FrameLayout.LayoutParams(MATCH, WRAP))
    frame.addView(controlsSurface, LinearLayout.LayoutParams(MATCH, 0, 1f))

    tagFilePath = text("", 12f, COLOR_MUTED).apply { maxLines = 1 }
    frame.addView(tagFilePath, margin(top = 8))
  }

  private fun buildCatalogueSurface(parent: LinearLayout) {
    parent.addView(text("Search apps, packages, or tags", 13f, COLOR_MUTED))
    searchField = imeField(
      controlName = "search",
      imeAction = EditorInfo.IME_ACTION_SEARCH,
      onChanged = { value -> if (!applyingSearchValue) onSearchChanged(value) },
      onSubmit = {},
    ).apply { tag = RustyKioskPanelControls.SEARCH }
    parent.addView(searchField, margin(top = 3, bottom = 8, height = 52))

    val tagScroller = HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = false
      isFillViewport = true
    }
    tagFilters = row().apply { tag = RustyKioskPanelControls.TAG_FILTERS }
    tagScroller.addView(tagFilters, FrameLayout.LayoutParams(WRAP, MATCH))
    parent.addView(tagScroller, LinearLayout.LayoutParams(MATCH, dp(48)))

    val body = row()
    val listSide = column()
    appCount = text("Apps", 17f, COLOR_TEXT, true)
    listSide.addView(appCount, margin(bottom = 5))
    val appScroller = ScrollView(context).apply {
      isFillViewport = true
      background = rounded(COLOR_SURFACE, COLOR_BORDER, 1, 8)
    }
    appList = column().apply { tag = RustyKioskPanelControls.APP_LIST }
    appScroller.addView(appList, FrameLayout.LayoutParams(MATCH, WRAP))
    listSide.addView(appScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))
    body.addView(listSide, LinearLayout.LayoutParams(0, MATCH, 0.46f).apply { rightMargin = dp(12) })

    val details = column().apply {
      setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    val detailsScroller = ScrollView(context).apply {
      isFillViewport = true
      background = rounded(COLOR_SURFACE, COLOR_BORDER, 1, 8)
      tag = RustyKioskPanelControls.APP_DETAILS
    }
    detailsTitle = text("Select an app", 21f, COLOR_TEXT, true)
    detailsPackage = text("", 13f, COLOR_MUTED)
    detailsStatus = text("", 14f, COLOR_GOOD, true)
    details.addView(detailsTitle)
    details.addView(detailsPackage, margin(top = 3))
    details.addView(detailsStatus, margin(top = 5, bottom = 8))

    val tagRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
    tagField = imeField(
      controlName = "tag-editor",
      imeAction = EditorInfo.IME_ACTION_DONE,
      onChanged = { value -> addTagButton.isEnabled = value.isNotBlank() && state.selectedEntry != null },
      onSubmit = ::submitTag,
    )
    tagRow.addView(tagField, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) })
    addTagButton = button("Add", ::submitTag)
    tagRow.addView(addTagButton, LinearLayout.LayoutParams(dp(92), dp(52)))
    details.addView(text("Add tag", 13f, COLOR_MUTED), margin(top = 2))
    details.addView(tagRow, margin(top = 3, bottom = 5))

    val selectedTagScroller = HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = false
    }
    selectedTags = row()
    selectedTagScroller.addView(selectedTags, FrameLayout.LayoutParams(WRAP, MATCH))
    details.addView(selectedTagScroller, LinearLayout.LayoutParams(MATCH, dp(44)))

    requirementStatus = text("Launch requirement", 13f, COLOR_MUTED)
    details.addView(requirementStatus, margin(top = 4))
    val requirementRow = row().apply {
      gravity = Gravity.CENTER_VERTICAL
      tag = RustyKioskPanelControls.LAUNCH_REQUIREMENT
    }
    requirementAnyButton = button("Any", { onLaunchRequirementSelected(AppLaunchRequirement.ANY) })
    requirementWifiOnButton = button("Wi-Fi on", { onLaunchRequirementSelected(AppLaunchRequirement.WIFI_ON) })
    requirementWifiOffButton = button("Wi-Fi off", { onLaunchRequirementSelected(AppLaunchRequirement.WIFI_OFF) })
    requirementRow.addView(requirementAnyButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
    requirementRow.addView(requirementWifiOnButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
    requirementRow.addView(requirementWifiOffButton, LinearLayout.LayoutParams(0, dp(42), 1f))
    details.addView(requirementRow, margin(top = 3, height = 42))
    cancelPendingLaunchButton = button("Cancel pending launch", onCancelPendingRequirementLaunch)
    details.addView(cancelPendingLaunchButton, margin(top = 5, height = 42))

    launchOptionsStatus = text("", 13f, COLOR_MUTED).apply {
      tag = RustyKioskPanelControls.LAUNCH_OPTIONS
    }
    details.addView(launchOptionsStatus, margin(top = 5))
    val launchOptionsScroller = HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = false
    }
    launchOptionsRow = row()
    launchOptionsScroller.addView(launchOptionsRow, FrameLayout.LayoutParams(WRAP, MATCH))
    details.addView(launchOptionsScroller, margin(top = 3, height = 58))

    normalLaunchButton = button("Normal launch", onNormalLaunch).apply {
      tag = RustyKioskPanelControls.NORMAL_LAUNCH
    }
    kioskLaunchButton = button("Kiosk launch", onKioskLaunch, primary = true).apply {
      tag = RustyKioskPanelControls.KIOSK_LAUNCH
    }
    details.addView(normalLaunchButton, margin(top = 8, height = 48))
    details.addView(kioskLaunchButton, margin(top = 7, height = 48))
    launchGuidance = text("", 13f, COLOR_MUTED)
    details.addView(launchGuidance, margin(top = 8))
    manageControlsButton = button("Manage user controls", onOpenUserControls).apply {
      tag = RustyKioskPanelControls.USER_CONTROLS_OPEN
    }
    details.addView(manageControlsButton, margin(top = 8, height = 46))
    details.addView(
      text(
        "The guard is inactive in Rusty Kiosk. Press Home here to open Meta Home normally.",
        12f,
        COLOR_MUTED,
      ),
      margin(top = 8),
    )
    detailsScroller.addView(details, FrameLayout.LayoutParams(MATCH, WRAP))
    body.addView(detailsScroller, LinearLayout.LayoutParams(0, MATCH, 0.54f))
    parent.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
  }

  private fun buildControlsSurface(): LinearLayout = column().apply {
    addView(text("User-controlled setup", 22f, COLOR_TEXT, true))
    addView(
      text(
        "Wireless debugging, Accessibility, direct PC access, and local APK installs are separate, reversible opt-ins.",
        14f,
        COLOR_MUTED,
      ),
      margin(top = 4, bottom = 10),
    )
    addView(
      controlCard("Passthrough appearance") {
        passthroughStatus = text("", 14f, COLOR_TEXT, true)
        passthroughMessage = text("", 13f, COLOR_MUTED)
        addView(passthroughStatus)
        addView(passthroughMessage, margin(top = 3))
        val styleButtons = row().apply { tag = RustyKioskPanelControls.PASSTHROUGH_CONTROLS }
        naturalPassthroughButton = button("Natural", onUseNaturalPassthrough)
        contourPassthroughButton = button("Contour LUT", onUseContourPassthrough)
        styleButtons.addView(
          naturalPassthroughButton,
          LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(7) },
        )
        styleButtons.addView(contourPassthroughButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(styleButtons, margin(top = 7, height = 46))
        addView(
          text(
            "Contour LUT uses hard color bands to reveal contours; it is not camera edge detection.",
            12f,
            COLOR_MUTED,
          ),
          margin(top = 6),
        )
      },
    )
    addView(controlCard("Dedicated setup helper") {
      setupStatus = text("", 14f, COLOR_TEXT, true)
      addView(setupStatus)
      addView(button("Check helper", onCheckSetupHelper), margin(top = 7, height = 46))
    })
    addView(
      controlCard("Wireless debugging") {
        wifiStatus = text("", 14f, COLOR_TEXT, true)
        wifiAfterBootStatus = text("", 13f, COLOR_MUTED)
        addView(wifiStatus)
        addView(wifiAfterBootStatus, margin(top = 3))
        requestWifiButton = button("", onRequestWifiAdb).apply {
          tag = RustyKioskPanelControls.WIFI_ADB_CONTROLS
        }
        wifiAfterBootButton = button("", onEnableWifiAfterBoot)
        addView(requestWifiButton, margin(top = 7, height = 46))
        addView(wifiAfterBootButton, margin(top = 7, height = 46))
      },
      margin(top = 8),
    )
    addView(
      controlCard("Direct PC link (no ADB)") {
        operatorBridgeStatus = text("", 14f, COLOR_TEXT, true)
        operatorBridgeEndpoint = text("", 13f, COLOR_MUTED)
        operatorBridgeCode = text("", 14f, COLOR_PRIMARY, true)
        installerStatus = text("", 13f, COLOR_MUTED)
        addView(operatorBridgeStatus)
        addView(operatorBridgeEndpoint, margin(top = 3))
        addView(operatorBridgeCode, margin(top = 3))
        addView(installerStatus, margin(top = 3))
        operatorBridgeButton = button("", onToggleOperatorBridge)
        rotateOperatorBridgeButton = button("Rotate pairing code", onRotateOperatorBridgeCode)
        pairingCodeVisibilityButton = button("Show pairing code", action = {
          pairingCodeVisible = !pairingCodeVisible
          renderControls()
        })
        installerPermissionButton = button("Allow local APK installs", onRequestInstallerPermission)
        addView(operatorBridgeButton, margin(top = 7, height = 46))
        addView(pairingCodeVisibilityButton, margin(top = 7, height = 46))
        addView(rotateOperatorBridgeButton, margin(top = 7, height = 46))
        addView(installerPermissionButton, margin(top = 7, height = 46))
      },
      margin(top = 8),
    )
    addView(
      controlCard("Accessibility soft guard") {
        accessibilityStatus = text("", 14f, COLOR_TEXT, true)
        addView(accessibilityStatus)
        accessibilityButton = button("", onEnableAccessibility).apply {
          tag = RustyKioskPanelControls.ACCESSIBILITY_TOGGLE
        }
        addView(accessibilityButton, margin(top = 7, height = 46))
      },
      margin(top = 8),
    )
    controlMessage = text("", 13f, COLOR_MUTED)
    addView(controlMessage, margin(top = 8))
    addView(
      button("Exit to Meta Home", onExitToMetaHome).apply {
        tag = RustyKioskPanelControls.META_HOME_EXIT
      },
      margin(top = 10, height = 48),
    )
    addView(button("Back to apps", onCloseUserControls, primary = true), margin(top = 8, bottom = 10, height = 48))
  }

  private fun render() {
    if (!::searchField.isInitialized) return
    statusLine.text = state.statusLine
    tagFilePath.text = state.tagFilePath
    catalogueSurface.visibility = if (state.userControlsOpen) View.GONE else View.VISIBLE
    controlsSurface.visibility = if (state.userControlsOpen) View.VISIBLE else View.GONE
    controlStatus.text =
      "Passthrough ${state.userControls.passthroughStatusLabel}  ·  Accessibility ${state.userControls.accessibilityStatusLabel}  ·  Direct ${state.userControls.operatorBridgeStatusLabel}"
    controlStatus.setOnClickListener {
      if (state.userControlsOpen) onCloseUserControls() else onOpenUserControls()
    }

    if (searchField.text.toString() != state.searchQuery) {
      applyingSearchValue = true
      searchField.setText(state.searchQuery)
      searchField.setSelection(searchField.text.length)
      applyingSearchValue = false
    }
    renderTagFilters()
    renderAppList()
    renderDetails()
    renderControls()
    applyFocusRequests()
  }

  private fun renderTagFilters() {
    tagFilters.removeAllViews()
    tagFilters.addView(chip("All apps", state.selectedTag == null) { onTagSelected(null) }, chipParams())
    state.tags.forEach { tag ->
      tagFilters.addView(chip(tag, state.selectedTag == tag) { onTagSelected(tag) }, chipParams())
    }
  }

  private fun renderAppList() {
    val entries = state.visibleEntries
    appCount.text = "Apps (${entries.size})"
    appList.removeAllViews()
    if (entries.isEmpty()) {
      appList.addView(text("No apps match the current search and tag filter.", 14f, COLOR_MUTED).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
      })
      return
    }
    entries.forEach { entry ->
      val selected = entry.key == state.selectedKey
      val row = column().apply {
        isClickable = true
        isFocusable = true
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = rounded(if (selected) COLOR_SELECTED else COLOR_SURFACE, COLOR_BORDER, 0, 0)
        setOnClickListener { onAppSelected(entry.key) }
      }
      val heading = row()
      heading.addView(text(entry.label, 15f, COLOR_TEXT, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP, 1f))
      heading.addView(text(if (entry.installed) "Installed" else "Not installed", 12f, if (entry.installed) COLOR_GOOD else COLOR_WARNING))
      row.addView(heading)
      row.addView(text(entry.packageName ?: "Name-only tag-file entry", 12f, COLOR_MUTED).apply { maxLines = 1 })
      if (entry.tags.isNotEmpty()) row.addView(text(entry.tags.sorted().joinToString(" · "), 12f, COLOR_PRIMARY).apply { maxLines = 1 })
      appList.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
      appList.addView(View(context).apply { setBackgroundColor(COLOR_BORDER) }, LinearLayout.LayoutParams(MATCH, dp(1)))
    }
  }

  private fun renderDetails() {
    val entry = state.selectedEntry
    if (entry?.key != selectedDetailsKey) {
      selectedDetailsKey = entry?.key
      tagField.setText("")
    }
    val enabled = entry != null
    detailsTitle.text = entry?.label ?: "Select an app"
    detailsPackage.text = entry?.packageName ?: if (entry == null) "" else "No package supplied"
    detailsStatus.text = entry?.statusLabel.orEmpty()
    detailsStatus.setTextColor(if (entry?.installed != false) COLOR_GOOD else COLOR_WARNING)
    tagField.isEnabled = enabled
    addTagButton.isEnabled = enabled && tagField.text.isNotBlank()
    normalLaunchButton.isEnabled = entry?.launchable == true
    kioskLaunchButton.isEnabled = entry?.launchable == true && state.guardEnabled
    val requirement = entry?.launchRequirement ?: AppLaunchRequirement.ANY
    requirementStatus.text = "Launch requirement: ${requirement.wireName}"
    requirementAnyButton.isEnabled = enabled && requirement != AppLaunchRequirement.ANY
    requirementWifiOnButton.isEnabled = enabled && requirement != AppLaunchRequirement.WIFI_ON
    requirementWifiOffButton.isEnabled = enabled && requirement != AppLaunchRequirement.WIFI_OFF
    cancelPendingLaunchButton.visibility =
      if (state.pendingRequirementLaunchId == null) View.GONE else View.VISIBLE

    val launchOptions = state.selectedLaunchOptions
    launchOptionsStatus.text = launchOptions.message
    launchOptionsStatus.setTextColor(
      if (launchOptions.status == AppLaunchOptionsStatus.REJECTED) COLOR_WARNING else COLOR_MUTED
    )
    launchOptionsRow.removeAllViews()
    launchOptions.options.forEach { option ->
      val description = option.description.takeIf(String::isNotBlank)
      val label = listOfNotNull(option.displayLabel, description).joinToString(" · ")
      val optionButton = button(label, { onLaunchOption(option.optionId) }, primary = true).apply {
        tag = RustyKioskPanelControls.LAUNCH_OPTION_LAUNCH
        contentDescription = "${option.displayLabel}. ${option.description}".trim()
        isAllCaps = false
        maxLines = 2
      }
      launchOptionsRow.addView(
        optionButton,
        LinearLayout.LayoutParams(WRAP, dp(54)).apply { rightMargin = dp(6) },
      )
    }

    selectedTags.removeAllViews()
    entry?.tags?.sorted()?.forEach { tag ->
      selectedTags.addView(chip("$tag ×", false) { onRemoveTag(tag) }, chipParams())
    }
    launchGuidance.text =
      if (state.pendingRequirementMessage != null) {
        "${state.pendingRequirementMessage} Return from Android Wi-Fi settings to retry automatically, or cancel."
      } else if (state.guardEnabled) {
        "Requirements are checked before either launch. Soft guard: Home #1/#2 restore; Home #3 returns here."
      } else {
        "Requirements are checked before either launch. Kiosk launch also needs Accessibility."
      }
    launchGuidance.setTextColor(
      if (state.pendingRequirementMessage == null && state.guardEnabled) COLOR_MUTED else COLOR_WARNING
    )
    manageControlsButton.visibility = if (state.guardEnabled) View.GONE else View.VISIBLE
  }

  private fun renderControls() {
    val controls = state.userControls
    setupStatus.text = "Helper: ${controls.setupStatusLabel}"
    wifiStatus.text = "Wireless debugging: ${controls.wifiStatusLabel}"
    wifiAfterBootStatus.text = if (controls.requestWifiAfterBoot) "Ask again after restart: On" else "Ask again after restart: Off"
    accessibilityStatus.text = "Accessibility: ${controls.accessibilityStatusLabel}"
    passthroughStatus.text = "System passthrough: ${controls.passthroughStatusLabel}"
    passthroughMessage.text = controls.passthroughMessage
    naturalPassthroughButton.isEnabled =
      controls.passthroughStyle != KioskPassthroughStyle.NATURAL ||
        !controls.systemPassthroughEnabled || !controls.passthroughLutApplied
    contourPassthroughButton.isEnabled =
      controls.passthroughStyle != KioskPassthroughStyle.CONTOUR_LUT ||
        !controls.systemPassthroughEnabled || !controls.passthroughLutApplied
    operatorBridgeStatus.text = "Direct link: ${controls.operatorBridgeStatusLabel}"
    operatorBridgeEndpoint.text = controls.operatorBridgeEndpoint ?: "Connect the headset to Wi-Fi to get an address."
    if (renderedPairingCode != controls.operatorBridgePairingCode) {
      renderedPairingCode = controls.operatorBridgePairingCode
      pairingCodeVisible = false
    }
    operatorBridgeCode.text =
      "Pairing code: ${OperatorBridgePairingCodePresentation.render(controls.operatorBridgePairingCode, pairingCodeVisible)}"
    pairingCodeVisibilityButton.text = if (pairingCodeVisible) "Hide pairing code" else "Show pairing code"
    installerStatus.text =
      if (controls.installerAllowed) {
        "Local APK installer: wearer allowed"
      } else {
        "Local APK installer: needs wearer permission"
      }
    controlMessage.text = controls.message

    requestWifiButton.text = if (controls.wirelessDebuggingEnabled) "Turn off wireless debugging" else "Request wireless debugging"
    requestWifiButton.setOnClickListener {
      if (controls.wirelessDebuggingEnabled) onDisableWifiAdb() else onRequestWifiAdb()
    }
    wifiAfterBootButton.text = if (controls.requestWifiAfterBoot) "Stop asking after restart" else "Ask after each restart"
    wifiAfterBootButton.setOnClickListener {
      if (controls.requestWifiAfterBoot) onDisableWifiAfterBoot() else onEnableWifiAfterBoot()
    }
    accessibilityButton.text = if (controls.accessibilityEnabled) "Disable Accessibility" else "Enable Accessibility"
    accessibilityButton.setOnClickListener {
      if (controls.accessibilityEnabled) onDisableAccessibility() else onEnableAccessibility()
    }
    operatorBridgeButton.text = if (controls.operatorBridgeEnabled) "Disable direct link" else "Enable direct link"
    operatorBridgeButton.setOnClickListener { onToggleOperatorBridge() }
    rotateOperatorBridgeButton.isEnabled = !controls.operatorBridgeRunning
    installerPermissionButton.visibility = if (controls.installerAllowed) View.GONE else View.VISIBLE
    val ready = controls.setupHelperReady && controls.operationInProgress == null
    requestWifiButton.isEnabled = ready
    wifiAfterBootButton.isEnabled = ready
    accessibilityButton.isEnabled = controls.accessibilityEnabled || ready
  }

  private fun applyFocusRequests() {
    if (state.userControlsOpen) return
    if (state.searchFocusRequest > renderedSearchFocusRequest) {
      renderedSearchFocusRequest = state.searchFocusRequest
      focusAndRequestKeyboard(searchField, "search")
    }
    if (state.tagFocusRequest > renderedTagFocusRequest && state.selectedEntry != null) {
      renderedTagFocusRequest = state.tagFocusRequest
      focusAndRequestKeyboard(tagField, "tag-editor")
    }
  }

  private fun submitTag() {
    val value = tagField.text.toString()
    if (value.isBlank()) return
    onAddTag(value)
    tagField.setText("")
  }

  private fun imeField(
    controlName: String,
    imeAction: Int,
    onChanged: (String) -> Unit,
    onSubmit: () -> Unit,
  ): EditText =
    EditText(context).apply {
      inputType = InputType.TYPE_CLASS_TEXT
      setSingleLine(true)
      isFocusable = true
      isFocusableInTouchMode = true
      showSoftInputOnFocus = true
      isCursorVisible = true
      this.imeOptions = imeAction or EditorInfo.IME_FLAG_NO_EXTRACT_UI
      gravity = Gravity.CENTER_VERTICAL
      textSize = 17f
      setTextColor(COLOR_TEXT)
      setHintTextColor(COLOR_MUTED)
      setPadding(dp(12), 0, dp(12), 0)
      background = rounded(COLOR_FIELD, COLOR_BORDER, 1, 8)
      addTextChangedListener(
        object : TextWatcher {
          override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
          override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) =
            onChanged(text?.toString().orEmpty())
          override fun afterTextChanged(text: Editable?) = Unit
        }
      )
      setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) requestKeyboard(this, controlName)
      }
      setOnClickListener { requestKeyboard(this, controlName) }
      setOnEditorActionListener { _, actionId, event ->
        val submitted =
          actionId == imeAction ||
            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
        if (submitted) onSubmit()
        submitted
      }
    }

  private fun focusAndRequestKeyboard(field: EditText, controlName: String) {
    field.post {
      if (!field.hasFocus()) field.requestFocus()
      requestKeyboard(field, controlName)
    }
  }

  private fun requestKeyboard(field: EditText, controlName: String) {
    requestKeyboard(field, controlName, attempt = 1)
  }

  private fun requestKeyboard(field: EditText, controlName: String, attempt: Int) {
    field.post {
      if (!field.hasFocus()) field.requestFocus()

      // Spatial panels are hosted on a Meta-owned virtual display. An InputMethodManager obtained
      // from the Activity remains bound to display 0 and cannot reliably serve this EditText.
      val fieldDisplay = field.display
      val imeContext =
        if (fieldDisplay == null) field.context else field.context.createDisplayContext(fieldDisplay)
      val inputMethodManager = imeContext.getSystemService(InputMethodManager::class.java)
      inputMethodManager.restartInput(field)
      val accepted =
        inputMethodManager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
      Log.i(
        QUEST_IME_LOG_TAG,
        "status=keyboard-requested control=$controlName attempt=$attempt " +
          "fieldDisplayId=${fieldDisplay?.displayId ?: -1} " +
          "imeContextDisplayId=${imeContext.display?.displayId ?: -1} " +
          "attached=${field.isAttachedToWindow} windowTokenPresent=${field.windowToken != null} " +
          "showSoftInputAccepted=$accepted textLogged=false",
      )

      if (!accepted && attempt < MAX_KEYBOARD_REQUEST_ATTEMPTS) {
        field.postDelayed(
          { requestKeyboard(field, controlName, attempt + 1) },
          KEYBOARD_REQUEST_RETRY_DELAY_MS,
        )
      }
    }
  }

  private fun controlCard(title: String, content: LinearLayout.() -> Unit): LinearLayout =
    column().apply {
      setPadding(dp(14), dp(11), dp(14), dp(11))
      background = rounded(COLOR_SURFACE, COLOR_BORDER, 1, 8)
      addView(text(title, 17f, COLOR_TEXT, true), margin(bottom = 5))
      content()
    }

  private fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private fun row(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

  private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(context).apply {
      text = value
      textSize = sizeSp
      setTextColor(color)
      includeFontPadding = false
      if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

  private fun button(label: String, action: () -> Unit, primary: Boolean = false): Button =
    Button(context).apply {
      text = label
      textSize = 14f
      isAllCaps = false
      gravity = Gravity.CENTER
      includeFontPadding = false
      minWidth = 0
      minimumWidth = 0
      stateListAnimator = null
      setTextColor(if (primary) Color.BLACK else COLOR_TEXT)
      background = rounded(if (primary) COLOR_PRIMARY else COLOR_SURFACE_ALT, if (primary) COLOR_PRIMARY else COLOR_BORDER, 1, 8)
      setOnClickListener { action() }
    }

  private fun chip(label: String, selected: Boolean, action: () -> Unit): Button =
    button(label, action, primary = selected).apply { textSize = 13f }

  private fun chipParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(WRAP, dp(42)).apply { rightMargin = dp(7) }

  private fun rounded(fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
      cornerRadius = dp(radiusDp).toFloat()
      setColor(fill)
      if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

  private fun margin(
    top: Int = 0,
    right: Int = 0,
    bottom: Int = 0,
    left: Int = 0,
    height: Int = WRAP,
  ): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(MATCH, if (height == WRAP) WRAP else dp(height)).apply {
      setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

  private fun dp(value: Int): Int =
    (value * context.resources.displayMetrics.density + 0.5f).toInt()

  private companion object {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    const val PANEL_LAYER_Z_INDEX = 99
    const val QUEST_IME_LOG_TAG = "RustyKioskIme"
    const val MAX_KEYBOARD_REQUEST_ATTEMPTS = 2
    const val KEYBOARD_REQUEST_RETRY_DELAY_MS = 150L
    val COLOR_BACKGROUND = Color.rgb(25, 25, 25)
    val COLOR_SURFACE = Color.rgb(35, 35, 35)
    val COLOR_SURFACE_ALT = Color.rgb(48, 48, 48)
    val COLOR_FIELD = Color.rgb(43, 43, 43)
    val COLOR_SELECTED = Color.rgb(67, 50, 37)
    val COLOR_BORDER = Color.rgb(83, 83, 83)
    val COLOR_TEXT = Color.rgb(244, 240, 236)
    val COLOR_MUTED = Color.rgb(185, 180, 175)
    val COLOR_PRIMARY = Color.rgb(226, 139, 69)
    val COLOR_GOOD = Color.rgb(112, 203, 145)
    val COLOR_WARNING = Color.rgb(242, 138, 126)
  }
}
