package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class LitePreferenceStoreTest {
  @Test public void recreationRestoresFiltersAndExactSelectionWithoutChangingExistingKeys() {
    Map<String, Object> persisted = new HashMap<>();
    SharedPreferences preferences = preferences(persisted);
    LitePreferenceStore original = new LitePreferenceStore(preferences);
    assertFalse(original.favoritesOnly());
    assertNull(original.selectedComponent());
    String key = "com.example/com.example.Main";
    original.setSearch("\"Spatial Camera\" video");
    original.setFavoritesOnly(true);
    original.setSelectedComponent(key);
    original.setFavorite(key, true);
    original.addTag(key, "camera");
    original.setWifiRequirement(key, WifiRequirement.ON);
    LitePreferenceStore recreated = new LitePreferenceStore(preferences);
    assertEquals("\"Spatial Camera\" video", recreated.search());
    assertTrue(recreated.favoritesOnly());
    assertEquals(key, recreated.selectedComponent());
    assertTrue(recreated.isFavorite(key));
    assertTrue(recreated.tags(key).contains("camera"));
    assertEquals(WifiRequirement.ON, recreated.wifiRequirement(key));
    assertTrue(persisted.containsKey("favorite:" + key));
    assertTrue(persisted.containsKey("tags:" + key));
    assertTrue(persisted.containsKey("wifi:" + key));
    recreated.setFavoritesOnly(false);
    recreated.setSelectedComponent(null);
    assertFalse(original.favoritesOnly());
    assertNull(original.selectedComponent());
  }

  @Test public void malformedSavedComponentCannotBecomeSelectionAuthority() {
    Map<String, Object> values = new HashMap<>();
    values.put("selected-component", "content://attacker/Main");
    assertNull(new LitePreferenceStore(preferences(values)).selectedComponent());
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
