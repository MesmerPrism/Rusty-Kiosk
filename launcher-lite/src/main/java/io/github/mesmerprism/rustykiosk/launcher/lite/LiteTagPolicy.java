package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.Locale;

final class LiteTagPolicy {
  private LiteTagPolicy() {}

  static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    if (!normalized.matches("[a-z0-9][a-z0-9-]{0,23}")) {
      return null;
    }
    return normalized;
  }
}
