package com.openchamber.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class UsageWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WidgetRenderer.refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                         int widgetId, Bundle options) {
        super.onAppWidgetOptionsChanged(context, manager, widgetId, options);
        manager.updateAppWidget(widgetId, WidgetRenderer.render(context, true, widgetId));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (WidgetRenderer.NEXT_USAGE_ACTION.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetRenderer.nextPage(context, id);
        }
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) {
            context.getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE)
                .edit().remove("usage_page_" + id).apply();
        }
    }
}
