package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.TreeSet;

/** Closed app-owned protocol; a row carries an opaque ID, never launch authority. */
final class LiteLaunchOptionsPolicy {
  static final String SCHEMA = "rusty.quest.app_launch_options.v1";
  static final String AUTHORITY_SUFFIX = ".app-launch-options";
  static final String EXTRA_OPTION_ID =
      "io.github.mesmerprism.rustyquest.spatial_camera_panel.extra.LAUNCH_OPTION_ID";
  static final String[] PROJECTION = {"schema_version", "option_id", "display_label", "description"};
  static final int MAX_COUNT = 64;

  private LiteLaunchOptionsPolicy() {}

  static boolean metadata(String pkg, String activity, String schema, String authority, String owner) {
    if (schema == null && authority == null && owner == null) return false;
    require(SCHEMA.equals(schema), "launch-options-schema-invalid");
    require((pkg + AUTHORITY_SUFFIX).equals(authority), "launch-options-authority-invalid");
    require(activity.equals(owner), "launch-options-owner-not-front-door");
    return true;
  }

  static List<Option> validateRows(List<Option> rows) {
    require(rows.size() <= MAX_COUNT, "launch-option-count-invalid");
    HashSet<String> ids = new HashSet<>();
    for (Option row : rows) {
      require(row != null && row.schemaVersion == 1, "launch-option-schema-version-invalid");
      require(text(row.optionId, 160, false), "launch-option-id-invalid");
      require(text(row.displayLabel, 96, false), "launch-option-label-invalid");
      require(text(row.description, 160, true), "launch-option-description-invalid");
      require(ids.add(row.optionId), "launch-option-id-duplicate");
    }
    return List.copyOf(rows);
  }

  static int schemaVersion(long value) {
    require(value == 1L, "launch-option-schema-version-invalid");
    return 1;
  }

  static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  static void exclusiveUid(String packageName, String[] packagesForUid) {
    require(Arrays.equals(packagesForUid, new String[]{packageName}), "launch-options-shared-uid-rejected");
  }

  static String signingIdentity(boolean multipleSigners, int currentCount, List<byte[]> lineage) {
    require(!multipleSigners && currentCount == 1 && !lineage.isEmpty(),
        "launch-options-signing-identity-unavailable");
    TreeSet<String> digests = new TreeSet<>();
    for (byte[] certificate : lineage) {
      require(certificate != null && certificate.length > 0, "launch-options-signing-identity-unavailable");
      digests.add(SignerDigest.sha256(certificate));
    }
    return String.join(",", digests);
  }

  private static boolean text(String value, int maximum, boolean blankAllowed) {
    return value != null && value.length() <= maximum && (blankAllowed || !value.isBlank());
  }

  static final class Option {
    final int schemaVersion;
    final String optionId;
    final String displayLabel;
    final String description;
    Option(int schemaVersion, String optionId, String displayLabel, String description) {
      this.schemaVersion = schemaVersion;
      this.optionId = optionId;
      this.displayLabel = displayLabel;
      this.description = description;
    }
    @Override public boolean equals(Object other) {
      if (!(other instanceof Option)) return false;
      Option row = (Option) other;
      return schemaVersion == row.schemaVersion && Objects.equals(optionId, row.optionId)
          && Objects.equals(displayLabel, row.displayLabel) && Objects.equals(description, row.description);
    }
    @Override public int hashCode() {
      return Objects.hash(schemaVersion, optionId, displayLabel, description);
    }
  }

  static final class Binding {
    final LiteInstalledIdentity identity;
    final String providerAuthority;
    final String providerClass;
    Binding(LiteInstalledIdentity identity, String authority, String providerClass) {
      this.identity = identity;
      this.providerAuthority = authority;
      this.providerClass = providerClass;
    }
    @Override public boolean equals(Object other) {
      if (!(other instanceof Binding)) return false;
      Binding binding = (Binding) other;
      return identity.equals(binding.identity) && providerAuthority.equals(binding.providerAuthority)
          && providerClass.equals(binding.providerClass);
    }
    @Override public int hashCode() { return Objects.hash(identity, providerAuthority, providerClass); }
  }

  static boolean accepts(Binding expected, Option selected, Binding current, List<Option> offered) {
    return expected != null && expected.equals(current) && selected != null
        && offered.stream().filter(selected::equals).count() == 1;
  }
}
