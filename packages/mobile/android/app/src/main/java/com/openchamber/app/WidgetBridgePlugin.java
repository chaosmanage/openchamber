package com.openchamber.app;

import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** The connected WebView writes redacted widget snapshots; widgets never receive API tokens. */
@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {
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
