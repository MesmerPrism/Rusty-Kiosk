package io.github.mesmerprism.rustykiosk

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object AppLaunchOptionsContract {
  const val SCHEMA = "rusty.quest.app_launch_options.v1"
  const val SCHEMA_VERSION = 1
  const val PROVIDER_AUTHORITY_SUFFIX = ".app-launch-options"
  const val PROVIDER_PATH = "options"
  const val EXTRA_LAUNCH_OPTION_ID =
    "io.github.mesmerprism.rustyquest.spatial_camera_panel.extra.LAUNCH_OPTION_ID"
  const val META_SCHEMA = "rusty.quest.app_launch_options.schema"
  const val META_PROVIDER_AUTHORITY = "rusty.quest.app_launch_options.provider_authority"
  const val META_OWNER_ACTIVITY = "rusty.quest.app_launch_options.owner_activity"
  const val MAX_OPTION_COUNT = 64
  const val MAX_OPTION_ID_LENGTH = 160
  const val MAX_LABEL_LENGTH = 96
  const val MAX_DESCRIPTION_LENGTH = 160
  const val COLUMN_SCHEMA_VERSION = "schema_version"
  const val COLUMN_OPTION_ID = "option_id"
  const val COLUMN_DISPLAY_LABEL = "display_label"
  const val COLUMN_DESCRIPTION = "description"
  val PROJECTION =
    arrayOf(
      COLUMN_SCHEMA_VERSION,
      COLUMN_OPTION_ID,
      COLUMN_DISPLAY_LABEL,
      COLUMN_DESCRIPTION,
    )

  fun contentUri(packageName: String): Uri =
    Uri.Builder()
      .scheme("content")
      .authority(packageName + PROVIDER_AUTHORITY_SUFFIX)
      .appendPath(PROVIDER_PATH)
      .build()
}

internal data class AppLaunchOption(
  val schemaVersion: Int,
  val optionId: String,
  val displayLabel: String,
  val description: String,
)

internal enum class AppLaunchOptionsStatus(val wireName: String) {
  NONE("none"),
  READY("ready"),
  REJECTED("rejected"),
}

internal data class AppLaunchOptionsBinding(
  val packageName: String,
  val uid: Int,
  val signingIdentity: String,
  val lastUpdateTime: Long,
  val versionCode: Long,
  val providerAuthority: String,
  val providerClass: String,
  val ownerActivity: String,
)

internal data class AppLaunchOptionsUiState(
  val status: AppLaunchOptionsStatus = AppLaunchOptionsStatus.NONE,
  val message: String = "The selected app does not declare launch options.",
  val options: List<AppLaunchOption> = emptyList(),
  val binding: AppLaunchOptionsBinding? = null,
)

internal data class RawAppLaunchOption(
  val schemaVersion: Int,
  val optionId: String?,
  val displayLabel: String?,
  val description: String?,
)

internal object AppLaunchOptionsValidationPolicy {
  fun validateRows(rows: List<RawAppLaunchOption>): List<AppLaunchOption> {
    require(rows.size <= AppLaunchOptionsContract.MAX_OPTION_COUNT) {
      "launch-option-count-invalid"
    }
    val options = rows.map { row ->
      require(row.schemaVersion == AppLaunchOptionsContract.SCHEMA_VERSION) {
        "launch-option-schema-version-invalid"
      }
      val optionId = requireNotNull(row.optionId) { "launch-option-id-null" }
      val displayLabel = requireNotNull(row.displayLabel) { "launch-option-label-null" }
      val description = requireNotNull(row.description) { "launch-option-description-null" }
      require(optionId.isNotBlank() && optionId.length <= AppLaunchOptionsContract.MAX_OPTION_ID_LENGTH) {
        "launch-option-id-invalid"
      }
      require(displayLabel.isNotBlank() && displayLabel.length <= AppLaunchOptionsContract.MAX_LABEL_LENGTH) {
        "launch-option-label-invalid"
      }
      require(description.length <= AppLaunchOptionsContract.MAX_DESCRIPTION_LENGTH) {
        "launch-option-description-invalid"
      }
      AppLaunchOption(row.schemaVersion, optionId, displayLabel, description)
    }
    require(options.map(AppLaunchOption::optionId).distinct().size == options.size) {
      "launch-option-id-duplicate"
    }
    return options
  }

  fun validateMetadata(
    packageName: String,
    launchActivity: String,
    schema: String?,
    providerAuthority: String?,
    ownerActivity: String?,
  ): Triple<String, String, String>? {
    if (schema == null && providerAuthority == null && ownerActivity == null) return null
    require(schema == AppLaunchOptionsContract.SCHEMA) { "launch-options-schema-invalid" }
    require(providerAuthority == packageName + AppLaunchOptionsContract.PROVIDER_AUTHORITY_SUFFIX) {
      "launch-options-authority-invalid"
    }
    require(ownerActivity == launchActivity) { "launch-options-owner-not-front-door" }
    return Triple(schema, providerAuthority, ownerActivity)
  }
}

