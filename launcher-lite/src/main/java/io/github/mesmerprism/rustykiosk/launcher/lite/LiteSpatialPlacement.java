package io.github.mesmerprism.rustykiosk.launcher.lite;

/** Pure viewer-relative panel placement; yaw only keeps text upright. */
final class LiteSpatialPlacement {
  final float x, y, z, forwardX, forwardZ;
  private LiteSpatialPlacement(float x, float y, float z, float forwardX, float forwardZ) {
    this.x = x; this.y = y; this.z = z; this.forwardX = forwardX; this.forwardZ = forwardZ;
  }

  static LiteSpatialPlacement resolve(float x, float y, float z, float forwardX, float forwardZ) {
    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
      x = 0f; y = 1.6f; z = 0f;
    }
    double length = Math.hypot(forwardX, forwardZ);
    if (!Double.isFinite(length) || length < 0.0001) {
      forwardX = 0f; forwardZ = -1f;
    } else {
      forwardX = (float) (forwardX / length);
      forwardZ = (float) (forwardZ / length);
    }
    return new LiteSpatialPlacement(x + forwardX * 1.5f,
        Math.max(0.8f, Math.min(2.2f, y - 0.15f)), z + forwardZ * 1.5f,
        forwardX, forwardZ);
  }
}
