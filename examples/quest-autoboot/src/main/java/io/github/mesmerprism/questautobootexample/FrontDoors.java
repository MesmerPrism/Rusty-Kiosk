package io.github.mesmerprism.questautobootexample;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only current exported MAIN front doors may enter the saved selection. */
final class FrontDoors {
    static final String VR = "com.oculus.intent.category.VR";
    static final String TWO_D = "com.oculus.intent.category.2D";
    private static final String[] CATEGORIES = { Intent.CATEGORY_LAUNCHER, VR, TWO_D,
        Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_INFO };

    static final class Choice {
        final String id;
        final String label;
        final String component;
        final String category;
        Choice(String label, String component, String category, String identity) {
            this.id = idFor(component, category, identity);
            this.label = label;
            this.component = component;
            this.category = category;
        }
    }

    static List<Choice> list(Context context) {
        PackageManager packages = context.getPackageManager();
        List<Choice> choices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : CATEGORIES) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo hit : packages.queryIntentActivities(query, 0)) {
                ActivityInfo activity = hit.activityInfo;
                if (activity == null || !activity.exported || activity.packageName == null
                    || activity.name == null || context.getPackageName().equals(activity.packageName)) continue;
                String identity = installedIdentity(packages, activity.packageName);
                if (identity == null) continue;
                String component = new ComponentName(activity.packageName, activity.name).flattenToString();
                String id = idFor(component, category, identity);
                if (!seen.add(id)) continue;
                CharSequence title = hit.loadLabel(packages);
                String label = title == null ? "" : title.toString().trim();
                choices.add(new Choice(label.isEmpty() ? activity.packageName : label,
                    component, category, identity));
            }
        }
        choices.sort(Comparator.comparing((Choice item) -> item.label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(item -> item.id));
        return choices;
    }

    static Choice find(Context context, String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return null;
        for (Choice choice : list(context)) if (choice.id.equals(id)) return choice;
        return null;
    }

    static boolean stillInstalled(Context context, String component, String category, String choiceId) {
        if (component == null || category == null || choiceId == null) return false;
        boolean allowed = false;
        for (String candidate : CATEGORIES) if (candidate.equals(category)) allowed = true;
        ComponentName target = ComponentName.unflattenFromString(component);
        if (!allowed || target == null || context.getPackageName().equals(target.getPackageName())) return false;
        PackageManager packages = context.getPackageManager();
        String identity = installedIdentity(packages, target.getPackageName());
        if (identity == null || !idFor(component, category, identity).equals(choiceId)) return false;
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category)
            .setPackage(target.getPackageName());
        for (ResolveInfo hit : packages.queryIntentActivities(query, 0)) {
            ActivityInfo activity = hit.activityInfo;
            if (activity != null && activity.exported && target.getPackageName().equals(activity.packageName)
                && target.getClassName().equals(activity.name)) return true;
        }
        return false;
    }

    static String idFor(String component, String category, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((category + "\n" + component + "\n" + identity)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int index = 0; index < 16; index++) hex.append(String.format("%02x", digest[index] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String installedIdentity(PackageManager packages, String name) {
        try {
            PackageInfo info = packages.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null || info.applicationInfo == null) return null;
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length != 1) return null;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            StringBuilder signer = new StringBuilder(64);
            for (byte part : digest) signer.append(String.format("%02x", part & 0xff));
            return info.applicationInfo.uid + ":" + info.getLongVersionCode() + ":"
                + info.lastUpdateTime + ":" + signer;
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException unavailable) {
            return null;
        }
    }

    private FrontDoors() { }
}
