package com.example.bledobwidget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Android requires a pinned launcher shortcut to point at an Activity.
 * This Activity uses Theme.NoDisplay, never opens MainActivity, and finishes
 * immediately after dispatching the BLE command through the original service.
 */
public class ToggleActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (LedWidgetProvider.prefs(this).getString("address", "").isEmpty()) {
                Toast.makeText(this, "Сначала выбери BLEDOB в основном приложении", Toast.LENGTH_LONG).show();
                return;
            }
            if (!LedWidgetProvider.hasConnectPermission(this)) {
                Toast.makeText(this, "Открой Bingo LED и разреши Bluetooth", Toast.LENGTH_LONG).show();
                return;
            }
            Intent service = new Intent(this, BleCommandService.class);
            service.setAction(BleCommandService.ACTION_TOGGLE);
            startForegroundService(service);
        } catch (Exception ex) {
            Toast.makeText(this, "Ошибка переключения: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }
}
