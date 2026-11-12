package com.openchamber.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Expires short-lived "Done" states even when the OpenChamber WebView is closed.
 *
 * Android may defer inexact alarms during Doze. The row renderer also checks
 * the clock on every collection render, so stale statuses never persist after
 * the next widget refresh even if the alarm is delayed.
 */
final class WidgetDoneExpiryScheduler {
    static final String EXPIRE_ACTION = "com.openchamber.app.action.EXPIRE_WIDGET_DONE";
    private static final int REQUEST_CODE = 7921;
    private WidgetDoneExpiryScheduler() {}

    static void reschedule(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;

        Intent intent = new Intent(context, SessionsWidgetProvider.class);
        intent.setAction(EXPIRE_ACTION);
        PendingIntent signal = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(signal);

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager.getAppWidgetIds(new ComponentName(context, SessionsWidgetProvider.class)).length == 0) return;

        String saved = context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE)
            .getString("sessions", "{}");
        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        try {
            JSONArray entries = new JSONObject(saved).optJSONArray("rows");
            if (entries == null) return;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null || !"Done".equals(entry.optString("status"))) continue;
                long until = entry.optLong("doneUntil", 0L);
                if (until > now && until < soonest) soonest = until;
            }
        } catch (Exception ignored) {
            return; // No valid completion data to expire.
        }

        if (soonest == Long.MAX_VALUE) return;
        // Does not require SCHEDULE_EXACT_ALARM; the wakeup may be deferred
        // by battery optimization but the UI never infers completion status.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, soonest + 1000L, signal);
    }
}
