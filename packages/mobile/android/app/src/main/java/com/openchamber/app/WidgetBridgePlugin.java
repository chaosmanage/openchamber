package com.openchamber.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** The connected WebView writes redacted widget snapshots; widgets never receive API tokens. */
@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {
    /**
     * Native refresh intent queue; web credentials and network access remain
     * exclusively in the connected WebView. Polling this lightweight local
     * counter catches taps even when Android does not emit appUrlOpen.
     */
    @PluginMethod
    public void pendingRefresh(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE);
        JSObject result = new JSObject();
        result.put("requested", prefs.getInt("refresh_requested", 0));
        result.put("acknowledged", prefs.getInt("refresh_acknowledged", 0));
        call.resolve(result);
    }

    @PluginMethod
    public void acknowledgeRefresh(PluginCall call) {
        Integer sequence = call.getInt("requested");
        if (sequence == null) {
            call.reject("Missing refresh sequence");
            return;
        }
        SharedPreferences prefs = getContext().getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE);
        int requested = prefs.getInt("refresh_requested", 0);
        int acknowledged = prefs.getInt("refresh_acknowledged", 0);
        if (sequence > acknowledged && sequence <= requested) {
            prefs.edit().putInt("refresh_acknowledged", sequence).apply();
        }
        call.resolve(new JSObject());
    }

    @PluginMethod
    public void update(PluginCall call) {
        try {
            String usage = call.getString("usage");
            String sessions = call.getString("sessions");
            if (usage == null || sessions == null || usage.length() > 65536 || sessions.length() > 65536) {
                call.reject("Invalid widget snapshots");
                return;
            }
            getContext().getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE)
                .edit().putString("usage", usage).putString("sessions", sessions).apply();
            WidgetRenderer.refresh(getContext());
            call.resolve(new JSObject());
        } catch (Exception error) {
            call.reject("Widget update failed", error);
        }
    }
}
