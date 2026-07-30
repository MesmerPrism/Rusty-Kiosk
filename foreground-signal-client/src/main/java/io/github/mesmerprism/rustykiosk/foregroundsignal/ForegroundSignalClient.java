package io.github.mesmerprism.rustykiosk.foregroundsignal;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Engine-neutral client for the advisory Rusty Kiosk foreground fast path.
 *
 * <p>Call this only from an app-owned aggregate signal that the application, rather than one
 * Activity, has lost foreground authority. The signal requests recovery only: it is not proof that
 * Home was pressed and never contributes to Kiosk's multi-Home escape gesture. The method returns
 * false when Kiosk is absent, inactive, or rejects the caller. Accessibility remains the fallback
 * and the sole Home-transition authority.
 */
public final class ForegroundSignalClient {
  private ForegroundSignalClient() {}

  public static boolean notifyApplicationForegroundLost(Context context) {
    return notifyApplicationForegroundLost(context, "application-foreground-callback");
  }

  public static boolean notifyApplicationForegroundLost(Context context, String source) {
    if (context == null) {
      return false;
    }
    Bundle extras = new Bundle();
    extras.putInt(
        ForegroundSignalContract.EXTRA_PROTOCOL_VERSION,
        ForegroundSignalContract.PROTOCOL_VERSION);
    extras.putLong(
        ForegroundSignalContract.EXTRA_SIGNAL_ELAPSED_REALTIME_NANOS,
        SystemClock.elapsedRealtimeNanos());
    extras.putString(
        ForegroundSignalContract.EXTRA_SIGNAL_SOURCE,
        normalizeSource(source));
    try {
      Bundle response =
          context
              .getApplicationContext()
              .getContentResolver()
              .call(
                  ForegroundSignalContract.PROVIDER_URI,
                  ForegroundSignalContract.METHOD_FOREGROUND_LOST,
                  Integer.toString(ForegroundSignalContract.PROTOCOL_VERSION),
                  extras);
      return response != null
          && response.getBoolean(ForegroundSignalContract.RESPONSE_ACCEPTED, false);
    } catch (RuntimeException unavailableOrRejected) {
      return false;
    }
  }

  private static String normalizeSource(String source) {
    if (source == null || source.trim().isEmpty()) {
      return "application-foreground-callback";
    }
    String normalized = source.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
  }
}
