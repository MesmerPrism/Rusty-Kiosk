package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class LiteBrowsingStateTest {
  private final LiteApp first = new LiteApp("First", "com.first", "com.first.Main", "launcher");
  private final LiteApp second = new LiteApp("Second", "com.second", "com.second.Main", "launcher");
  @Test public void restoresExactVisibleComponent() {
    assertSame(second, LiteBrowsingState.selected(second.key(), List.of(first, second)));
  }
  @Test public void filteredOrMissingSelectionFallsBackToVisibleOnly() {
    assertSame(first, LiteBrowsingState.selected(second.key(), List.of(first)));
    assertNull(LiteBrowsingState.selected(second.key(), List.of()));
  }
  @Test public void rejectsMalformedStoredSelection() {
    assertNull(LiteBrowsingState.acceptComponent("content://com.second/Main"));
    assertNull(LiteBrowsingState.acceptComponent("com.second/Main/other"));
    assertNull(LiteBrowsingState.acceptComponent("x".repeat(513)));
    assertNull(LiteBrowsingState.acceptComponent(null));
    assertEquals(second.key(), LiteBrowsingState.acceptComponent(second.key()));
  }
}
