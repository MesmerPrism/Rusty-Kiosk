package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.app.Activity;
import android.os.Bundle;

/** Default Horizon OS window. All product handlers live in the shared panel controller. */
public final class RustyLauncherLiteActivity extends Activity implements LitePresentationHost {
  private LitePanelController panel;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_rusty_launcher_lite);
    panel = new LitePanelController(this, findViewById(R.id.lite_panel_root), this);
  }

  @Override protected void onResume() { super.onResume(); if (panel != null) panel.onResume(); }
  @Override protected void onPause() { if (panel != null) panel.onPause(); super.onPause(); }
  @Override public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (!focused && panel != null) panel.onFocusLost();
  }
  @Override protected void onDestroy() { if (panel != null) panel.release(); super.onDestroy(); }
  @Override public boolean isImmersive() { return false; }
  @Override public String mode() { return "window"; }
  @Override public void switchPresentation() { LiteHybridNavigator.launchImmersive(this); }
}
