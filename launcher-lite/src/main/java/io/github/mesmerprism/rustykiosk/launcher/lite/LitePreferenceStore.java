package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

final class LitePreferenceStore {
  private static final String FAVORITE_PREFIX = "favorite:";
  private static final String TAGS_PREFIX = "tags:";
  private static final String WIFI_PREFIX = "wifi:";
  private static final String SEARCH = "search";
  private static final String FAVORITES_ONLY = "favorites-only";
  private static final String SHOW_SYSTEM_APPS = "show-system-apps";
  private static final String SHOW_INTERNAL_ACTIVITIES = "show-internal-activities";
  private static final String SELECTED_COMPONENT = "selected-component";
  private static final String RECORD_KEYS = "record-keys";
  private final SharedPreferences preferences;

  LitePreferenceStore(Context context) {
    this(context.getSharedPreferences("launcher-lite-state", Context.MODE_PRIVATE));
  }

  LitePreferenceStore(SharedPreferences preferences) {
    this.preferences = preferences;
  }

  boolean isFavorite(String key) {
    return preferences.getBoolean(FAVORITE_PREFIX + key, false);
  }

  void setFavorite(String key, boolean favorite) {
    admitKey(key);
    preferences.edit().putBoolean(FAVORITE_PREFIX + key, favorite).apply();
  }

  Set<String> tags(String key) {
    Set<String> stored = preferences.getStringSet(TAGS_PREFIX + key, Collections.emptySet());
    Set<String> accepted = new TreeSet<>();
    for (String value : stored) {
      String normalized = LiteTagPolicy.normalize(value);
      if (normalized != null) {
        accepted.add(normalized);
      }
      if (accepted.size() == 12) {
        break;
      }
    }
    return accepted;
  }

  boolean addTag(String key, String tag) {
    admitKey(key);
    Set<String> updated = new HashSet<>(tags(key));
    if (updated.size() >= 12 && !updated.contains(tag)) {
      return false;
    }
    boolean changed = updated.add(tag);
    if (changed) {
      preferences.edit().putStringSet(TAGS_PREFIX + key, updated).apply();
    }
    return changed;
  }

  void removeTag(String key, String tag) {
    Set<String> updated = new HashSet<>(tags(key));
    if (updated.remove(tag)) {
      preferences.edit().putStringSet(TAGS_PREFIX + key, updated).apply();
    }
  }

  WifiRequirement wifiRequirement(String key) {
    return WifiRequirement.fromStored(preferences.getString(WIFI_PREFIX + key, "any"));
  }

  void setWifiRequirement(String key, WifiRequirement requirement) {
    admitKey(key);
    preferences.edit().putString(WIFI_PREFIX + key, requirement.storedValue).apply();
  }

  void pruneToCatalog(Set<String> installedKeys) {
    Set<String> recorded = recordedKeys();
    Set<String> retained = LiteRecordPolicy.retainInstalled(recorded, installedKeys);
    SharedPreferences.Editor editor = preferences.edit().putStringSet(RECORD_KEYS, retained);
    for (String key : recorded) {
      if (!retained.contains(key)) {
        editor.remove(FAVORITE_PREFIX + key);
        editor.remove(TAGS_PREFIX + key);
        editor.remove(WIFI_PREFIX + key);
      }
    }
    editor.apply();
  }

  String search() {
    String value = preferences.getString(SEARCH, "");
    return value == null || value.length() > 120 ? "" : value;
  }

  void setSearch(String value) {
    preferences.edit().putString(SEARCH, value.length() > 120 ? value.substring(0, 120) : value).apply();
  }

  boolean favoritesOnly() {
    return preferences.getBoolean(FAVORITES_ONLY, false);
  }

  void setFavoritesOnly(boolean value) {
    preferences.edit().putBoolean(FAVORITES_ONLY, value).apply();
  }

  String selectedComponent() {
    return LiteBrowsingState.acceptComponent(preferences.getString(SELECTED_COMPONENT, null));
  }

  boolean showSystemApps() {
    return preferences.getBoolean(SHOW_SYSTEM_APPS, false);
  }

  void setShowSystemApps(boolean value) {
    preferences.edit().putBoolean(SHOW_SYSTEM_APPS, value).apply();
  }

  boolean showInternalActivities() {
    return preferences.getBoolean(SHOW_INTERNAL_ACTIVITIES, false);
  }

  void setShowInternalActivities(boolean value) {
    preferences.edit().putBoolean(SHOW_INTERNAL_ACTIVITIES, value).apply();
  }

  void setSelectedComponent(String value) {
    preferences.edit().putString(SELECTED_COMPONENT, LiteBrowsingState.acceptComponent(value)).apply();
  }

  private Set<String> recordedKeys() {
    return new TreeSet<>(preferences.getStringSet(RECORD_KEYS, Collections.emptySet()));
  }

  private void admitKey(String key) {
    Set<String> prior = recordedKeys();
    Set<String> admitted = LiteRecordPolicy.admit(prior, key);
    SharedPreferences.Editor editor = preferences.edit().putStringSet(RECORD_KEYS, admitted);
    for (String evicted : prior) {
      if (!admitted.contains(evicted)) {
        editor.remove(FAVORITE_PREFIX + evicted);
        editor.remove(TAGS_PREFIX + evicted);
        editor.remove(WIFI_PREFIX + evicted);
      }
    }
    editor.apply();
  }
}
