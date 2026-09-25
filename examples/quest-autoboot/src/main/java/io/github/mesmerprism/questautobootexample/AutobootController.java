package io.github.mesmerprism.questautobootexample;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Local preferences and boot effect are owned by this example, independent of Kiosk. */
final class AutobootController {
    private static final String PREFS = "quest_autoboot_example";
    private static final String TICK = "io.github.mesmerprism.questautobootexample.RETRY";
    private static final long RETRY_MS = 2_000L;
    private static AutobootController instance;
    private final Context context;
    private final SharedPreferences saved;

    static synchronized AutobootController get(Context context) {
        if (instance == null) instance = new AutobootController(context);
        return instance;
    }

    private AutobootController(Context context) {
        this.context = context.getApplicationContext();
        this.saved = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized JSONObject status() throws JSONException {
        String component = saved.getString("component", "");
        String category = saved.getString("category", "");
        boolean pending = saved.getBoolean("pending", false);
        long age = pending ? SystemClock.elapsedRealtime() - saved.getLong("started", 0) : 0;
        return new JSONObject()
            .put("schema", "quest.autoboot.example.status.v1")
            .put("enabled", saved.getBoolean("enabled", false))
            .put("wait_for_wearer", saved.getBoolean("wait_for_wearer", true))
            .put("selected_choice_id", saved.getString("choice_id", ""))
            .put("selected_label", saved.getString("label", ""))
            .put("selected_category", category)
            .put("selected_available", FrontDoors.stillInstalled(context, component, category,
                saved.getString("choice_id", "")))
            .put("pending", pending)
            .put("pending_age_ms", Math.max(0, age))
            .put("last_request", saved.getString("last_request", "none"));
    }

    synchronized JSONObject catalogue() throws JSONException {
        JSONArray items = new JSONArray();
        for (FrontDoors.Choice choice : FrontDoors.list(context)) {
            items.put(new JSONObject().put("choice_id", choice.id).put("label", choice.label)
                .put("package", ComponentName.unflattenFromString(choice.component).getPackageName())
                .put("category", choice.category));
        }
        return new JSONObject().put("schema", "quest.autoboot.example.catalog.v1")
            .put("choices", items);
    }

    synchronized JSONObject select(String choiceId) throws JSONException {
        FrontDoors.Choice choice = FrontDoors.find(context, choiceId);
        if (choice == null || !FrontDoors.stillInstalled(context, choice.component,
            choice.category, choice.id)) {
            throw new IllegalArgumentException("Choice is no longer an installed front door.");
        }
        if (saved.getBoolean("pending", false)
            && !choice.id.equals(saved.getString("pending_choice_id", ""))) stop("selection-changed");
        saved.edit().putString("choice_id", choice.id).putString("component", choice.component)
            .putString("category", choice.category).putString("label", choice.label).apply();
        return status();
    }

    synchronized JSONObject enable(boolean enabled) throws JSONException {
        if (enabled && !selectedAvailable()) {
            throw new IllegalStateException("Select an installed app before enabling autostart.");
        }
        saved.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) stop("disabled");
        return status();
    }

    synchronized JSONObject waitForWearer(boolean wait) throws JSONException {
        saved.edit().putBoolean("wait_for_wearer", wait).apply();
        return status();
    }

    synchronized void beginBoot() {
        if (!saved.getBoolean("enabled", false) || !selectedAvailable()) {
            stop("boot-not-armed");
            return;
        }
        saved.edit().putBoolean("pending", true)
            .putLong("started", SystemClock.elapsedRealtime())
            .putString("pending_choice_id", saved.getString("choice_id", ""))
            .putBoolean("pending_wait_for_wearer", saved.getBoolean("wait_for_wearer", true))
            .putString("last_request", "boot-pending").apply();
        tick();
    }

    synchronized void tick() {
        if (!saved.getBoolean("pending", false)) return;
        long age = SystemClock.elapsedRealtime() - saved.getLong("started", 0);
        boolean wait = saved.getBoolean("pending_wait_for_wearer", true);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean interactive = power != null && power.isInteractive();
        boolean bootReady = "1".equals(readProperty("sys.boot_completed"));
        String animation = readProperty("init.svc.bootanim");
        bootReady &= animation.isEmpty() || "stopped".equals(animation);
        boolean worn = wait && "1".equals(readProperty("sys.hmt.mounted"));
        boolean sameSelection = saved.getString("choice_id", "")
            .equals(saved.getString("pending_choice_id", ""));
        BootPlan.Step step = BootPlan.next(saved.getBoolean("enabled", false),
            sameSelection && selectedAvailable(),
            bootReady, interactive, wait, worn, age);
        if (step == BootPlan.Step.WAIT) {
            schedule();
        } else if (step == BootPlan.Step.LAUNCH) {
            // Consume the pending attempt before Android receives the start request.
            stop("launch-requested");
            requestLaunch(wait, power);
        } else {
            stop(step.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private boolean selectedAvailable() {
        return FrontDoors.stillInstalled(context, saved.getString("component", ""),
            saved.getString("category", ""), saved.getString("choice_id", ""));
    }

    private void requestLaunch(boolean wait, PowerManager power) {
        String componentText = saved.getString("component", "");
        String category = saved.getString("category", "");
        if (!FrontDoors.stillInstalled(context, componentText, category,
            saved.getString("choice_id", ""))) {
            saved.edit().putString("last_request", "stop-target").apply();
            return;
        }
        ComponentName component = ComponentName.unflattenFromString(componentText);
        if (component == null) return;
        PowerManager.WakeLock wake = null;
        try {
            if (!wait && power != null) {
                wake = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "QuestAutobootExample:launch");
                wake.acquire(20_000L);
            }
            context.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(component)
                .addCategory(category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException denied) {
            saved.edit().putString("last_request", "launch-rejected").apply();
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private void schedule() {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RETRY_MS, retryIntent());
    }

    private void stop(String outcome) {
        saved.edit().putBoolean("pending", false).putString("last_request", outcome).apply();
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(retryIntent());
    }

    private PendingIntent retryIntent() {
        return PendingIntent.getBroadcast(context, 1, new Intent(context, RetryEvent.class).setAction(TICK),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean validTickAction(String action) { return TICK.equals(action); }

    private static String readProperty(String key) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", key).start();
            if (!process.waitFor(750, TimeUnit.MILLISECONDS)) return "";
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = lines.readLine();
                return line == null ? "" : line.trim();
            }
        } catch (Exception unavailable) {
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }
}
