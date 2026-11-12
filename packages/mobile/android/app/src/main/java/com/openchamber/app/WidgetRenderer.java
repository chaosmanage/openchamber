package com.openchamber.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * D1 OpenChamber home widgets. Reuses the Usage SDK guest's quota/group data.
 * Renders two fixed-width, aligned circular meters per row and wraps any
 * additional windows onto the next row under the same provider.
 */
final class WidgetRenderer {
    private WidgetRenderer() {}

    private static final int FG = Color.rgb(229, 231, 235);
    private static final int MUTED = Color.rgb(156, 163, 175);
    private static final int GREEN = Color.rgb(74, 181, 113);
    private static final int AMBER = Color.rgb(235, 173, 65);
    private static final int RED = Color.rgb(235, 99, 103);
    private static final int BLUE = Color.rgb(116, 183, 242);
    private static final int PURPLE = Color.rgb(183, 156, 227);

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
            open(context, usage ? "openchamber://settings/usage" : "openchamber://sessions?filter=recent",
                widgetId * 100));
        view.removeAllViews(R.id.widget_rows);
        JSONArray rows = data.optJSONArray("rows");
        int count = rows == null ? 0 : rows.length();
        if (count == 0) {
            RemoteViews line = new RemoteViews(context.getPackageName(), R.layout.widget_line);
            line.setTextViewText(R.id.widget_line_name, "Open OpenChamber to sync");
            line.setTextViewText(R.id.widget_line_detail, "Connect to your server");
            line.setViewVisibility(R.id.widget_line_progress, View.GONE);
            view.addView(R.id.widget_rows, line);
        } else if (usage) {
            usageRows(context, view, rows);
        } else {
            sessionRows(context, view, rows, widgetId);
        }
        String updated = clean(data.optString("updated"), 75);
        view.setTextViewText(R.id.widget_timestamp,
            updated.isEmpty() ? "Not synced" : "Synced " + updated);
        if (usage) {
            String mode = "used".equals(data.optString("mode")) ? "used" : "remaining";
            view.setTextViewText(R.id.widget_subtitle, mode + " · " + count + " providers");
        } else {
            view.setTextViewText(R.id.widget_subtitle, count + " recent");
            view.setOnClickPendingIntent(R.id.widget_new_session,
                open(context, "openchamber://new", widgetId * 1000 + 99));
        }
        return view;
    }

    private static void usageRows(Context context, RemoteViews root, JSONArray groups) {
        // Each provider's meters occupy exactly two equally weighted columns.
        // Never combine several quota percentages into one ring.
        int groupCount = Math.min(10, groups.length());
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            JSONObject group = groups.optJSONObject(groupIndex);
            if (group == null) continue;
            JSONArray meters = group.optJSONArray("rows");
            if (meters == null || meters.length() == 0) continue;
            String name = clean(group.optString("name"), 48);
            String id = group.optString("id");
            int index = 0;
            // Two gauges per physical row; Antigravity's four windows => two rows.
            while (index < meters.length() && index < 12) {
                RemoteViews line = new RemoteViews(context.getPackageName(), R.layout.widget_quota_row);
                if (index == 0) {
                    line.setTextViewText(R.id.quota_provider_initial, providerInitial(id, name));
                    line.setTextViewText(R.id.quota_provider_name, name);
                    line.setTextColor(R.id.quota_provider_initial, providerColor(id));
                    int icon = providerLogo(id);
                    if (icon != 0) {
                        // Keep the rounded badge background behind the reused SDK SVG.
                        line.setTextViewText(R.id.quota_provider_initial, "");
                        line.setViewVisibility(R.id.quota_provider_logo, View.VISIBLE);
                        line.setImageViewResource(R.id.quota_provider_logo, icon);
                    }
                } else {
                    // Keep the provider column reserved to line up every gauge.
                    line.setViewVisibility(R.id.quota_provider_initial, View.INVISIBLE);
                    line.setViewVisibility(R.id.quota_provider_logo, View.INVISIBLE);
                    line.setViewVisibility(R.id.quota_provider_name, View.INVISIBLE);
                }
                line.removeAllViews(R.id.quota_metrics);
                for (int column = 0; column < 2; column++) {
                    RemoteViews metric = new RemoteViews(context.getPackageName(), R.layout.widget_quota_metric);
                    JSONObject item = index + column < meters.length() ? meters.optJSONObject(index + column) : null;
                    if (item == null) {
                        metric.setViewVisibility(R.id.quota_ring, View.INVISIBLE);
                        metric.setViewVisibility(R.id.quota_label, View.INVISIBLE);
                        metric.setViewVisibility(R.id.quota_reset, View.INVISIBLE);
                        metric.setViewVisibility(R.id.quota_detail, View.GONE);
                    } else {
                        double percentage = item.optDouble("percent", Double.NaN);
                        String value = item.optString("value", "–");
                        double used = item.optDouble("used", Double.NaN);
                        double remaining = Double.isFinite(used) ? 100.0 - used
                            : Double.isFinite(percentage) ? percentage : Double.NaN;
                        int color = !Double.isFinite(remaining) ? MUTED
                            : remaining < 20 ? RED : remaining < 50 ? AMBER : GREEN;
                        metric.setImageViewBitmap(R.id.quota_ring, ring(context, percentage, value, color));
                        String label = compactLabel(item.optString("label"));
                        metric.setTextViewText(R.id.quota_label, label);
                        String reset = resetLabel(item.optLong("resetAt", 0));
                        if (reset.isEmpty()) metric.setViewVisibility(R.id.quota_reset, View.GONE);
                        else metric.setTextViewText(R.id.quota_reset, reset);
                        String detail = clean(item.optString("detail"), 40);
                        if (detail.isEmpty() || detail.equals(value)) {
                            metric.setViewVisibility(R.id.quota_detail, View.GONE);
                        } else {
                            metric.setTextViewText(R.id.quota_detail, detail);
                        }
                    }
                    line.addView(R.id.quota_metrics, metric);
                }
                root.addView(R.id.widget_rows, line);
                index += 2;
            }
        }
    }

    private static String compactLabel(String raw) {
        String label = raw.replace(" · ", "\n")
            .replace("5-Hour", "5h")
            .replace("5-hour", "5h")
            .replace("Credits balance", "Credit balance");
        // A pair of labels stays distinct for the Google and non-Google families.
        label = label.replace("Non-Google Models", "Non-Google")
            .replace("Google Models", "Google Models");
        // Preserve the two-line family/window split on narrow widget cells.
        return label.length() > 48 ? label.substring(0, 47) + "…" : label;
    }

    private static Bitmap ring(Context context, double percent, String value, int foreground) {
        float density = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        int size = Math.max(48, Math.round(52 * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float center = size / 2f;
        float stroke = Math.max(3f, 4.5f * density);
        float radius = center - stroke / 2f - 1.5f * density;

        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(Color.rgb(71, 73, 83));
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        canvas.drawCircle(center, center, radius, track);

        if (Double.isFinite(percent) && percent > 0) {
            Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
            progress.setColor(foreground);
            progress.setStyle(Paint.Style.STROKE);
            progress.setStrokeWidth(stroke);
            progress.setStrokeCap(Paint.Cap.ROUND);
            RectF bounds = new RectF(center - radius, center - radius, center + radius, center + radius);
            canvas.drawArc(bounds, -90, (float) (Math.min(100, percent) / 100.0 * 360), false, progress);
        }
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        text.setColor(FG);
        String number = clean(value, 12);
        if (number.endsWith("%") && number.length() > 5) number = number.substring(0, 4) + "%";
        float font = (number.length() > 5 ? 8.2f : number.length() > 4 ? 9.8f : 11f) * sp;
        text.setTextSize(font);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(number, center, center - (text.ascent() + text.descent()) / 2, text);
        return bitmap;
    }

    private static String resetLabel(long at) {
        if (at <= 0) return "";
        long ms = at - System.currentTimeMillis();
        if (ms <= 0) return "↻ due";
        long minutes = (ms + 59_999) / 60_000;
        if (minutes < 60) return "↻ " + minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return "↻ " + hours + "h " + (minutes % 60) + "m";
        if (hours < 168) return "↻ " + (hours / 24) + "d " + (hours % 24) + "h";
        return "↻ " + new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(at));
    }

    private static int providerLogo(String id) {
        if (id.contains("antigravity")) return R.drawable.widget_logo_antigravity;
        if (id.contains("github-copilot")) return R.drawable.widget_logo_github_copilot;
        if (id.contains("codex")) return R.drawable.widget_logo_codex;
        if (id.contains("cursor")) return R.drawable.widget_logo_cursor;
        if (id.equals("google")) return R.drawable.widget_logo_google;
        if (id.equals("openai")) return R.drawable.widget_logo_openai;
        if (id.equals("claude")) return R.drawable.widget_logo_claude;
        return 0;
    }

    private static String providerInitial(String id, String name) {
        if (id.contains("antigravity")) return "✦";
        if (id.contains("github")) return "GH";
        if (id.contains("codex") || id.contains("openai")) return "◎";
        if (id.contains("cursor")) return "◆";
        return name.isEmpty() ? "•" : name.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static int providerColor(String id) {
        if (id.contains("antigravity")) return PURPLE;
        if (id.contains("github")) return BLUE;
        if (id.contains("codex") || id.contains("openai")) return GREEN;
        if (id.contains("cursor")) return AMBER;
        return FG;
    }

    private static void sessionRows(Context context, RemoteViews root, JSONArray sessions, int widgetId) {
        for (int i = 0; i < Math.min(6, sessions.length()); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) continue;
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_session_row);
            String status = item.optString("status", "Recent");
            int color = status.equals("Running") ? BLUE : status.equals("Retrying") ? AMBER
                : status.equals("Done") ? GREEN : status.equals("Failed") ? RED : PURPLE;
            String mark = status.equals("Running") ? "● " : status.equals("Retrying") ? "↻ "
                : status.equals("Done") ? "✓ " : status.equals("Failed") ? "× " : "◷ ";
            row.setTextViewText(R.id.widget_session_badge, mark + status);
            row.setTextColor(R.id.widget_session_badge, color);
            row.setTextViewText(R.id.widget_session_name, clean(item.optString("name"), 90));

            String project = clean(item.optString("project"), 30);
            row.setTextViewText(R.id.widget_session_project,
                project.isEmpty() ? "Session" : "▣  " + project);
            // A project receives a repeatable accent, distinct from the state badge.
            int[] projectColors = new int[] { PURPLE, BLUE, GREEN, AMBER };
            row.setTextColor(R.id.widget_session_project,
                project.isEmpty() ? MUTED : projectColors[Math.floorMod(project.hashCode(), projectColors.length)]);
            String agent = clean(item.optString("agent"), 20);
            if (agent.isEmpty()) row.setViewVisibility(R.id.widget_session_agent, View.GONE);
            else row.setTextViewText(R.id.widget_session_agent, agent);

            String id = item.optString("id");
            if (!id.isEmpty()) {
                String directory = item.optString("directory");
                String uri = "openchamber://session/" + Uri.encode(id)
                    + (directory.isEmpty() ? "" : "?dir=" + Uri.encode(directory));
                row.setOnClickPendingIntent(R.id.widget_session_root,
                    open(context, uri, widgetId * 1000 + i + 1));
            }
            root.addView(R.id.widget_rows, row);
        }
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
