package android.graphics.fonts;

import android.content.res.AssetManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Layoutlib 14 Windows compatibility: framework font resources use disk paths.
 * Keep font bytes and XML unchanged; only repair the preview's stream lookup.
 */
public final class Font_Builder_Delegate {
  @LayoutlibDelegate
  static ByteBuffer createBuffer(AssetManager assets, String path, boolean asset, int cookie)
      throws IOException {
    if (path.isBlank()) return null;
    Path diskPath = Path.of(path).toRealPath();
    String normalized = diskPath.toString().replace('\\', '/');
    if (asset || !normalized.matches(".*/layoutlib-runtime-14\\.0\\.11-(win|linux|mac|mac-arm)/data/fonts/[^/]+\\.(ttf|otf|ttc)")) {
      throw new IOException("Unsupported Layoutlib preview font path: " + path);
    }
    byte[] bytes = Files.readAllBytes(diskPath);
    Path evidence = Path.of(System.getProperty("lite.preview.evidence"), "fonts");
    Files.createDirectories(evidence);
    try {
      Files.write(evidence.resolve(diskPath.getFileName() + ".sha256"),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
    buffer.put(bytes);
    buffer.flip();
    return buffer;
  }
}
