package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class LiteCatalogVisibilityTest {
  private static final String LAUNCHER = "android.intent.category.LAUNCHER";
  private static final String VR = "com.oculus.intent.category.VR";

  @Test public void publicBrowserAndSideloadedVrAppRemainDefaultVisible() {
    LiteApp browser = app("com.oculus.browser", "OculusLauncherActivity", true, LAUNCHER);
    LiteApp sideloaded = app("com.example.vr", "Main", false, VR);
    LiteApp prefixLookalike = app("com.oculus.browser.other", "Main", true, LAUNCHER);
    LiteCatalogVisibility visibility = new LiteCatalogVisibility(List.of(browser, sideloaded, prefixLookalike));
    assertTrue(visibility.visible(browser, false, false));
    assertTrue(visibility.visible(sideloaded, false, false));
    assertFalse(visibility.visible(prefixLookalike, false, false));
  }

  @Test public void standardCategoryDoesNotTurnKnownSystemDialogsIntoNormalApps() {
    LiteApp shell = app("com.oculus.vrshell", "HomeActivity", true, LAUNCHER);
    LiteApp dialog = app("com.oculus.systemux", "DialogActivity", false, LAUNCHER);
    LiteApp mediaPlayer = app("com.oculus.horizonmediaplayer", "HorizonMediaPlayerActivity", true, LAUNCHER);
    LiteApp playground = app("com.oculus.horizonmediaplayer", "SpatializerPlayground", true, LAUNCHER);
    LiteApp settings = app("com.oculus.panelapp.settings", "SettingsActivity", false, LAUNCHER);
    LiteApp settingsModal = app("com.oculus.panelapp.settings", "SettingsModalWrapperActivity", false, LAUNCHER);
    LiteCatalogVisibility visibility = new LiteCatalogVisibility(
        List.of(shell, dialog, mediaPlayer, playground, settings, settingsModal));
    assertTrue(visibility.visible(mediaPlayer, false, false));
    for (LiteApp internal : List.of(shell, dialog, playground, settingsModal)) {
      assertFalse(visibility.visible(internal, true, false));
      assertTrue(visibility.visible(internal, false, true));
    }
    assertFalse(visibility.visible(settings, false, true));
    assertTrue(visibility.visible(settings, true, false));
  }

  @Test public void accountAndPrivacyUtilitiesDoNotNeedTheAndroidSystemFlag() {
    LiteApp accounts = app("com.oculus.accountscenter", "AccountsCenterActivity", false, LAUNCHER);
    LiteApp privacy = app("com.oculus.vrprivacycheckup", "VrPrivacyCheckupActivity", false, LAUNCHER);
    LiteCatalogVisibility visibility = new LiteCatalogVisibility(List.of(accounts, privacy));
    for (LiteApp utility : List.of(accounts, privacy)) {
      assertFalse(visibility.visible(utility, false, true));
      assertTrue(visibility.visible(utility, true, false));
    }
  }

  @Test public void linkInterruptionAndPeopleShelfAreInternalWhileMainControlsAreSystem() {
    LiteApp link = app("com.oculus.xrstreamingclient", "MainActivity", true, LAUNCHER);
    LiteApp interruption = app("com.oculus.xrstreamingclient", "NetworkInterruptionActivity", true, LAUNCHER);
    LiteApp people = new LiteApp("People", "com.oculus.socialplatform",
        "com.oculus.people.app.PeopleActivity", LAUNCHER, true, Set.of(LAUNCHER));
    LiteApp shelf = new LiteApp("Shelf", "com.oculus.socialplatform",
        "com.oculus.panelapp.people.PeopleShelfActivity", LAUNCHER, true, Set.of(LAUNCHER));
    LiteApp companion = new LiteApp("Companion", "com.oculus.socialplatform",
        "com.oculus.btCompanion.MainActivity", LAUNCHER, true, Set.of(LAUNCHER));
    LiteCatalogVisibility visibility = new LiteCatalogVisibility(List.of(link, interruption, people, shelf, companion));
    for (LiteApp system : List.of(link, people)) {
      assertTrue(visibility.visible(system, true, false));
      assertFalse(visibility.visible(system, false, true));
    }
    for (LiteApp internal : List.of(interruption, shelf, companion)) {
      assertTrue(visibility.visible(internal, false, true));
      assertFalse(visibility.visible(internal, true, false));
    }
  }

  @Test public void systemSettingsAndInternalActivitiesHaveIndependentToggles() {
    LiteApp settings = app("com.android.settings", "Settings", true, LAUNCHER);
    LiteApp systemVr = app("com.oculus.system", "Internal", true, VR);
    LiteApp frontDoor = app("com.example", "Main", false, LAUNCHER);
    LiteApp auxiliary = app("com.example", "Internal", false, VR);
    LiteCatalogVisibility visibility = new LiteCatalogVisibility(List.of(settings, systemVr, frontDoor, auxiliary));
    assertEquals(LiteCatalogVisibility.Kind.SYSTEM, visibility.kind(settings));
    assertEquals(LiteCatalogVisibility.Kind.INTERNAL, visibility.kind(systemVr));
    assertEquals(LiteCatalogVisibility.Kind.INTERNAL, visibility.kind(auxiliary));
    assertFalse(visibility.visible(settings, false, true));
    assertTrue(visibility.visible(settings, true, false));
    assertFalse(visibility.visible(systemVr, true, false));
    assertTrue(visibility.visible(systemVr, false, true));
    assertFalse(visibility.visible(auxiliary, false, false));
    assertTrue(visibility.visible(frontDoor, false, false));
  }

  @Test public void duplicateQueriesMergeCategoriesWithoutCreatingInternalFrontDoor() {
    Map<String, LiteApp> catalogue = new LinkedHashMap<>();
    assertTrue(admit(catalogue, VR));
    assertFalse(admit(catalogue, LAUNCHER));
    LiteApp merged = catalogue.values().iterator().next();
    assertEquals(Set.of(VR, LAUNCHER), merged.categories);
    assertEquals(VR, merged.category);
    assertEquals(LiteCatalogVisibility.Kind.APP, new LiteCatalogVisibility(List.of(merged)).kind(merged));
  }

  @Test public void hiddenSelectionFallsBackWithoutRemovingFavoriteOrTags() {
    LiteApp normal = app("com.example", "Main", false, LAUNCHER);
    LiteApp settings = app("com.android.settings", "Settings", true, LAUNCHER);
    LiteCatalogVisibility visibility = new LiteCatalogVisibility(List.of(normal, settings));
    List<LiteApp> visible = List.of(normal, settings).stream()
        .filter(app -> visibility.visible(app, false, false)).toList();
    assertSame(normal, LiteBrowsingState.selected(settings.key(), visible));
    assertTrue(normal.matches("favorite", Set.of("favorite")));
    // Pruning still uses the full installed catalogue, including hidden entries.
    assertEquals(Set.of(settings.key()), LiteRecordPolicy.retainInstalled(
        Set.of(settings.key()), Set.of(normal.key(), settings.key())));
  }

  private static boolean admit(Map<String, LiteApp> catalogue, String category) {
    return LiteCatalogPolicy.admit(catalogue, "Demo", "com.example", "Main", category,
        true, true, true, "lite", false);
  }

  private static LiteApp app(String pkg, String activity, boolean system, String category) {
    return new LiteApp(activity, pkg, pkg + "." + activity, category, system, Set.of(category));
  }
}
