package io.github.mesmerprism.rustykiosk.launcher.lite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class LiteAppTest {
  private final LiteApp app =
      new LiteApp("Spatial Browser", "com.example.browser", "com.example.Main", "launcher");

  @Test
  public void searchMatchesLabelPackageAndLocalTag() {
    assertTrue(app.matches("spatial", List.of("web", "favorite")));
    assertTrue(app.matches("example.browser", List.of("web")));
    assertTrue(app.matches("WEB", List.of("web")));
    assertFalse(app.matches("camera", List.of("web")));
  }

  @Test
  public void blankSearchMatchesEveryApp() {
    assertTrue(app.matches("  ", List.of()));
  }

  @Test
  public void termsMayMatchDifferentFieldsButEveryTermIsRequired() {
    assertTrue(app.matches("spatial/example_WEB", List.of("web")));
    assertFalse(app.matches("spatial missing", List.of("web")));
  }

  @Test
  public void phrasesNormalizeSeparatorsAndStayWithinOneField() {
    assertTrue(app.matches("\"spatial-browser\"", List.of()));
    assertTrue(app.matches("\"example browser\"", List.of()));
    assertFalse(app.matches("\"browser web\"", List.of("web")));
    assertTrue(app.matches("\"SPATIAL BROWSER\" web", List.of("web")));
    assertTrue(app.matches("\"spatial browser", List.of()));
  }

  @Test
  public void separatorsAndEmptyQuotesHaveNoTerms() {
    assertTrue(app.matches("--- / \"\"", List.of()));
    LiteApp unicode = new LiteApp("Über Café", "com.example", "com.example.Main", "launcher");
    assertTrue(unicode.matches("ÜBER café", List.of()));
  }
}
