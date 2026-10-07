package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class LitePreferenceStoreTest {
  @Test public void recreationRestoresFiltersAndExactSelectionWithoutChangingExistingKeys() {
    Map<String, Object> persisted = new HashMap<>();
    SharedPreferences preferences = preferences(persisted);
    LitePreferenceStore original = new LitePreferenceStore(preferences);
    assertFalse(original.favoritesOnly());
    assertFalse(original.showSystemApps());
    assertFalse(original.showInternalActivities());
    assertNull(original.selectedComponent());
    String key = "com.example/com.example.Main";
    original.setSearch("\"Spatial Camera\" video");
    original.setFavoritesOnly(true);
    original.setShowSystemApps(true);
    original.setShowInternalActivities(true);
    original.setSelectedComponent(key);
    original.setFavorite(key, true);
    original.addTag(key, "camera");
    original.setWifiRequirement(key, WifiRequirement.ON);
    LitePreferenceStore recreated = new LitePreferenceStore(preferences);
    assertEquals("\"Spatial Camera\" video", recreated.search());
    assertTrue(recreated.favoritesOnly());
    assertTrue(recreated.showSystemApps());
    assertTrue(recreated.showInternalActivities());
    assertEquals(key, recreated.selectedComponent());
    assertTrue(recreated.isFavorite(key));
    assertTrue(recreated.tags(key).contains("camera"));
    assertEquals(WifiRequirement.ON, recreated.wifiRequirement(key));
    assertTrue(persisted.containsKey("favorite:" + key));
    assertTrue(persisted.containsKey("tags:" + key));
    assertTrue(persisted.containsKey("wifi:" + key));
    recreated.setFavoritesOnly(false);
    recreated.setShowSystemApps(false);
    recreated.setShowInternalActivities(false);
    recreated.setSelectedComponent(null);
    assertFalse(original.favoritesOnly());
    assertFalse(original.showSystemApps());
    assertFalse(original.showInternalActivities());
    assertNull(original.selectedComponent());
  }

  @Test public void malformedSavedComponentCannotBecomeSelectionAuthority() {
    Map<String, Object> values = new HashMap<>();
    values.put("selected-component", "content://attacker/Main");
    assertNull(new LitePreferenceStore(preferences(values)).selectedComponent());
  }

  @Test public void hidingSystemEntriesPreservesRecordsAndSearchAcrossRecreation() {
    SharedPreferences preferences = preferences(new HashMap<>());
    LitePreferenceStore store = new LitePreferenceStore(preferences);
    String key = "com.android.settings/com.android.settings.Settings";
    store.setShowSystemApps(true);
    store.setFavorite(key, true);
    store.addTag(key, "tools");
    store.setWifiRequirement(key, WifiRequirement.OFF);
    store.setSearch("tools");
    store.setShowSystemApps(false);
    store.pruneToCatalog(Set.of(key));
    LitePreferenceStore recreated = new LitePreferenceStore(preferences);
    assertFalse(recreated.showSystemApps());
    assertTrue(recreated.isFavorite(key));
    assertEquals(Set.of("tools"), recreated.tags(key));
    assertEquals(WifiRequirement.OFF, recreated.wifiRequirement(key));
    assertEquals("tools", recreated.search());
  }

  // A tiny in-memory interface adapter verifies persisted keys, without Android framework calls.
  private static SharedPreferences preferences(Map<String, Object> values) {
    ClassLoader loader = LitePreferenceStoreTest.class.getClassLoader();
    return (SharedPreferences) Proxy.newProxyInstance(loader, new Class<?>[]{SharedPreferences.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if (name.equals("edit")) {
            return Proxy.newProxyInstance(loader, new Class<?>[]{SharedPreferences.Editor.class},
                (editor, action, data) -> {
                  if (action.getName().startsWith("put")) {
                    if (data[1] == null) values.remove(data[0]); else values.put((String) data[0], data[1]);
                    return editor;
                  }
                  if (action.getName().equals("remove")) { values.remove(data[0]); return editor; }
                  if (action.getName().equals("commit")) return true;
                  if (action.getName().equals("apply")) return null;
                  throw new UnsupportedOperationException(action.getName());
                });
          }
          if (name.startsWith("get") && args != null && args.length == 2) {
            return values.getOrDefault(args[0], args[1]);
          }
          throw new UnsupportedOperationException(name);
        });
  }
}
