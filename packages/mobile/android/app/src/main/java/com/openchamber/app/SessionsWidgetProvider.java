package com.openchamber.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class SessionsWidgetProvider extends AppWidgetProvider {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (WidgetDoneExpiryScheduler.EXPIRE_ACTION.equals(intent.getAction())) {
            // Data is cached locally. Rebuild the list and schedule the next
            // expiry without starting the app or asking for network access.
            WidgetRenderer.refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WidgetRenderer.refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                         int widgetId, Bundle options) {
        super.onAppWidgetOptionsChanged(context, manager, widgetId, options);
        manager.updateAppWidget(widgetId, WidgetRenderer.render(context, false, widgetId));
    }
}
