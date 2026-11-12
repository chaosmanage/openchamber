package com.openchamber.app;

import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static final String ACTION_REFRESH_WIDGETS = "com.openchamber.app.action.REFRESH_WIDGETS";

    // A widget tap is an Activity PendingIntent. Record it synchronously in
    // native app storage before the WebView connects; works for both cold starts
    // and onNewIntent when the app is already on screen.
    private void recordWidgetRefresh(Intent intent) {
        if (intent == null || !ACTION_REFRESH_WIDGETS.equals(intent.getAction())) return;
        SharedPreferences prefs = getSharedPreferences("oc_sdk_widgets", Context.MODE_PRIVATE);
        int next = prefs.getInt("refresh_requested", 0) + 1;
        prefs.edit().putInt("refresh_requested", next).apply();
        // Prevent configuration changes from re-issuing the same tap.
        intent.setAction(Intent.ACTION_MAIN);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recordWidgetRefresh(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // App-local plugins must be registered before the bridge starts.
        registerPlugin(ClipboardTextPlugin.class);
        registerPlugin(FileSharePlugin.class);
        registerPlugin(ImagePastePlugin.class);
        registerPlugin(PushKeyPlugin.class);
        registerPlugin(ReadingAudioFocusPlugin.class);
        registerPlugin(WindowLayoutPlugin.class);
        registerPlugin(RuntimeWebSocketPlugin.class);
        registerPlugin(WidgetBridgePlugin.class);
        super.onCreate(savedInstanceState);
        recordWidgetRefresh(getIntent());
    }

    @Override
    protected void load() {
        // Capacitor inflates a plain CapacitorWebView, which tells the IME it accepts no
        // content. Swap in ours before the bridge resolves the WebView by id.
        setContentView(R.layout.activity_main);
        super.load();
    }
}
