package io.github.mesmerprism.rustykiosk

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionControls
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType

class RustyKioskActivity : AppSystemActivity() {
  private val installedApps by lazy(LazyThreadSafetyMode.NONE) { InstalledAppRepository(this) }
  private val tagStore by lazy(LazyThreadSafetyMode.NONE) { TagFileStore(this) }
  private val launchController by lazy(LazyThreadSafetyMode.NONE) { LaunchController(this) }
  private val setupHelperClient by lazy(LazyThreadSafetyMode.NONE) { SetupHelperControlClient(this) }
  private val setupResultStore by lazy(LazyThreadSafetyMode.NONE) { SetupHelperResultStore(this) }
  private val cliStore by lazy(LazyThreadSafetyMode.NONE) { RustyKioskCliStore(this) }
  private val passthroughSettings by lazy(LazyThreadSafetyMode.NONE) {
    KioskPassthroughSettings(this)
  }
  private val operatorBridgeSettings by lazy(LazyThreadSafetyMode.NONE) {
    OperatorBridgeSettings(this)
  }
  private val guardLaunchHandoffLease by lazy(LazyThreadSafetyMode.NONE) {
    GuardLaunchHandoffLease(this)
  }
  private val browsingStateStore by lazy(LazyThreadSafetyMode.NONE) {
    KioskBrowsingStateStore(this)
  }
  private val packageIdentityResolver by lazy(LazyThreadSafetyMode.NONE) {
    PackageSigningIdentityResolver(this)
  }
  private val requirementCoordinator by lazy(LazyThreadSafetyMode.NONE) {
    ActiveRequirementLaunchCoordinator(
      ProcessPendingRequirementLaunchStore,
      ProductionActiveRequirementHandlers.create(this),
      SystemClock::elapsedRealtime,
    )
  }
  private val wifiSettingsRemediator by lazy(LazyThreadSafetyMode.NONE) {
    AndroidWifiSettingsRemediator(this)
  }
  private val mainHandler = Handler(Looper.getMainLooper())
  private var panelEntity: Entity? = null
  private var passthroughController: RustyKioskPassthroughController? = null
  private var passthroughState = KioskPassthroughState()
  private val kioskPanelDelegate = lazy(LazyThreadSafetyMode.NONE) { createKioskPanel() }
  private val kioskPanel: RustyKioskNativePanel
    get() = kioskPanelDelegate.value
  private var state = KioskUiState()
    set(value) {
      field = value
      if (kioskPanelDelegate.isInitialized()) kioskPanel.update(value)
    }
  private var pendingCliRequestId: String? = null
  private var pendingRequirementCliRequest: RustyKioskCliRequest? = null

  override fun registerFeatures(): List<SpatialFeature> =
    listOf(
      VRFeature(
        this,
        LocomotionControls.Right,
        false,
        VrInputSystemType.INTERACTION_SDK,
      ),
    )

  override fun onCreate(savedInstanceState: Bundle?) {
    val launchIntent = intent
    pendingCliRequestId =
      launchIntent.getStringExtra(RustyKioskCliProtocol.EXTRA_PENDING_REQUEST_ID)
    super.onCreate(savedInstanceState)
    // Meta's VR keyboard can have an active served EditText and report mInputShown=true while its
    // compositor surface remains hidden. This activity-window flag is Meta's Spatial SDK
    // compatibility route for keeping the system IME surface visible above the immersive scene.
    window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    operatorBridgeSettings.ensureStartedIfEnabled()
    runCatching { systemManager.unregisterSystem<LocomotionSystem>() }
    val browsingState = browsingStateStore.load()
    state =
      state.copy(
        searchQuery = browsingState.searchQuery,
        selectedTag = browsingState.selectedTag,
        selectedKey = browsingState.selectedKey,
      )
    guardLaunchHandoffLease.clear()
    launchController.disarm(
      if (launchIntent.action == GuardContract.ACTION_RETURN_TO_KIOSK) {
        "triple-home-return-created"
      } else {
        "rusty-kiosk-created"
      }
    )
    refreshCatalogue("activity-created")
    tagStore.startWatching {
      runOnUiThread { refreshCatalogue("tag-file-hotload") }
    }
  }

  override fun onNewIntent(intent: Intent) {
    if (intent.action == GuardContract.ACTION_RETURN_TO_KIOSK) {
      setIntent(mainIntent())
      guardLaunchHandoffLease.clear()
      launchController.disarm("triple-home-return")
      super.onNewIntent(intent)
      restartFreshSpatialTask()
      return
    }
    setIntent(intent)
    pendingCliRequestId =
      intent.getStringExtra(RustyKioskCliProtocol.EXTRA_PENDING_REQUEST_ID)
    super.onNewIntent(intent)
    refreshCatalogue("new-intent")
    mainHandler.postDelayed(::consumePendingCliCommand, CLI_FOREGROUND_SETTLE_MS)
  }

