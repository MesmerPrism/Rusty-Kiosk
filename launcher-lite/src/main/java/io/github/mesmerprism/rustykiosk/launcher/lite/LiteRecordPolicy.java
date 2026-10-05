package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

final class LiteRecordPolicy {
  static final int MAX_RECORDS = 256;

  private LiteRecordPolicy() {}

  static Set<String> retainInstalled(Collection<String> recorded, Collection<String> installed) {
    Set<String> acceptedInstalled = new TreeSet<>(installed);
    Set<String> retained = new TreeSet<>();
    for (String key : recorded) {
      if (acceptedInstalled.contains(key)) {
        retained.add(key);
      }
    }
    while (retained.size() > MAX_RECORDS) {
      retained.remove(retained.stream().reduce((first, second) -> second).orElseThrow());
    }
    return retained;
  }

  static Set<String> admit(Collection<String> recorded, String incoming) {
    Set<String> admitted = new TreeSet<>(recorded);
    admitted.add(incoming);
    while (admitted.size() > MAX_RECORDS) {
      String last = admitted.stream().reduce((first, second) -> second).orElseThrow();
      if (last.equals(incoming)) {
        String first = admitted.iterator().next();
        admitted.remove(first);
      } else {
        admitted.remove(last);
      }
    }
    return admitted;
  }
}
