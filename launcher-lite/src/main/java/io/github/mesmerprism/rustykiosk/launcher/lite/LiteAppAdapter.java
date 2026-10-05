package io.github.mesmerprism.rustykiosk.launcher.lite;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class LiteAppAdapter extends BaseAdapter {
  private final Context context;
  private final PackageManager packageManager;
  private final LitePreferenceStore store;
  private final List<LiteApp> apps = new ArrayList<>();

  LiteAppAdapter(Context context, LitePreferenceStore store) {
    this.context = context;
    this.packageManager = context.getPackageManager();
    this.store = store;
  }

  void replace(List<LiteApp> replacement) {
    apps.clear();
    apps.addAll(replacement);
    notifyDataSetChanged();
  }

  @Override
  public int getCount() {
    return apps.size();
  }

  @Override
  public LiteApp getItem(int position) {
    return apps.get(position);
  }

  @Override
  public long getItemId(int position) {
    return apps.get(position).key().hashCode();
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    Row row;
    if (convertView == null) {
      row = new Row(context);
      convertView = row.root;
      convertView.setTag(row);
    } else {
      row = (Row) convertView.getTag();
    }
    LiteApp app = getItem(position);
    row.title.setText((store.isFavorite(app.key()) ? "★  " : "") + app.label);
    row.subtitle.setText(app.packageName);
    try {
      Drawable icon = packageManager.getActivityIcon(new ComponentName(app.packageName, app.activityName));
      row.icon.setImageDrawable(icon);
    } catch (PackageManager.NameNotFoundException exception) {
      row.icon.setImageDrawable(context.getDrawable(R.drawable.ic_rusty_launcher_lite));
    }
    return convertView;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private static final class Row {
    final LinearLayout root;
    final ImageView icon;
    final TextView title;
    final TextView subtitle;

    Row(Context context) {
      root = new LinearLayout(context);
      root.setOrientation(LinearLayout.HORIZONTAL);
      root.setGravity(Gravity.CENTER_VERTICAL);
      root.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
      icon = new ImageView(context);
      root.addView(icon, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
      LinearLayout text = new LinearLayout(context);
      text.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams textParams =
          new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      textParams.setMarginStart(dp(context, 12));
      root.addView(text, textParams);
      title = new TextView(context);
      title.setTextColor(context.getColor(R.color.on_surface));
      title.setTextSize(17);
      title.setSingleLine(true);
      text.addView(title);
      subtitle = new TextView(context);
      subtitle.setTextColor(context.getColor(R.color.on_surface_subtle));
      subtitle.setTextSize(12);
      subtitle.setSingleLine(true);
      text.addView(subtitle);
    }
  }
}
