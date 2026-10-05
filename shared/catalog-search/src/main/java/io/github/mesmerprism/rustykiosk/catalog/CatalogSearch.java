package io.github.mesmerprism.rustykiosk.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared installed-app search semantics for the Kiosk and Lite catalogues. */
public final class CatalogSearch {
  private CatalogSearch() {}

  public static boolean matches(String query, Iterable<String> fields) {
    List<Term> terms = parse(query == null ? "" : query);
    List<String> normalized = new ArrayList<>();
    for (String field : fields) normalized.add(normalize(field == null ? "" : field));
    for (Term term : terms) {
      boolean found = false;
      for (String field : normalized) {
        if ((term.phrase ? phrase(field) : field).contains(term.value)) {
          found = true;
          break;
        }
      }
      if (!found) return false;
    }
    return true;
  }

  private static List<Term> parse(String query) {
    List<Term> terms = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < query.length(); i++) {
      char character = query.charAt(i);
      if (character == '"') {
        flush(terms, token, quoted);
        quoted = !quoted;
      } else if (quoted || Character.isLetterOrDigit(character)) {
        token.append(character);
      } else {
        flush(terms, token, quoted);
      }
    }
    flush(terms, token, quoted);
    return terms;
  }

  private static void flush(List<Term> terms, StringBuilder token, boolean quoted) {
    String value = quoted ? phrase(token.toString()) : normalize(token.toString());
    if (!value.isEmpty()) terms.add(new Term(value, quoted));
    token.setLength(0);
  }

  private static String normalize(String value) {
    // Match Kotlin Char.isWhitespace(), including non-breaking space at the edges.
    int start = 0;
    int end = value.length();
    while (start < end && whitespace(value.charAt(start))) start++;
    while (end > start && whitespace(value.charAt(end - 1))) end--;
    return value.substring(start, end).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static boolean whitespace(char value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value);
  }

  private static String phrase(String value) {
    return normalize(value).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  private static final class Term {
    final String value;
    final boolean phrase;
    Term(String value, boolean phrase) { this.value = value; this.phrase = phrase; }
  }
}