internal data class AppLaunchOptionResolution(
  val state: AppLaunchOptionsUiState,
  val option: AppLaunchOption? = null,
)

internal data class AppLaunchOptionDispatchPlan(
  val target: LaunchTarget,
  val optionId: String,
)

internal object AppLaunchOptionDispatchPolicy {
  fun create(
    entry: CatalogEntry,
    binding: AppLaunchOptionsBinding,
    option: AppLaunchOption,
  ): AppLaunchOptionDispatchPlan {
    require(entry.installed) { "launch-option-app-not-installed" }
    val packageName = requireNotNull(entry.packageName) { "launch-option-package-missing" }
    val target = requireNotNull(entry.target) { "launch-option-front-door-missing" }
    require(packageName == binding.packageName && target.packageName == binding.packageName) {
      "launch-option-package-binding-invalid"
    }
    require(target.activityName == binding.ownerActivity) {
      "launch-option-owner-front-door-invalid"
    }
    require(
      option.optionId.isNotBlank() &&
        option.optionId.length <= AppLaunchOptionsContract.MAX_OPTION_ID_LENGTH
    ) { "launch-option-id-invalid" }
    return AppLaunchOptionDispatchPlan(target, option.optionId)
  }
}

internal class AppLaunchOptionsRepository(context: Context) {
  private val appContext = context.applicationContext
  private val packageManager = appContext.packageManager
  private val identityResolver = PackageSigningIdentityResolver(appContext)
  private val queryExecutor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "rusty-kiosk-launch-options-query").apply { isDaemon = true }
    }
  @Volatile private var queryTimedOut = false

  fun close() {
    queryExecutor.shutdownNow()
  }

  fun discover(entry: CatalogEntry?): AppLaunchOptionsUiState {
    if (entry?.installed != true || entry.target == null || entry.packageName == null) {
      return AppLaunchOptionsUiState()
    }
    return runCatching { discoverStrict(entry) }
      .getOrElse { throwable ->
        AppLaunchOptionsUiState(
          status = AppLaunchOptionsStatus.REJECTED,
          message = "Launch options were rejected: ${sanitizedReason(throwable)}.",
        )
      }
  }

  fun resolveForLaunch(entry: CatalogEntry, optionId: String): AppLaunchOptionResolution {
    if (optionId.isBlank() || optionId.length > AppLaunchOptionsContract.MAX_OPTION_ID_LENGTH) {
      return AppLaunchOptionResolution(
        AppLaunchOptionsUiState(
          status = AppLaunchOptionsStatus.REJECTED,
          message = "Launch options were rejected: launch-option-id-invalid.",
        )
      )
    }
    val fresh = discover(entry)
    if (fresh.status != AppLaunchOptionsStatus.READY || fresh.binding == null) {
      return AppLaunchOptionResolution(fresh)
    }
    val option = fresh.options.singleOrNull { it.optionId == optionId }
      ?: return AppLaunchOptionResolution(
        fresh.copy(
          status = AppLaunchOptionsStatus.REJECTED,
          message = "Launch options were rejected: selected-option-not-offered.",
          options = emptyList(),
          binding = null,
        )
      )
    return AppLaunchOptionResolution(fresh, option)
  }

  @Suppress("DEPRECATION")
  private fun discoverStrict(entry: CatalogEntry): AppLaunchOptionsUiState {
    val binding = resolveBinding(entry) ?: return AppLaunchOptionsUiState()
    val rows = queryRows(binding.packageName)
    val bindingAfterQuery = requireNotNull(resolveBinding(entry)) {
      "launch-options-capability-disappeared-during-query"
    }
    require(bindingAfterQuery == binding) {
      "launch-options-binding-changed-during-query"
    }
    val options = AppLaunchOptionsValidationPolicy.validateRows(rows)
    return AppLaunchOptionsUiState(
      status = AppLaunchOptionsStatus.READY,
      message =
        if (options.isEmpty()) "The selected app currently offers no launch options."
        else "${options.size} app-provided launch option${if (options.size == 1) "" else "s"} available.",
      options = options,
      binding = binding,
    )
  }

  @Suppress("DEPRECATION")
  private fun resolveBinding(entry: CatalogEntry): AppLaunchOptionsBinding? {
    val packageName = requireNotNull(entry.packageName)
    val target = requireNotNull(entry.target)
    require(target.packageName == packageName) { "launch-options-target-package-invalid" }
    val applicationInfo =
      packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    val metadata = applicationInfo.metaData
    val declared =
      AppLaunchOptionsValidationPolicy.validateMetadata(
        packageName = packageName,
        launchActivity = target.activityName,
        schema = metadata?.getString(AppLaunchOptionsContract.META_SCHEMA),
        providerAuthority = metadata?.getString(AppLaunchOptionsContract.META_PROVIDER_AUTHORITY),
        ownerActivity = metadata?.getString(AppLaunchOptionsContract.META_OWNER_ACTIVITY),
      ) ?: return null
    val authority = declared.second
    val ownerActivity = declared.third
    val provider = requireNotNull(packageManager.resolveContentProvider(authority, 0)) {
      "launch-options-provider-missing"
    }
    require(provider.packageName == packageName && provider.authority == authority) {
      "launch-options-provider-owner-invalid"
    }
    require(provider.enabled && provider.exported && !provider.grantUriPermissions) {
      "launch-options-provider-surface-invalid"
    }
    require(provider.applicationInfo?.uid == applicationInfo.uid) {
      "launch-options-provider-uid-invalid"
    }
    require(packageManager.getPackagesForUid(applicationInfo.uid)?.toList() == listOf(packageName)) {
      "launch-options-shared-uid-rejected"
    }
    val activity = packageManager.getActivityInfo(ComponentName(packageName, ownerActivity), 0)
    require(activity.packageName == packageName && activity.name == ownerActivity) {
      "launch-options-activity-owner-invalid"
    }
    require(activity.enabled && activity.exported && activity.applicationInfo.uid == applicationInfo.uid) {
      "launch-options-activity-surface-invalid"
    }
    val identity = requireNotNull(identityResolver.resolve(packageName)) {
      "launch-options-signing-identity-unavailable"
    }
    require(identity.uid == applicationInfo.uid) {
      "launch-options-installation-uid-invalid"
    }
    return AppLaunchOptionsBinding(
      packageName = packageName,
      uid = applicationInfo.uid,
      signingIdentity = identity.signingIdentity,
      lastUpdateTime = identity.lastUpdateTime,
      versionCode = identity.versionCode,
      providerAuthority = authority,
      providerClass = provider.name,
      ownerActivity = ownerActivity,
    )
  }

  private fun queryRows(packageName: String): List<RawAppLaunchOption> {
    check(!queryTimedOut) { "launch-options-query-timeout" }
    val cancellationSignal = CancellationSignal()
    val future = queryExecutor.submit<List<RawAppLaunchOption>> {
      queryRowsBlocking(packageName, cancellationSignal)
    }
    return try {
      future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      queryTimedOut = true
      future.cancel(true)
      Thread(
        { runCatching { cancellationSignal.cancel() } },
        "rusty-kiosk-launch-options-cancel",
      ).apply {
        isDaemon = true
        start()
      }
      throw IllegalStateException("launch-options-query-timeout")
    }
  }

  private fun queryRowsBlocking(
    packageName: String,
    cancellationSignal: CancellationSignal,
  ): List<RawAppLaunchOption> {
    val cursor = requireNotNull(
      appContext.contentResolver.query(
        AppLaunchOptionsContract.contentUri(packageName),
        AppLaunchOptionsContract.PROJECTION,
        null,
        null,
        null,
        cancellationSignal,
      )
    ) { "launch-options-query-null" }
    return cursor.use {
      require(it.columnNames.contentEquals(AppLaunchOptionsContract.PROJECTION)) {
        "launch-options-columns-invalid"
      }
      val rows = mutableListOf<RawAppLaunchOption>()
      while (it.moveToNext()) {
        require(rows.size < AppLaunchOptionsContract.MAX_OPTION_COUNT) {
          "launch-option-count-invalid"
        }
        require(
          it.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
            it.getType(1) == Cursor.FIELD_TYPE_STRING &&
            it.getType(2) == Cursor.FIELD_TYPE_STRING &&
            it.getType(3) == Cursor.FIELD_TYPE_STRING
        ) { "launch-options-column-types-invalid" }
        rows +=
          RawAppLaunchOption(
            schemaVersion = it.getInt(0),
            optionId = if (it.isNull(1)) null else it.getString(1),
            displayLabel = if (it.isNull(2)) null else it.getString(2),
            description = if (it.isNull(3)) null else it.getString(3),
          )
      }
      rows
    }
  }

  private fun sanitizedReason(throwable: Throwable): String =
    throwable.message
      ?.takeIf { it.matches(Regex("[a-z0-9-]{3,80}")) }
      ?: "capability-validation-failed"

  private companion object {
    const val QUERY_TIMEOUT_MS = 1_500L
  }
}

internal fun AppLaunchOption.stableDigest(): String =
  stableDigest(
    AppLaunchOptionsContract.SCHEMA,
    schemaVersion.toString(),
    optionId,
    displayLabel,
    description,
  )

internal fun AppLaunchOptionsBinding.stableDigest(): String =
  stableDigest(
    AppLaunchOptionsContract.SCHEMA,
    packageName,
    uid.toString(),
    signingIdentity,
    lastUpdateTime.toString(),
    versionCode.toString(),
    providerAuthority,
    providerClass,
    ownerActivity,
  )
