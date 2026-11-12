package com.openchamber.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure Android RemoteViews: nothing fetches network data or keeps a server credential. */
final class WidgetRenderer {
    private WidgetRenderer() {}

    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Class<?> kind : new Class<?>[] {UsageWidgetProvider.class, SessionsWidgetProvider.class}) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, kind));
            for (int id : ids) {
                manager.updateAppWidget(id, render(context, kind == UsageWidgetProvider.class, id));
            }
        }
    }

    static RemoteViews render(Context context, boolean usage, int widgetId) {
        RemoteViews view = new RemoteViews(context.getPackageName(),
            usage ? R.layout.widget_usage : R.layout.widget_sessions);
        String key = usage ? "usage" : "sessions";
        String saved = context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE).getString(key, "{}");
        JSONObject data;
        try { data = new JSONObject(saved); } catch (Exception e) { data = new JSONObject(); }

        view.setOnClickPendingIntent(R.id.widget_header,
            open(context, "openchamber://sessions?filter=recent", widgetId * 100));
        view.removeAllViews(R.id.widget_rows);
        JSONArray rows = data.optJSONArray("rows");
        int count = rows == null ? 0 : rows.length();
        int max = usage ? 12 : 6;
        if (count == 0) {
            RemoteViews line = new RemoteViews(context.getPackageName(), R.layout.widget_line);
            line.setTextViewText(R.id.widget_line_name, "Open OpenChamber to sync");
            line.setTextViewText(R.id.widget_line_detail, "Connect to your server");
            line.setViewVisibility(R.id.widget_line_progress, android.view.View.GONE);
            view.addView(R.id.widget_rows, line);
        } else {
            for (int i = 0; i < Math.min(max, count); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item == null) continue;
                RemoteViews line = new RemoteViews(context.getPackageName(), R.layout.widget_line);
                line.setTextViewText(R.id.widget_line_name, clean(item.optString("name"), 95));
                line.setTextViewText(R.id.widget_line_detail, clean(item.optString("detail"), 125));
                if (usage) {
                    int progress = Math.max(0, Math.min(100, item.optInt("progress", 0)));
                    line.setProgressBar(R.id.widget_line_progress, 100, progress, false);
                } else {
                    line.setViewVisibility(R.id.widget_line_progress, android.view.View.GONE);
                    String id = item.optString("id");
                    if (!id.isEmpty()) {
                        String directory = item.optString("directory");
                        String uri = "openchamber://session/" + Uri.encode(id)
                            + (directory.isEmpty() ? "" : "?dir=" + Uri.encode(directory));
                        line.setOnClickPendingIntent(R.id.widget_line_root,
                            open(context, uri, widgetId * 1000 + i + 1));
                    }
                }
                view.addView(R.id.widget_rows, line);
            }
        }
        String updated = clean(data.optString("updated"), 75);
        view.setTextViewText(R.id.widget_timestamp,
            updated.isEmpty() ? "Not synced" : "Synced " + updated);
        if (usage) {
            view.setTextViewText(R.id.widget_subtitle, count + " provider" + (count == 1 ? "" : "s"));
        } else {
            view.setTextViewText(R.id.widget_subtitle, count + " recent sessions");
            view.setOnClickPendingIntent(R.id.widget_new_session,
                open(context, "openchamber://new", widgetId * 1000 + 99));
        }
        return view;
    }

    private static String clean(String raw, int limit) {
        String value = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > limit ? value.substring(0, limit - 1) + "…" : value;
    }

    private static PendingIntent open(Context context, String url, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url), context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
