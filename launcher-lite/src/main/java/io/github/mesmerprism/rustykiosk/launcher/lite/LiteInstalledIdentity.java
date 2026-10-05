package io.github.mesmerprism.rustykiosk.launcher.lite;

import java.util.Objects;

final class LiteInstalledIdentity {
  final String packageName;
  final String activityName;
  final String category;
  final long versionCode;
  final long lastUpdateTime;
  final int uid;
  final String currentSignerFingerprint;

  LiteInstalledIdentity(
      String packageName,
      String activityName,
      String category,
      long versionCode,
      long lastUpdateTime,
      int uid,
      String currentSignerFingerprint) {
    this.packageName = Objects.requireNonNull(packageName);
    this.activityName = Objects.requireNonNull(activityName);
    this.category = Objects.requireNonNull(category);
    this.versionCode = versionCode;
    this.lastUpdateTime = lastUpdateTime;
    this.uid = uid;
    this.currentSignerFingerprint = Objects.requireNonNull(currentSignerFingerprint);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof LiteInstalledIdentity)) {
      return false;
    }
    LiteInstalledIdentity identity = (LiteInstalledIdentity) other;
    return versionCode == identity.versionCode
        && lastUpdateTime == identity.lastUpdateTime
        && uid == identity.uid
        && packageName.equals(identity.packageName)
        && activityName.equals(identity.activityName)
        && category.equals(identity.category)
        && currentSignerFingerprint.equals(identity.currentSignerFingerprint);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        packageName,
        activityName,
        category,
        versionCode,
        lastUpdateTime,
        uid,
        currentSignerFingerprint);
  }
}
