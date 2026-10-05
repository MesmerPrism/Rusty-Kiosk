package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class LiteRecordPolicyTest {
  @Test
  public void removesAbsentRecordsAndCapsNewAdmission() {
    assertEquals(Set.of("a"), LiteRecordPolicy.retainInstalled(Set.of("a", "b"), Set.of("a", "c")));
    List<String> full = new ArrayList<>();
    for (int index = 0; index < LiteRecordPolicy.MAX_RECORDS; index += 1) {
      full.add(String.format("key-%03d", index));
    }
    Set<String> admitted = LiteRecordPolicy.admit(full, "new-key");
    assertEquals(LiteRecordPolicy.MAX_RECORDS, admitted.size());
    assertTrue(admitted.contains("new-key"));
    assertFalse(admitted.contains("key-000"));
  }
}