  override fun onResume() {
    super.onResume()
    passthroughController?.let {
      applyPassthroughStyle(passthroughSettings.load(), "activity-resumed", persist = false)
    }
    guardLaunchHandoffLease.clear()
    launchController.disarm("rusty-kiosk-resumed")
    refreshCatalogue("activity-resumed")
    if (requirementCoordinator.pending() != null) {
      mainHandler.postDelayed(::resumePendingRequirementLaunch, REQUIREMENT_RESUME_SETTLE_MS)
    }
    mainHandler.postDelayed(::consumePendingCliCommand, CLI_FOREGROUND_SETTLE_MS)
    if (pendingCliRequestId == null && setupHelperClient.isInstalled() &&
      setupResultStore.snapshot().pendingOperation == null
    ) {
      mainHandler.post { dispatchSetupHelper(SetupHelperOperation.STATUS) }
    }
  }

  override fun onDestroy() {
    tagStore.stopWatching()
    mainHandler.removeCallbacksAndMessages(null)
    passthroughController?.stop("activity-destroyed")
    passthroughController = null
    if (kioskPanelDelegate.isInitialized()) kioskPanel.release()
    super.onDestroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, VIEW_ORIGIN_Z_METERS, 180.0f)
    passthroughController =
      RustyKioskPassthroughController(scene) { marker ->
        android.util.Log.i(PASSTHROUGH_LOG_TAG, marker)
      }
    applyPassthroughStyle(passthroughSettings.load(), "scene-ready", persist = false)
    panelEntity =
      Entity.createPanelEntity(
        R.id.kiosk_panel,
        Transform(
          Pose(
            Vector3(0.0f, PANEL_CENTER_Y_METERS, PANEL_Z_METERS),
            Quaternion(0.0f, 0.0f, 1.0f, 0.0f),
          )
        ),
        PanelDimensions(
          Vector2(RustyKioskPanelGeometry.WIDTH_METERS, RustyKioskPanelGeometry.HEIGHT_METERS)
        ),
        Scale(Vector3(1.0f, 1.0f, 1.0f)),
        Visible(true),
      )
  }

  override fun registerPanels(): List<PanelRegistration> = listOf(kioskPanel.registration())

  private fun createKioskPanel(): RustyKioskNativePanel =
    RustyKioskNativePanel(
      context = this,
      onSearchChanged = ::setSearchQuery,
      onTagSelected = ::setTagFilter,
      onAppSelected = ::setSelectedKey,
      onRefresh = { refreshCatalogue("panel-refresh") },
      onAddTag = ::addTag,
      onRemoveTag = ::removeTag,
      onLaunchRequirementSelected = ::setLaunchRequirement,
      onCancelPendingRequirementLaunch = ::cancelPendingRequirementLaunch,
      onNormalLaunch = { launchSelected(LaunchKind.NORMAL) },
      onKioskLaunch = { launchSelected(LaunchKind.KIOSK) },
      onOpenUserControls = { state = state.copy(userControlsOpen = true) },
      onCloseUserControls = { state = state.copy(userControlsOpen = false) },
      onCheckSetupHelper = { dispatchSetupHelper(SetupHelperOperation.STATUS) },
      onRequestWifiAdb = { dispatchSetupHelper(SetupHelperOperation.REQUEST_WIFI_ADB) },
      onEnableWifiAfterBoot = { dispatchSetupHelper(SetupHelperOperation.ENABLE_WIFI_AFTER_BOOT) },
      onDisableWifiAfterBoot = { dispatchSetupHelper(SetupHelperOperation.DISABLE_WIFI_AFTER_BOOT) },
      onDisableWifiAdb = { dispatchSetupHelper(SetupHelperOperation.DISABLE_WIFI_ADB) },
      onEnableAccessibility = { dispatchSetupHelper(SetupHelperOperation.ENABLE_ACCESSIBILITY) },
      onDisableAccessibility = ::disableAccessibility,
      onUseNaturalPassthrough = {
        applyPassthroughStyle(KioskPassthroughStyle.NATURAL, "panel-natural")
      },
      onUseContourPassthrough = {
        applyPassthroughStyle(KioskPassthroughStyle.CONTOUR_LUT, "panel-contour")
      },
      onToggleOperatorBridge = ::toggleOperatorBridge,
      onRotateOperatorBridgeCode = ::rotateOperatorBridgeCode,
      onRequestInstallerPermission = ::requestInstallerPermission,
      onExitToMetaHome = ::exitToMetaHome,
    ).also { it.update(state) }

  private fun refreshCatalogue(source: String) {
    val previousSelection = state.selectedKey
    val result =
      runCatching {
        val document = tagStore.loadDocument()
        val entries = CatalogAssembler.assemble(installedApps.snapshot(), document)
        val selectedTag = state.selectedTag?.takeIf { tag -> entries.any { tag in it.tags } }
        if (selectedTag != state.selectedTag) browsingStateStore.setSelectedTag(selectedTag)
        val visibleEntries = CatalogFilter.apply(entries, state.searchQuery, selectedTag)
        val selected = previousSelection?.takeIf { key -> visibleEntries.any { it.key == key } }
          ?: visibleEntries.firstOrNull()?.key
        if (selected != state.selectedKey) browsingStateStore.setSelectedKey(selected)
        val userControls = readUserControls()
        KioskUiState(
          entries = entries,
          searchQuery = state.searchQuery,
          selectedTag = selectedTag,
          selectedKey = selected,
          statusLine = "${entries.count { it.installed }} installed · ${entries.count { !it.installed }} not installed · $source",
          tagFilePath = tagStore.tagFile.absolutePath,
          guardEnabled = userControls.accessibilityEnabled,
          userControlsOpen = state.userControlsOpen,
          userControls = userControls,
          searchFocusRequest = state.searchFocusRequest,
          tagFocusRequest = state.tagFocusRequest,
          pendingRequirementLaunchId = requirementCoordinator.pending()?.pendingId,
          pendingRequirementMessage = state.pendingRequirementMessage,
        )
      }
    state =
      result.getOrElse { throwable ->
        state.copy(
          statusLine = "Catalogue reload failed: ${throwable.message ?: throwable.javaClass.simpleName}",
          tagFilePath = tagStore.tagFile.absolutePath,
          guardEnabled = isGuardEnabled(),
          userControls = readUserControls(),
        )
      }
  }

  private fun addTag(value: String) {
    val entry = state.selectedEntry ?: return
    val tag = normalizeTag(value)
    if (tag.isEmpty()) {
      state = state.copy(statusLine = "Enter a tag first.")
      return
    }
    runCatching { tagStore.setTags(entry, entry.tags + tag) }
      .onSuccess { refreshCatalogue("tag-added") }
      .onFailure { throwable ->
        state = state.copy(statusLine = "Could not save tag: ${throwable.message}")
      }
  }

  private fun removeTag(tag: String) {
    val entry = state.selectedEntry ?: return
    runCatching { tagStore.setTags(entry, entry.tags - normalizeTag(tag)) }
      .onSuccess { refreshCatalogue("tag-removed") }
      .onFailure { throwable ->
        state = state.copy(statusLine = "Could not remove tag: ${throwable.message}")
      }
  }

  private fun setLaunchRequirement(requirement: AppLaunchRequirement) {
    val entry = state.selectedEntry ?: return
    if (requirementCoordinator.pending() != null) cancelPendingRequirementLaunch()
    runCatching {
        tagStore.setLaunchRequirement(entry, requirement)
      }
      .onSuccess {
        state = state.copy(
          pendingRequirementLaunchId = null,
          pendingRequirementMessage = null,
        )
        refreshCatalogue("launch-requirement-updated")
      }
      .onFailure { throwable ->
        state = state.copy(
          statusLine = "Could not save launch requirement: ${throwable.message}",
        )
      }
  }

  private fun launchSelected(kind: LaunchKind): LaunchResult {
    val entry = state.selectedEntry
      ?: return LaunchResult(false, "Select an app first.").also { result ->
        state = state.copy(statusLine = result.message)
      }
    val bound = createFreshRequirementCandidate(entry.key, kind)
      ?: return LaunchResult(false, "The selected app changed before requirement preflight.").also {
        state = state.copy(statusLine = it.message)
      }
    return handleRequirementDecision(requirementCoordinator.request(bound.candidate), bound)
  }

  private fun performLaunch(entry: CatalogEntry, kind: LaunchKind): LaunchResult {
    val targetPackage = entry.target?.packageName
    if (kind == LaunchKind.KIOSK && targetPackage != null) {
      guardLaunchHandoffLease.arm(targetPackage)
    } else {
      guardLaunchHandoffLease.clear()
    }
    val result = launchController.launch(entry, kind, state.guardEnabled)
    if (!result.accepted) guardLaunchHandoffLease.clear()
    state = state.copy(statusLine = result.message)
    return result
  }

  private data class BoundRequirementCandidate(
    val candidate: ActiveRequirementLaunchCandidate,
    val entry: CatalogEntry,
  )

  private fun createFreshRequirementCandidate(
    entryKey: String,
    kind: LaunchKind,
  ): BoundRequirementCandidate? = runCatching {
    val document = tagStore.loadDocument()
    val entry = CatalogAssembler.assemble(installedApps.snapshot(), document)
      .singleOrNull { it.key == entryKey } ?: return null
    val packageName = entry.packageName ?: return null
    val identity = packageIdentityResolver.resolve(packageName) ?: return null
    BoundRequirementCandidate(
      ActiveRequirementLaunchBindingFactory.create(entry, document, kind, identity),
      entry,
    )
  }.getOrNull()

  private fun handleRequirementDecision(
    decision: RequirementLaunchDecision,
    bound: BoundRequirementCandidate? = null,
  ): LaunchResult = when (decision) {
    is RequirementLaunchDecision.LaunchNow -> {
      val fresh = bound?.takeIf { it.candidate == decision.candidate }
        ?: createFreshRequirementCandidate(
          decision.candidate.binding.catalogEntryKey,
          decision.candidate.binding.launchKind,
        )?.takeIf { it.candidate == decision.candidate }
      if (fresh == null) {
        requirementCoordinator.cancel()
        LaunchResult(false, "The app or requirement changed before launch.").also {
          state = state.copy(
            statusLine = it.message,
            pendingRequirementLaunchId = null,
            pendingRequirementMessage = null,
          )
        }
      } else {
        state = state.copy(
          pendingRequirementLaunchId = null,
          pendingRequirementMessage = null,
        )
        performLaunch(fresh.entry, fresh.candidate.binding.launchKind)
      }
    }
    is RequirementLaunchDecision.RemediationRequired -> {
      val opened = wifiSettingsRemediator.open()
      if (!opened) requirementCoordinator.cancel(decision.pending.pendingId)
      val message = if (opened) {
        "${decision.evaluation.reason} Android Wi-Fi settings opened; return to revalidate or cancel."
      } else {
        "${decision.evaluation.reason} Android Wi-Fi settings could not be opened."
      }
      LaunchResult(opened, message, completed = !opened).also {
        state = state.copy(
          statusLine = message,
          pendingRequirementLaunchId = if (opened) decision.pending.pendingId else null,
          pendingRequirementMessage = if (opened) decision.evaluation.reason else null,
        )
      }
    }
    is RequirementLaunchDecision.Waiting ->
      LaunchResult(true, "${decision.evaluation.reason} Change Wi-Fi, return, or cancel.", completed = false)
        .also {
          state = state.copy(
            statusLine = it.message,
            pendingRequirementLaunchId = decision.pending.pendingId,
            pendingRequirementMessage = decision.evaluation.reason,
          )
        }
    is RequirementLaunchDecision.Blocked ->
      LaunchResult(false, decision.reason).also {
        state = state.copy(
          statusLine = it.message,
          pendingRequirementLaunchId = null,
          pendingRequirementMessage = null,
        )
      }
    is RequirementLaunchDecision.Cleared ->
      LaunchResult(false, decision.message).also {
        state = state.copy(
          statusLine = it.message,
          pendingRequirementLaunchId = null,
          pendingRequirementMessage = null,
        )
      }
    RequirementLaunchDecision.NoPending ->
      LaunchResult(false, "No pending requirement launch exists.")
  }

  private fun resumePendingRequirementLaunch() {
    val pending = requirementCoordinator.pending() ?: return
    val bound = createFreshRequirementCandidate(
      pending.binding.catalogEntryKey,
      pending.binding.launchKind,
    )
    val result = handleRequirementDecision(requirementCoordinator.resume(bound?.candidate), bound)
    if (result.completed) {
      val pendingCli = pendingRequirementCliRequest ?: cliStore.activeRequest()?.takeIf {
        it.command == RustyKioskCliCommand.LAUNCH_NORMAL ||
          it.command == RustyKioskCliCommand.LAUNCH_KIOSK
      }
      pendingCli?.let { request ->
        pendingRequirementCliRequest = null
        recordCliOutcome(request, result.toCliOutcome())
      }
    }
  }

  private fun cancelPendingRequirementLaunch(): LaunchResult =
    handleRequirementDecision(requirementCoordinator.cancel(state.pendingRequirementLaunchId)).also { result ->
      if (result.completed) {
        val pendingCli = pendingRequirementCliRequest ?: cliStore.activeRequest()?.takeIf {
          it.command == RustyKioskCliCommand.LAUNCH_NORMAL ||
            it.command == RustyKioskCliCommand.LAUNCH_KIOSK
        }
        pendingCli?.let { request ->
          pendingRequirementCliRequest = null
          recordCliOutcome(request, result.toCliOutcome())
        }
      }
    }

  private fun consumePendingCliCommand() {
    val requestId = pendingCliRequestId ?: return
    pendingCliRequestId = null
    val request = cliStore.consume(requestId) ?: return
    val outcome = executeCliCommand(request)
    if (outcome != null) recordCliOutcome(request, outcome)
  }

  private fun recordCliOutcome(
    request: RustyKioskCliRequest,
    outcome: RustyKioskCliOutcome,
  ) {
    refreshUserControls()
    cliStore.record(
      request = request,
      outcome = outcome,
      state = state,
      guardArmed = GuardStateStore(this).loadArmed() != null,
    )
  }

  private fun executeCliCommand(request: RustyKioskCliRequest): RustyKioskCliOutcome? =
    when (request.command) {
      RustyKioskCliCommand.STATUS -> {
        refreshCatalogue("cli-status")
        cliOutcome(true, true, "Current Rusty Kiosk state recorded.")
      }
      RustyKioskCliCommand.SHOW_CONTROLS -> {
        state = state.copy(userControlsOpen = true)
        cliOutcome(true, true, "User controls opened.")
      }
      RustyKioskCliCommand.SHOW_APPS -> {
        state = state.copy(userControlsOpen = false)
        cliOutcome(true, true, "App catalogue opened.")
      }
      RustyKioskCliCommand.RELOAD -> {
        refreshCatalogue("cli-reload")
        cliOutcome(true, true, "Catalogue and tag file reloaded.")
      }
      RustyKioskCliCommand.FOCUS_SEARCH -> {
        state =
          state.copy(
            userControlsOpen = false,
            searchFocusRequest = state.searchFocusRequest + 1L,
          )
        cliOutcome(true, false, "Search field focus and keyboard requested.")
      }
      RustyKioskCliCommand.FOCUS_TAG_EDITOR -> {
        if (state.selectedEntry == null) {
          cliOutcome(false, true, "Select an app first.")
        } else {
          state =
            state.copy(
              userControlsOpen = false,
              tagFocusRequest = state.tagFocusRequest + 1L,
            )
          cliOutcome(true, false, "Tag editor focus and keyboard requested.")
        }
      }
      RustyKioskCliCommand.SET_SEARCH -> {
        setSearchQuery(request.value.orEmpty())
        cliOutcome(true, true, "Search updated.")
      }
      RustyKioskCliCommand.SELECT -> selectFromCli(request.value.orEmpty())
      RustyKioskCliCommand.FILTER_TAG -> filterTagFromCli(request.value)
      RustyKioskCliCommand.ADD_TAG -> addTagFromCli(request.value.orEmpty())
      RustyKioskCliCommand.REMOVE_TAG -> removeTagFromCli(request.value.orEmpty())
      RustyKioskCliCommand.SET_LAUNCH_REQUIREMENT ->
        setLaunchRequirementFromCli(request.value.orEmpty())
      RustyKioskCliCommand.CANCEL_PENDING_LAUNCH ->
        cancelPendingRequirementLaunch().toCliOutcome()
      RustyKioskCliCommand.LAUNCH_NORMAL -> launchFromCli(request, LaunchKind.NORMAL)
      RustyKioskCliCommand.LAUNCH_KIOSK -> launchFromCli(request, LaunchKind.KIOSK)
      RustyKioskCliCommand.CHECK_SETUP_HELPER ->
        requestSetupOperationFromCli(request, SetupHelperOperation.STATUS)
      RustyKioskCliCommand.REQUEST_WIFI_ADB ->
        requestSetupOperationFromCli(request, SetupHelperOperation.REQUEST_WIFI_ADB)
      RustyKioskCliCommand.ENABLE_WIFI_AFTER_BOOT ->
        requestSetupOperationFromCli(request, SetupHelperOperation.ENABLE_WIFI_AFTER_BOOT)
      RustyKioskCliCommand.DISABLE_WIFI_AFTER_BOOT ->
        requestSetupOperationFromCli(request, SetupHelperOperation.DISABLE_WIFI_AFTER_BOOT)
      RustyKioskCliCommand.DISABLE_WIFI_ADB ->
        requestSetupOperationFromCli(request, SetupHelperOperation.DISABLE_WIFI_ADB)
      RustyKioskCliCommand.ENABLE_ACCESSIBILITY -> {
        if (isGuardEnabled()) cliOutcome(true, true, "Accessibility is already enabled.")
        else requestSetupOperationFromCli(request, SetupHelperOperation.ENABLE_ACCESSIBILITY)
      }
      RustyKioskCliCommand.DISABLE_ACCESSIBILITY -> {
        if (!isGuardEnabled()) cliOutcome(true, true, "Accessibility is already disabled.")
        else requestSetupOperationFromCli(request, SetupHelperOperation.DISABLE_ACCESSIBILITY)
      }
      RustyKioskCliCommand.PASSTHROUGH_NATURAL ->
        setPassthroughFromCli(KioskPassthroughStyle.NATURAL, "cli-natural")
      RustyKioskCliCommand.PASSTHROUGH_CONTOUR ->
        setPassthroughFromCli(KioskPassthroughStyle.CONTOUR_LUT, "cli-contour")
      RustyKioskCliCommand.EXIT_META_HOME -> {
        exitToMetaHome()
        cliOutcome(true, false, "Meta Home exit requested.")
      }
    }

  private fun selectFromCli(selector: String): RustyKioskCliOutcome {
    val normalized = normalizeLookup(selector)
    val entry =
      state.visibleEntries.firstOrNull { candidate ->
        candidate.key == selector ||
          candidate.packageName == selector ||
          normalizeLookup(candidate.label) == normalized
      }
      ?: return cliOutcome(false, true, "No visible app matches the selector.")
    setSelectedKey(entry.key)
    return cliOutcome(true, true, "Selected ${entry.label}.")
  }

  private fun filterTagFromCli(value: String?): RustyKioskCliOutcome {
    val tag = value?.let(::normalizeTag)
    if (tag != null && tag !in state.tags) {
      return cliOutcome(false, true, "No catalogue tag matches the requested filter.")
    }
    setTagFilter(tag)
    return cliOutcome(true, true, if (tag == null) "Tag filter cleared." else "Tag filter set to $tag.")
  }

  private fun setSearchQuery(query: String) {
    val searchQuery = browsingStateStore.setSearchQuery(query)
    val selectedKey = retainedVisibleSelection(searchQuery, state.selectedTag)
    state = state.copy(searchQuery = searchQuery, selectedKey = selectedKey)
  }

  private fun setTagFilter(tag: String?) {
    val selectedTag = browsingStateStore.setSelectedTag(tag)
    val selectedKey = retainedVisibleSelection(state.searchQuery, selectedTag)
    state = state.copy(selectedTag = selectedTag, selectedKey = selectedKey)
  }

  private fun setSelectedKey(key: String?) {
    state = state.copy(selectedKey = browsingStateStore.setSelectedKey(key))
  }

  private fun retainedVisibleSelection(searchQuery: String, selectedTag: String?): String? {
    val visibleEntries = CatalogFilter.apply(state.entries, searchQuery, selectedTag)
    val selectedKey = state.selectedKey?.takeIf { key -> visibleEntries.any { it.key == key } }
      ?: visibleEntries.firstOrNull()?.key
    return browsingStateStore.setSelectedKey(selectedKey)
  }

  private fun addTagFromCli(value: String): RustyKioskCliOutcome {
    val entry = state.selectedEntry
      ?: return cliOutcome(false, true, "Select an app first.")
    val tag = normalizeTag(value)
    addTag(value)
    val saved = tag.isNotEmpty() && state.selectedEntry?.tags?.contains(tag) == true
    return cliOutcome(saved, true, if (saved) "Added $tag to ${entry.label}." else state.statusLine)
  }

  private fun removeTagFromCli(value: String): RustyKioskCliOutcome {
    val entry = state.selectedEntry
      ?: return cliOutcome(false, true, "Select an app first.")
    val tag = normalizeTag(value)
    if (tag !in entry.tags) return cliOutcome(false, true, "The selected app does not have that tag.")
    removeTag(tag)
    val removed = state.selectedEntry?.tags?.contains(tag) != true
    return cliOutcome(removed, true, if (removed) "Removed $tag from ${entry.label}." else state.statusLine)
  }

  private fun setLaunchRequirementFromCli(value: String): RustyKioskCliOutcome {
    val entry = state.selectedEntry ?: return cliOutcome(false, true, "Select an app first.")
    val requirement = runCatching { AppLaunchRequirement.parseStrict(value) }
      .getOrElse { return cliOutcome(false, true, "Requirement must be any, wifi-on, or wifi-off.") }
    setLaunchRequirement(requirement)
    val saved = state.selectedEntry?.launchRequirement == requirement
    return cliOutcome(
      saved,
      true,
      if (saved) "${entry.label} now requires ${requirement.wireName}." else state.statusLine,
    )
  }

  private fun launchFromCli(
    request: RustyKioskCliRequest,
    kind: LaunchKind,
  ): RustyKioskCliOutcome? {
    val result = launchSelected(kind)
    if (result.accepted && !result.completed) {
      pendingRequirementCliRequest = request
      return null
    }
    return result.toCliOutcome()
  }

  private fun requestSetupOperationFromCli(
    request: RustyKioskCliRequest,
    operation: SetupHelperOperation,
  ): RustyKioskCliOutcome? {
    val controls = readUserControls()
    if (!controls.setupHelperInstalled) {
      return cliOutcome(false, true, "Rusty Kiosk Setup is not installed.")
    }
    if (operation != SetupHelperOperation.STATUS && !controls.setupHelperReady) {
      return cliOutcome(
        false,
        true,
        "Rusty Kiosk Setup needs one USB-C provisioning step before it can change settings.",
      )
    }
    dispatchSetupHelper(operation, request)
    return null
  }

  private fun cliOutcome(
    accepted: Boolean,
    completed: Boolean,
    message: String,
  ) = RustyKioskCliOutcome(accepted, completed, message)

  private fun LaunchResult.toCliOutcome(): RustyKioskCliOutcome =
    cliOutcome(accepted = accepted, completed = completed, message = message)

  private fun dispatchSetupHelper(
    operation: SetupHelperOperation,
    cliRequest: RustyKioskCliRequest? = null,
  ) {
    setupHelperClient.dispatch(operation) { result ->
      refreshUserControls(result.message)
      mainHandler.postDelayed(
        {
          refreshUserControls(result.message)
          cliRequest?.let { request ->
            recordCliOutcome(
              request,
              cliOutcome(result.success, true, result.message),
            )
          }
        },
        CONTROL_READBACK_SETTLE_MS,
      )
    }
      .onSuccess { refreshUserControls() }
      .onFailure { throwable ->
        val message = throwable.message ?: "The fixed setup request could not be started."
        refreshUserControls(message)
        cliRequest?.let { request -> recordCliOutcome(request, cliOutcome(false, true, message)) }
      }
  }

  private fun disableAccessibility() {
    if (!isGuardEnabled()) {
      refreshUserControls("Accessibility is already disabled.")
      return
    }
    launchController.disarm("user-disable-requested")
    if (readUserControls().setupHelperReady) {
      dispatchSetupHelper(SetupHelperOperation.DISABLE_ACCESSIBILITY)
    } else {
      KioskAccessibilityService.requestUserDisable(this)
      refreshUserControls("Disabling Rusty Kiosk Accessibility from the active service…")
      mainHandler.postDelayed(
        {
          refreshUserControls(
            if (isGuardEnabled()) {
              "The service could not disable itself. Reinstall and provision Rusty Kiosk Setup, then retry."
            } else {
              "Accessibility disabled. Kiosk launch is now unavailable."
            }
          )
        },
        ACCESSIBILITY_DISABLE_SETTLE_MS,
      )
    }
  }

  private fun exitToMetaHome() {
    launchController.disarm("user-exit-to-meta-home")
    runCatching {
        startActivity(
          Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      }
      .onFailure {
        state = state.copy(statusLine = "Meta Home could not be opened directly. Press the Meta Home button.")
      }
  }

  private fun toggleOperatorBridge() {
    val enable = !operatorBridgeSettings.snapshot().enabled
    operatorBridgeSettings.setEnabled(enable)
    refreshUserControls(
      if (enable) {
        "Starting the authenticated local operator link. It exposes only fixed Rusty Kiosk operations."
      } else {
        "Local operator access disabled. ADB recovery tools are unchanged."
      }
    )
    mainHandler.postDelayed(
      { refreshUserControls(if (enable) "Local operator link status refreshed." else null) },
      OPERATOR_BRIDGE_SETTLE_MS,
    )
  }

  private fun rotateOperatorBridgeCode() {
    operatorBridgeSettings.rotatePairingCode()
    refreshUserControls(
      "Pairing code rotated and the direct link was disabled. Update the PC, then enable it again."
    )
  }

  private fun requestInstallerPermission() {
    runCatching {
        startActivity(
          Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
          )
        )
      }
      .onSuccess {
        refreshUserControls("Use Meta's visible setting to allow or deny local APK installation.")
      }
      .onFailure { throwable ->
        refreshUserControls(
          "The headset did not expose the per-app installer setting: ${throwable.message ?: throwable.javaClass.simpleName}"
        )
      }
  }

  private fun setPassthroughFromCli(
    style: KioskPassthroughStyle,
    source: String,
  ): RustyKioskCliOutcome {
    val updated = applyPassthroughStyle(style, source)
    return cliOutcome(
      accepted = updated.lutApplied,
      completed = true,
      message = updated.message,
    )
  }

  private fun applyPassthroughStyle(
    style: KioskPassthroughStyle,
    source: String,
    persist: Boolean = true,
  ): KioskPassthroughState {
    if (persist) passthroughSettings.save(style)
    val controller = passthroughController
    passthroughState =
      if (controller == null) {
        KioskPassthroughState(
          style = style,
          message = "The Spatial SDK scene is still starting; ${style.label} passthrough is queued.",
        )
      } else {
        controller.apply(style, source)
      }
    refreshUserControls(passthroughState.message)
    if (controller != null) {
      mainHandler.postDelayed(
        {
          if (passthroughController === controller) {
            passthroughState = controller.snapshot("$source-readback", emitMarker = true)
            refreshUserControls()
          }
        },
        PASSTHROUGH_READBACK_SETTLE_MS,
      )
    }
    return passthroughState
  }

  private fun readUserControls(messageOverride: String? = null): UserControlState {
    val installed = setupHelperClient.isInstalled()
    val ready =
      installed && setupHelperClient.hasControlPermission() &&
        setupHelperClient.hasWriteSecureSettings()
    val stored = setupResultStore.snapshot()
    val bridge = operatorBridgeSettings.snapshot()
    return UserControlState(
      passthroughStyle = passthroughState.style,
      systemPassthroughEnabled = passthroughState.systemPassthroughEnabled,
      passthroughLutApplied = passthroughState.lutApplied,
      passthroughMessage = passthroughState.message,
      setupHelperInstalled = installed,
      setupHelperReady = ready,
      requestWifiAfterBoot = stored.requestAfterBoot,
      wirelessDebuggingEnabled =
        Settings.Global.getInt(contentResolver, WIFI_ADB_SETTING, 0) == 1,
      accessibilityEnabled = isGuardEnabled(),
      operatorBridgeEnabled = bridge.enabled,
      operatorBridgeRunning = bridge.enabled && bridge.running && bridge.transitionConverged,
      operatorBridgeEndpoint = bridge.endpoint,
      operatorBridgePairingCode = bridge.pairingCode,
      installerAllowed = bridge.installerAllowed,
      operatorBridgeError = bridge.lastError,
      operationInProgress = stored.pendingOperation?.wireName,
      message =
        messageOverride
          ?: stored.message
          ?: if (installed) {
            "Wi-Fi ADB, Accessibility, direct PC access, and local installs are separate opt-ins."
          } else {
            "Install both Rusty Kiosk APKs and provision the setup helper once over USB-C."
          },
    )
  }

  private fun refreshUserControls(message: String? = null) {
    val controls = readUserControls(message)
    state =
      state.copy(
        guardEnabled = controls.accessibilityEnabled,
        userControls = controls,
      )
  }

  private fun isGuardEnabled(): Boolean {
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager
      .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any { service ->
        val info = service.resolveInfo.serviceInfo
        info.packageName == packageName &&
          (info.name == KioskAccessibilityService::class.java.name ||
            packageName + info.name == KioskAccessibilityService::class.java.name)
      }
  }

  private fun restartFreshSpatialTask() {
    val appContext = applicationContext
    Handler(Looper.getMainLooper()).postDelayed(
      {
        appContext.startActivity(
          mainIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
      },
      RETURN_RESTART_DELAY_MS,
    )
    finishAndRemoveTask()
  }

  private fun mainIntent(): Intent =
    Intent(Intent.ACTION_MAIN, null, this, RustyKioskActivity::class.java)
      .addCategory(Intent.CATEGORY_LAUNCHER)

  private companion object {
    const val PANEL_CENTER_Y_METERS = 1.32f
    const val PANEL_Z_METERS = 0.50f
    const val VIEW_ORIGIN_Z_METERS = 2.0f
    const val RETURN_RESTART_DELAY_MS = 500L
    const val ACCESSIBILITY_DISABLE_SETTLE_MS = 800L
    const val OPERATOR_BRIDGE_SETTLE_MS = 700L
    const val CONTROL_READBACK_SETTLE_MS = 600L
    const val PASSTHROUGH_READBACK_SETTLE_MS = 750L
    const val CLI_FOREGROUND_SETTLE_MS = 800L
    const val REQUIREMENT_RESUME_SETTLE_MS = 350L
    const val WIFI_ADB_SETTING = "adb_wifi_enabled"
    const val PASSTHROUGH_LOG_TAG = "RustyKioskPassthrough"
  }
}
