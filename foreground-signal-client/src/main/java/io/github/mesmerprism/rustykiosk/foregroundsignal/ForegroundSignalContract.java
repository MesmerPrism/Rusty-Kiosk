package io.github.mesmerprism.rustykiosk.foregroundsignal;

import android.net.Uri;

/** Stable protocol constants shared by Rusty Kiosk and engine-specific foreground applications. */
public final class ForegroundSignalContract {
  public static final int PROTOCOL_VERSION = 2;
  public static final String CAPABILITY_METADATA =
      "io.github.mesmerprism.rustykiosk.FOREGROUND_SIGNAL_PROTOCOL";
  public static final String PROVIDER_AUTHORITY =
      "io.github.mesmerprism.rustykiosk.foreground-signal";
  public static final Uri PROVIDER_URI =
      Uri.parse("content://" + PROVIDER_AUTHORITY);
  public static final String METHOD_FOREGROUND_LOST = "foreground-lost";
  public static final String EXTRA_PROTOCOL_VERSION = "protocol_version";
  public static final String EXTRA_SIGNAL_ELAPSED_REALTIME_NANOS =
      "signal_elapsed_realtime_nanos";
  public static final String EXTRA_SIGNAL_SOURCE = "signal_source";
  public static final String RESPONSE_ACCEPTED = "accepted";
  public static final String RESPONSE_STATUS = "status";

  private ForegroundSignalContract() {}
}
