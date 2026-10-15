package com.example.bledobwidget;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.UUID;

public class BleCommandService extends Service {
    public static final String ACTION_TOGGLE = "com.example.bledobwidget.SEND_TOGGLE";
    public static final String ACTION_SET = "com.example.bledobwidget.SEND_EXPLICIT";
    public static final String EXTRA_ON = "requested_on";
    private static final UUID SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final String CHANNEL = "bledob_control";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private boolean targetOn;
    private boolean finished;
    private boolean busy;
    private Runnable timeout;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Управление BLEDOB", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (busy) return START_NOT_STICKY;
        busy = true;
        finished = false;
        try {
            Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle("BLEDOB")
                    .setContentText("Отправка команды освещению")
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(444, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(444, builder.build());
        } catch (Exception e) { LedWidgetProvider.render(this, "ОШИБКА"); stopSelf(); return START_NOT_STICKY; }

        SharedPreferences p = LedWidgetProvider.prefs(this);
        String address = p.getString("address", "");
        if (address.isEmpty() || !LedWidgetProvider.hasConnectPermission(this)) { fail("Не выбран контроллер или нет разрешения"); return START_NOT_STICKY; }
        targetOn = ACTION_SET.equals(intent.getAction()) ? intent.getBooleanExtra(EXTRA_ON, false) : !p.getBoolean("on", false);
        LedWidgetProvider.render(this, "ЖДИТЕ");
        timeout = () -> fail("Тайм-аут Bluetooth");
        handler.postDelayed(timeout, 12000);
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { fail("Bluetooth выключен"); return START_NOT_STICKY; }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) fail("Не удалось подключиться");
        } catch (Exception e) { fail(e.toString()); }
        return START_NOT_STICKY;
    }

    private byte[] command(boolean on) {
        // Keep the exact power packet variant already proven on this user's BLEDOB.
        int protocol = LedWidgetProvider.prefs(this).getInt("protocol", 0);
        return LedProtocol.power(on, protocol);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (finished) return;
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                try { if (!g.discoverServices()) fail("GATT: список сервисов недоступен"); }
                catch (Exception e) { fail(e.toString()); }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                fail("Ошибка соединения GATT: " + status);
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (finished) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Не найдены GATT-сервисы"); return; }
            BluetoothGattService service = g.getService(SERVICE);
            BluetoothGattCharacteristic characteristic = service != null ? service.getCharacteristic(CHAR) : null;
            if (characteristic == null) { fail("У устройства нет FFF0/FFF3"); return; }
            try {
                int properties = characteristic.getProperties();
                int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                    fail("Характеристика FFF3 недоступна для записи"); return;
                }
                byte[] bytes = command(targetOn);
                boolean ok;
                if (Build.VERSION.SDK_INT >= 33) {
                    int result = g.writeCharacteristic(characteristic, bytes, writeType);
                    ok = result == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                } else {
                    characteristic.setWriteType(writeType);
                    characteristic.setValue(bytes);
                    ok = g.writeCharacteristic(characteristic);
                }
                if (!ok) fail("BLE: не удалось отправить команду");
            } catch (Exception e) { fail(e.toString()); }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (finished) return;
            if (status == BluetoothGatt.GATT_SUCCESS) success();
            else fail("Ошибка записи BLE: " + status);
        }
    };

    private void success() { complete(true, ""); }
    private void fail(String why) { Log.w("BLEDOB", why); complete(false, why); }
    private synchronized void complete(boolean success, String error) {
        if (finished) return;
        finished = true;
        if (timeout != null) handler.removeCallbacks(timeout);
        if (success) LedWidgetProvider.prefs(this).edit().putBoolean("on", targetOn).apply();
        handler.post(() -> {
            LedWidgetProvider.render(this, success ? null : "ОШИБКА");
            try { if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; } } catch (Exception ignored) {}
            busy = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }
    @Override public void onDestroy() {
        if (timeout != null) handler.removeCallbacks(timeout);
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        super.onDestroy();
    }
}
