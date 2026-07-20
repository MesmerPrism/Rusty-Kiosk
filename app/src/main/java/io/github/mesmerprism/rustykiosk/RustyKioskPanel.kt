package io.github.mesmerprism.rustykiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
  onOpenAccessibilitySettings: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
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

    OutlinedTextField(
      value = state.searchQuery,
      onValueChange = onSearchChanged,
      label = { Text("Search apps, packages, or tags") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        onAddTag = onAddTag,
        onRemoveTag = onRemoveTag,
        onNormalLaunch = onNormalLaunch,
        onKioskLaunch = onKioskLaunch,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        modifier = Modifier.weight(0.54f),
      )
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
  Column(modifier = modifier.fillMaxHeight()) {
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
  onAddTag: (String) -> Unit,
  onRemoveTag: (String) -> Unit,
  onNormalLaunch: () -> Unit,
  onKioskLaunch: () -> Unit,
  onOpenAccessibilitySettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var tagInput by remember(entry?.key) { mutableStateOf("") }
  Column(
    modifier = modifier.fillMaxHeight().border(1.dp, Divider, RoundedCornerShape(8.dp)).padding(14.dp),
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
      OutlinedTextField(
        value = tagInput,
        onValueChange = { tagInput = it },
        label = { Text("Add tag") },
        singleLine = true,
        modifier = Modifier.weight(1.0f),
        shape = RoundedCornerShape(8.dp),
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
      modifier = Modifier.fillMaxWidth(),
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
      shape = RoundedCornerShape(8.dp),
    ) {
      Text("Normal launch")
    }
    Button(
      onClick = onKioskLaunch,
      enabled = entry.launchable && guardEnabled,
      modifier = Modifier.fillMaxWidth(),
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
        onClick = onOpenAccessibilitySettings,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text("Open Accessibility settings")
      }
    }

    Text(
      "The guard is inactive in Rusty Kiosk. Press Home here to open Meta Home normally.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private val Divider = Color(0xFF4A4945)
private val SelectedSurface = Color(0xFF34302A)
private val InstalledColor = Color(0xFFA9D6A5)
private val MissingColor = Color(0xFFFFB4A8)
