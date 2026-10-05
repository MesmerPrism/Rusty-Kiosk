package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.List;

/** A saved component is a selection hint only; it must survive the current visible catalogue. */
final class LiteBrowsingState {
  private LiteBrowsingState() {}

  static String acceptComponent(String value) {
    if (value == null || value.length() > 512) return null;
    return value.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+") ? value : null;
  }

  static LiteApp selected(String saved, List<LiteApp> visible) {
    String key = acceptComponent(saved);
    if (key != null) for (LiteApp app : visible) if (app.key().equals(key)) return app;
    return visible.isEmpty() ? null : visible.get(0);
  }
}
