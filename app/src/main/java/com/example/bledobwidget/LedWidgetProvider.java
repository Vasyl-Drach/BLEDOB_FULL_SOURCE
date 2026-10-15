package com.example.bledobwidget;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.RemoteViews;
import android.content.ComponentName;

public class LedWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_TOGGLE = "com.example.bledobwidget.TOGGLE";
    static final String PREF = "ledsettings";

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    static boolean hasConnectPermission(Context ctx) {
        return Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            if (prefs(context).getString("address", "").isEmpty() || !hasConnectPermission(context)) {
                Intent settings = new Intent(context, MainActivity.class);
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
                return;
            }
            Intent service = new Intent(context, BleCommandService.class);
            service.setAction(BleCommandService.ACTION_TOGGLE);
            try { context.startForegroundService(service); }
            catch (Exception e) { render(context, "ОШИБКА"); }
            return;
        }
        super.onReceive(context, intent);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) renderOne(context, manager, id, null);
    }

    public static void render(Context context, String status) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, LedWidgetProvider.class));
        for (int id : ids) renderOne(context, mgr, id, status);
    }

    private static void renderOne(Context context, AppWidgetManager mgr, int id, String status) {
        SharedPreferences p = prefs(context);
        boolean configured = !p.getString("address", "").isEmpty();
        boolean on = p.getBoolean("on", false);
        boolean busy = "ЖДИТЕ".equals(status);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        // The PNG has a real alpha channel; never draw a colored tile behind it.
        // Dim the icon when off instead of changing the entire background.
        int alpha = "ОШИБКА".equals(status) ? 75 : busy ? 150 : on ? 255 : 140;
        views.setInt(R.id.widget_icon, "setImageAlpha", alpha);
        Intent click = configured ? new Intent(context, LedWidgetProvider.class).setAction(ACTION_TOGGLE)
                : new Intent(context, MainActivity.class);
        PendingIntent pi = configured
                ? PendingIntent.getBroadcast(context, 1001, click, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getActivity(context, 1002, click, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        mgr.updateAppWidget(id, views);
    }
}
