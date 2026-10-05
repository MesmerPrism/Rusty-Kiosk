package io.github.mesmerprism.questautobootexample;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Native 2D example panel. Visible and typed actions call AutobootController. */
public final class ExampleActivity extends Activity {
    private AutobootController controller;
    private LinearLayout root;
    private LinearLayout choices;
    private String search = "";
    private boolean rendering;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        controller = AutobootController.get(this);
        showPanel();
    }

    @Override protected void onResume() {
        super.onResume();
        if (controller != null) showPanel();
    }

    private void showPanel() {
        rendering = true;
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 20, 24, 24);
        root.setBackgroundColor(Color.rgb(248, 248, 246));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = line("Quest Autoboot Example", 26);
        title.setTextColor(Color.rgb(20, 65, 75));
        root.addView(title);
        root.addView(line("Choose one installed app. A boot launch is a request, not proof that the app became ready.", 15));
        try {
            JSONObject status = controller.status();
            root.addView(line("Selected: " + status.optString("selected_label", "None"), 18));
            root.addView(line("Front door: " + (status.optBoolean("selected_available") ? "Available" : "Unavailable")
                + " · Last request: " + status.optString("last_request"), 14));
            Switch enabled = new Switch(this);
            enabled.setText("Launch selected app after boot");
            enabled.setChecked(status.optBoolean("enabled"));
            enabled.setOnCheckedChangeListener((button, checked) -> {
                if (rendering) return;
                apply(() -> controller.enable(checked));
            });
            root.addView(enabled);
            Switch wearer = new Switch(this);
            wearer.setText("Wait until worn and display is awake");
            wearer.setChecked(status.optBoolean("wait_for_wearer"));
            wearer.setOnCheckedChangeListener((button, checked) -> {
                if (rendering) return;
                apply(() -> controller.waitForWearer(checked));
            });
            root.addView(wearer);
        } catch (Exception error) {
            root.addView(line("Status unavailable", 16));
        }
        root.addView(line("The option is off on a fresh install. Changes apply to the next boot.", 13));
        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("Search installed apps");
        filter.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        filter.setText(search);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                search = text.toString();
                showChoices();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        root.addView(filter);
        choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        root.addView(choices);
        showChoices();
        rendering = false;
    }

    private void showChoices() {
        if (choices == null) return;
        choices.removeAllViews();
        List<FrontDoors.Choice> available = FrontDoors.list(this);
        String needle = search.trim().toLowerCase(Locale.ROOT);
        for (FrontDoors.Choice choice : available) {
            if (!needle.isEmpty() && !choice.label.toLowerCase(Locale.ROOT).contains(needle)
                && !choice.component.toLowerCase(Locale.ROOT).contains(needle)) continue;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(choice.label + " · " + choice.category);
            button.setOnClickListener(view -> apply(() -> controller.select(choice.id)));
            choices.addView(button);
        }
    }

    private TextView line(String text, int sp) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sp);
        label.setTextColor(Color.rgb(50, 57, 60));
        label.setPadding(0, 4, 0, 9);
        return label;
    }

    private void apply(Action action) {
        try {
            action.run();
        } catch (Exception rejected) {
            Toast.makeText(this, rejected.getMessage(), Toast.LENGTH_LONG).show();
        }
        showPanel();
    }

    private interface Action { void run() throws Exception; }
}
