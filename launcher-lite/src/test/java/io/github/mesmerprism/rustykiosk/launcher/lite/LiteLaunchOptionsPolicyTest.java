package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class LiteLaunchOptionsPolicyTest {
  private final LiteLaunchOptionsPolicy.Option option = new LiteLaunchOptionsPolicy.Option(1, "camera-a", "Camera A", "");
  private LiteLaunchOptionsPolicy.Binding binding(long version, long updated, int uid, String signer, String provider, String activity) {
    return new LiteLaunchOptionsPolicy.Binding(new LiteInstalledIdentity("com.example", activity, "launcher", version, updated, uid, signer),
        "com.example.app-launch-options", provider);
  }
  @Test public void absentCapabilityIsDistinctFromMalformedMetadata() {
    assertFalse(LiteLaunchOptionsPolicy.metadata("com.example", "Main", null, null, null));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.metadata("com.example", "Main", null,
        "com.example.app-launch-options", "Main"));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.metadata("com.example", "Main",
        LiteLaunchOptionsPolicy.SCHEMA, "other.app-launch-options", "Main"));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.metadata("com.example", "Main",
        LiteLaunchOptionsPolicy.SCHEMA, "com.example.app-launch-options", "Other"));
  }
  @Test public void boundedClosedRowsRejectDuplicateMissingAndOversizedData() {
    assertEquals(List.of(option), LiteLaunchOptionsPolicy.validateRows(List.of(option)));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.validateRows(List.of(option, option)));
    for (LiteLaunchOptionsPolicy.Option bad : List.of(
        new LiteLaunchOptionsPolicy.Option(2, "id", "Label", ""),
        new LiteLaunchOptionsPolicy.Option(1, null, "Label", ""),
        new LiteLaunchOptionsPolicy.Option(1, " ", "Label", ""),
        new LiteLaunchOptionsPolicy.Option(1, "x".repeat(161), "Label", ""),
        new LiteLaunchOptionsPolicy.Option(1, "id", "", ""),
        new LiteLaunchOptionsPolicy.Option(1, "id", "x".repeat(97), ""),
        new LiteLaunchOptionsPolicy.Option(1, "id", "Label", null),
        new LiteLaunchOptionsPolicy.Option(1, "id", "Label", "x".repeat(161)))) {
      assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.validateRows(List.of(bad)));
    }
    List<LiteLaunchOptionsPolicy.Option> tooMany = new ArrayList<>();
    for (int i = 0; i < 65; i++) tooMany.add(new LiteLaunchOptionsPolicy.Option(1, "id" + i, "Label", ""));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.validateRows(tooMany));
  }
  @Test public void everyIdentityFieldAndExactDisplayedRowMustSurviveDispatch() {
    LiteLaunchOptionsPolicy.Binding original = binding(1, 100, 42, "signer", "Provider", "Main");
    assertTrue(LiteLaunchOptionsPolicy.accepts(original, option, original, List.of(option)));
    for (LiteLaunchOptionsPolicy.Binding changed : List.of(
        binding(2, 100, 42, "signer", "Provider", "Main"),
        binding(1, 101, 42, "signer", "Provider", "Main"),
        binding(1, 100, 43, "signer", "Provider", "Main"),
        binding(1, 100, 42, "other", "Provider", "Main"),
        binding(1, 100, 42, "signer", "Replacement", "Main"),
        binding(1, 100, 42, "signer", "Provider", "Other"))) {
      assertFalse(LiteLaunchOptionsPolicy.accepts(original, option, changed, List.of(option)));
    }
    assertFalse(LiteLaunchOptionsPolicy.accepts(original, option, original, List.of()));
    assertFalse(LiteLaunchOptionsPolicy.accepts(original, option, original,
        List.of(new LiteLaunchOptionsPolicy.Option(1, option.optionId, "Changed label", ""))));
    assertFalse(LiteLaunchOptionsPolicy.accepts(null, option, original, List.of(option)));
  }

  @Test public void sharedUidAndMultipleOrMissingSignersFailClosed() {
    LiteLaunchOptionsPolicy.exclusiveUid("com.example", new String[]{"com.example"});
    for (String[] packages : new String[][]{null, {}, {"other"}, {"com.example", "other"}}) {
      assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.exclusiveUid("com.example", packages));
    }
    byte[] signer = {1, 2};
    assertEquals(SignerDigest.sha256(signer), LiteLaunchOptionsPolicy.signingIdentity(false, 1, List.of(signer)));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.signingIdentity(true, 1, List.of(signer)));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.signingIdentity(false, 2, List.of(signer)));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.signingIdentity(false, 0, List.of(signer)));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.signingIdentity(false, 1, List.of()));
    assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.signingIdentity(false, 1, List.of(new byte[0])));
  }

  @Test public void providerIntegerCannotTruncateIntoAcceptedSchemaVersion() {
    assertEquals(1, LiteLaunchOptionsPolicy.schemaVersion(1L));
    for (long value : new long[]{0, -1, 2, 4294967297L, Long.MIN_VALUE, Long.MAX_VALUE}) {
      assertThrows(IllegalStateException.class, () -> LiteLaunchOptionsPolicy.schemaVersion(value));
    }
  }
}
