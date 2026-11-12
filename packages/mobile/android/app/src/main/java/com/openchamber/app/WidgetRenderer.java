package com.openchamber.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
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
    static final String NEXT_USAGE_ACTION = "com.openchamber.app.action.NEXT_USAGE";

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
        if (usage) view.setOnClickPendingIntent(R.id.widget_rows,
            open(context, "openchamber://settings/usage", widgetId * 100 + 1));
        view.removeAllViews(R.id.widget_rows);
        JSONArray rows = data.optJSONArray("rows");
        int count = rows == null ? 0 : rows.length();
        final int heightDp = widgetHeight(context, widgetId);
        if (count == 0) {
            RemoteViews line = new RemoteViews(context.getPackageName(), R.layout.widget_line);
            line.setTextViewText(R.id.widget_line_name, "Open OpenChamber to sync");
            line.setTextViewText(R.id.widget_line_detail, "Connect to your server");
            line.setViewVisibility(R.id.widget_line_progress, View.GONE);
            view.addView(R.id.widget_rows, line);
        } else if (usage) {
            usageRows(context, view, rows, widgetId, heightDp,
                "used".equals(data.optString("mode")) ? "used" : "remaining");
        } else {
            sessionRows(context, view, rows, widgetId, heightDp);
        }
        String updated = clean(data.optString("updated"), 75);
        view.setTextViewText(R.id.widget_timestamp,
            updated.isEmpty() ? "Not synced" : "Synced " + updated);
        if (usage) {
            String mode = "used".equals(data.optString("mode")) ? "used" : "remaining";
            // usageRows owns the compact mode / page indicator.
            if (count == 0) view.setTextViewText(R.id.widget_subtitle, mode);
        } else {
            // sessionRows owns the visible / total indicator.
            if (count == 0) view.setTextViewText(R.id.widget_subtitle, "0 sessions");
            view.setOnClickPendingIntent(R.id.widget_new_session,
                open(context, "openchamber://new", widgetId * 1000 + 99));
        }
        return view;
    }

    /**
     * Use the launcher-provided widget height to decide how many rows fit.
     * The first page is intentionally compact. The next-arrow cycles through
     * every selected provider without requiring the full-screen widget.
     */
    private static int widgetHeight(Context context, int widgetId) {
        Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
        int height = options == null ? 0
            : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return height > 0 ? height : 235;
    }

    private static int widgetWidth(Context context, int widgetId) {
        Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
        int width = options == null ? 0
            : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        return width > 0 ? width : 330;
    }

    private static List<JSONObject> measurableRows(JSONObject group) {
        ArrayList<JSONObject> result = new ArrayList<>();
        JSONArray rows = group.optJSONArray("rows");
        if (rows == null) return result;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            double pct = item.optDouble("percent", Double.NaN);
            if (Double.isFinite(pct)) result.add(item);
        }
        return result;
    }

    private static final class QuotaLine {
        final JSONObject provider;
        final JSONObject left;
        final JSONObject right;
        final int offset;
        QuotaLine(JSONObject provider, JSONObject left, JSONObject right, int offset) {
            this.provider = provider;
            this.left = left;
            this.right = right;
            this.offset = offset;
        }
    }

    /**
     * Flatten physical rows before paging. Group-boundary pagination produced
     * large blank areas when the next provider had four Antigravity windows.
     * Retain the provider label on every continued row so no quota gets lost.
     */
    private static List<QuotaLine> quotaLines(JSONArray groups) {
        ArrayList<QuotaLine> lines = new ArrayList<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject provider = groups.optJSONObject(i);
            if (provider == null) continue;
            List<JSONObject> windows = measurableRows(provider);
            if (windows.isEmpty()) {
                lines.add(new QuotaLine(provider, null, null, 0));
                continue;
            }
            for (int n = 0; n < windows.size(); n += 2) {
                lines.add(new QuotaLine(provider, windows.get(n),
                    n + 1 < windows.size() ? windows.get(n+1) : null, n));
            }
        }
        return lines;
    }

    private static void usageRows(Context context, RemoteViews root, JSONArray groups,
                                  int widgetId, int heightDp, String mode) {
        boolean narrow = widgetWidth(context, widgetId) < 300;
        // Reserve actual header, footer, and launcher inset; use enough height
        // for 2 legible rings and their two-line labels without clipping.
        final int rowHeight = narrow ? 90 : 67;
        final int visibleRows = Math.max(1, Math.min(8, (heightDp - 80) / rowHeight));
        List<QuotaLine> lines = quotaLines(groups);
        if (lines.isEmpty()) return;
        int pages = (lines.size() + visibleRows - 1) / visibleRows;
        int storedPage = context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE)
            .getInt("usage_page_" + widgetId, 0);
        int page = Math.floorMod(storedPage, pages);
        if (pages > 1) {
            root.setTextViewText(R.id.widget_subtitle, mode + " · " + (page + 1) + "/" + pages);
            root.setViewVisibility(R.id.widget_page_next, View.VISIBLE);
            Intent intent = new Intent(context, UsageWidgetProvider.class);
            intent.setAction(NEXT_USAGE_ACTION);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            root.setOnClickPendingIntent(R.id.widget_page_next,
                PendingIntent.getBroadcast(context, widgetId * 1000 + 44, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            root.setViewVisibility(R.id.widget_page_next, View.GONE);
            root.setTextViewText(R.id.widget_subtitle, mode);
        }

        int from = page * visibleRows;
        int to = Math.min(from + visibleRows, lines.size());
        for (int lineNum = from; lineNum < to; lineNum++) {
            QuotaLine quota = lines.get(lineNum);
            JSONObject group = quota.provider;
            String name = clean(group.optString("name"), 48);
            String id = group.optString("id");
            RemoteViews line = new RemoteViews(context.getPackageName(),
                narrow ? R.layout.widget_quota_row_narrow : R.layout.widget_quota_row);
            line.setTextViewText(R.id.quota_provider_initial, providerInitial(id, name));
            line.setTextViewText(R.id.quota_provider_name, name);
            line.setTextColor(R.id.quota_provider_initial, providerColor(id));
            int icon = providerLogo(id);
            if (icon != 0) {
                line.setTextViewText(R.id.quota_provider_initial, "");
                line.setViewVisibility(R.id.quota_provider_logo, View.VISIBLE);
                line.setImageViewResource(R.id.quota_provider_logo, icon);
            }
            // Credit balances are text, not percentage rings.
            if (quota.offset == 0) {
                JSONArray allRows = group.optJSONArray("rows");
                if (allRows != null) for (int i = 0; i < allRows.length(); i++) {
                    JSONObject entry = allRows.optJSONObject(i);
                    if (entry == null || Double.isFinite(entry.optDouble("percent", Double.NaN))) continue;
                    if (entry.optString("label").toLowerCase(Locale.ROOT).contains("balance")) {
                        line.setViewVisibility(R.id.quota_provider_extra, View.VISIBLE);
                        line.setTextViewText(R.id.quota_provider_extra,
                            clean("Credits " + entry.optString("value"), 25));
                        break;
                    }
                }
            }
            line.removeAllViews(R.id.quota_metrics);
            JSONObject[] two = new JSONObject[] { quota.left, quota.right };
            for (JSONObject item : two) {
                RemoteViews meter = new RemoteViews(context.getPackageName(), R.layout.widget_quota_metric);
                if (item == null) {
                    meter.setViewVisibility(R.id.quota_ring, View.INVISIBLE);
                    meter.setViewVisibility(R.id.quota_label, View.INVISIBLE);
                    meter.setViewVisibility(R.id.quota_reset, View.GONE);
                    meter.setViewVisibility(R.id.quota_detail, View.GONE);
                } else {
                    double percentage = item.optDouble("percent", Double.NaN);
                    String value = item.optString("value", "–");
                    double used = item.optDouble("used", Double.NaN);
                    double remaining = Double.isFinite(used) ? 100 - used
                        : Double.isFinite(percentage) ? percentage : Double.NaN;
                    int color = !Double.isFinite(remaining) ? MUTED
                        : remaining < 20 ? RED : remaining < 50 ? AMBER : GREEN;
                    meter.setImageViewBitmap(R.id.quota_ring, ring(context, percentage, value, color));
                    meter.setTextViewText(R.id.quota_label, compactLabel(item.optString("label")));
                    String reset = resetLabel(item.optLong("resetAt", 0));
                    if (reset.isEmpty()) meter.setViewVisibility(R.id.quota_reset, View.GONE);
                    else meter.setTextViewText(R.id.quota_reset, reset);
                    String detail = clean(item.optString("detail"), 36);
                    if (detail.isEmpty() || detail.equals(value)) {
                        meter.setViewVisibility(R.id.quota_detail, View.GONE);
                    } else meter.setTextViewText(R.id.quota_detail,
                        detail.replace(" / ", "/").replace(" used", ""));
                }
                line.addView(R.id.quota_metrics, meter);
            }
            root.addView(R.id.widget_rows, line);
        }
    }

    /** Next-page action is scoped to one widget instance. */
    static void nextPage(Context context, int widgetId) {
        String key = "usage_page_" + widgetId;
        int page = context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE).getInt(key, 0);
        context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE)
            .edit().putInt(key, page + 1).apply();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.updateAppWidget(widgetId, render(context, true, widgetId));
    }

    private static String compactLabel(String raw) {
        // Same quota identities as the Usage SDK panel, shortened for a phone.
        String label = raw.replace("Non-Google Models · ", "Non-Google ")
            .replace("Google Models · ", "Google ")
            .replace("5-Hour", "5h").replace("5-hour", "5h")
            .replace("7d", "Weekly").replace("Credits balance", "Credits");
        return label.length() > 28 ? label.substring(0, 27) + "…" : label;
    }

    private static Bitmap ring(Context context, double percent, String value, int foreground) {
        float density = context.getResources().getDisplayMetrics().density;
        float sp = context.getResources().getDisplayMetrics().scaledDensity;
        // Avoid large RemoteViews parcels when all providers fit on one page.
        // 60dp remains sharper than the displayed 53dp ImageView.
        int size = Math.max(60, Math.round(60 * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float center = size / 2f;
        float stroke = Math.max(5f, 6.5f * density);
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
        float font = (number.length() > 5 ? 15f : number.length() > 4 ? 18f : 19f) * sp;
        text.setTextSize(font);
        // Most importantly, 100% must NEVER escape the ring's inner diameter,
        // even on high-font-scale Android launchers.
        float safeWidth = 2f * (radius - stroke * 0.85f);
        while (text.measureText(number) > safeWidth && font > 7f * sp) {
            font -= 0.5f * sp;
            text.setTextSize(font);
        }
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

    private static void sessionRows(Context context, RemoteViews root, JSONArray sessions,
                                    int widgetId, int heightDp) {
        int maxRows = Math.min(6, Math.max(2, (heightDp - 88) / 60));
        root.setTextViewText(R.id.widget_subtitle,
            Math.min(maxRows, sessions.length()) + "/" + sessions.length());
        for (int i = 0; i < Math.min(maxRows, sessions.length()); i++) {
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
            row.setInt(R.id.widget_session_accent, "setBackgroundColor",
                status.equals("Recent") ? projectAccent(item.optString("project")) : color);
            row.setTextViewText(R.id.widget_session_name, clean(item.optString("name"), 90));

            String project = clean(item.optString("project"), 30);
            row.setTextViewText(R.id.widget_session_project,
                project.isEmpty() ? "Session" : "▣  " + project);
            // A project receives a repeatable accent, distinct from the state badge.
            row.setTextColor(R.id.widget_session_project,
                project.isEmpty() ? MUTED : projectAccent(project));
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

    private static int projectAccent(String project) {
        int[] colors = new int[] { PURPLE, BLUE, GREEN, AMBER };
        return colors[Math.floorMod(project.hashCode(), colors.length)];
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
