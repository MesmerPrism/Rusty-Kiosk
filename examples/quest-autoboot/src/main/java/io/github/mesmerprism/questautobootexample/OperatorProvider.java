package io.github.mesmerprism.questautobootexample;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** DUMP-protected typed adapter over the same local controller used by the panel. */
public final class OperatorProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null || context.checkCallingPermission(Manifest.permission.DUMP)
            != PackageManager.PERMISSION_GRANTED) throw new SecurityException("DUMP required");
        try {
            AutobootController controller = AutobootController.get(context);
            JSONObject payload;
            switch (method == null ? "" : method) {
                case "status":
                    requireShape(arg, extras, null, null);
                    payload = controller.status();
                    break;
                case "catalog":
                    requireShape(arg, extras, null, null);
                    payload = controller.catalogue();
                    break;
                case "select":
                    requireShape(arg, extras, "choice_id", String.class);
                    payload = controller.select(extras.getString("choice_id"));
                    break;
                case "enable":
                    requireShape(arg, extras, "enabled", Boolean.class);
                    payload = controller.enable(extras.getBoolean("enabled"));
                    break;
                case "wait":
                    requireShape(arg, extras, "wait_for_wearer", Boolean.class);
                    payload = controller.waitForWearer(extras.getBoolean("wait_for_wearer"));
                    break;
                default: throw new IllegalArgumentException("Unknown operator method");
            }
            return result(true, payload.toString());
        } catch (Exception rejected) {
            String message = rejected.getMessage() == null ? "request rejected" : rejected.getMessage();
            return result(false, "{\"error\":" + JSONObject.quote(message) + "}");
        }
    }

    private static void requireShape(String arg, Bundle extras, String key, Class<?> type) {
        if (arg != null && !arg.isEmpty()) throw new IllegalArgumentException("Argument not supported");
        if (key == null) {
            if (extras != null && !extras.isEmpty()) throw new IllegalArgumentException("Extras not supported");
            return;
        }
        if (extras == null || extras.size() != 1 || !extras.containsKey(key)
            || !type.isInstance(extras.get(key))) throw new IllegalArgumentException("Invalid typed extra");
    }

    private static Bundle result(boolean ok, String json) {
        Bundle output = new Bundle();
        output.putBoolean("ok", ok);
        output.putString("payload_b64", Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP));
        return output;
    }

    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
