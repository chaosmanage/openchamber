package com.openchamber.app;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android-native scrollable Sessions widget collection.
 *
 * No network calls or authentication: reads only the redacted, locally cached
 * widget snapshot written by the connected OpenChamber app.
 */
public class WidgetSessionsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new SessionFactory(getApplicationContext());
    }

    private static final class SessionFactory implements RemoteViewsFactory {
        private final Context context;
        private final List<JSONObject> entries = new ArrayList<>();
        private JSONObject palette = new JSONObject();

        SessionFactory(Context context) { this.context = context; }

        @Override public void onCreate() { onDataSetChanged(); }

        @Override public void onDataSetChanged() {
            String saved = context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE)
                .getString("sessions", "{}");
            entries.clear();
            try {
                JSONObject data = new JSONObject(saved);
                palette = data.optJSONObject("palette");
                if (palette == null) palette = new JSONObject();
                JSONArray rows = data.optJSONArray("rows");
                if (rows == null) return;
                for (int i = 0; i < Math.min(10, rows.length()); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) entries.add(row);
                }
            } catch (Exception ignored) { palette = new JSONObject(); }
        }

        @Override public void onDestroy() { entries.clear(); }
        @Override public int getCount() { return entries.size(); }
        @Override public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= entries.size()) return null;
            return WidgetRenderer.renderSessionRow(context, entries.get(position), palette);
        }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) {
            if (position < 0 || position >= entries.size()) return position;
            return entries.get(position).optString("id", "").hashCode();
        }
        @Override public boolean hasStableIds() { return true; }
    }
}
