package com.example.bledobwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.UUID;

/** Self-contained fade for the 1x1 pinned shortcut and the home-screen widget.
 * The foreground service remains alive until the 1.5-second fade finishes.
 */
public class BleCommandService extends Service {
    public static final String ACTION_TOGGLE = "com.example.bledobwidget.SEND_TOGGLE";
    public static final String ACTION_SET = "com.example.bledobwidget.SEND_EXPLICIT";
    public static final String EXTRA_ON = "requested_on";

    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final String CHANNEL_ID = "bledob_control";

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private boolean active;
    private boolean finished;
    private boolean targetOn;
    private int savedBrightness;
    private int protocol;
    private int phase; // 0 = initial power-on, 1 = fade brightness, 2 = final power-off
    private long fadeStart;
    private long lastBrightnessSentAt;
    private int lastBrightness;
    private boolean writing;
    private Runnable timeout;
    private Runnable nextStep;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "Bingo LED · управление", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (active) return START_NOT_STICKY; // Never overlap two GATT write sequences.
        active = true;
        finished = false;
        SharedPreferences prefs = LedWidgetProvider.prefs(this);
        String address = prefs.getString("address", "");
        targetOn = ACTION_SET.equals(intent.getAction())
                ? intent.getBooleanExtra(EXTRA_ON, false) : !prefs.getBoolean("on", false);
        savedBrightness = FadeMath.clamp(prefs.getInt("brightness", 85));
        protocol = prefs.getInt("protocol", 0);
        phase = targetOn ? 0 : 1;

        try {
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle("Bingo LED")
                    .setContentText(targetOn ? "Плавное включение…" : "Плавное выключение…")
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(444, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(444, notification);
        } catch (Exception e) { complete(false, e.getMessage()); return START_NOT_STICKY; }

        if (address.isEmpty() || !LedWidgetProvider.hasConnectPermission(this)) {
            complete(false, "Нет устройства или разрешения Bluetooth");
            return START_NOT_STICKY;
        }
        LedWidgetProvider.render(this, "ЖДИТЕ");
        timeout = () -> complete(false, "Тайм-аут Bluetooth при плавном переключении");
        main.postDelayed(timeout, 20000);
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                complete(false, "Bluetooth выключен");
                return START_NOT_STICKY;
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) complete(false, "Невозможно подключиться к BLEDOB");
        } catch (Exception e) { complete(false, "Соединение: " + e); }
        return START_NOT_STICKY;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            main.post(() -> {
                if (finished || g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!g.discoverServices()) complete(false, "Не найдены BLE-сервисы");
                    } catch (Exception e) { complete(false, e.toString()); }
                } else if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    complete(false, "Разрыв Bluetooth: " + status);
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (finished || g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { complete(false, "Сервисы BLE недоступны"); return; }
                BluetoothGattService service = g.getService(SERVICE_UUID);
                characteristic = service == null ? null : service.getCharacteristic(CHAR_UUID);
                if (characteristic == null) { complete(false, "Нет FFF0/FFF3"); return; }
                if ((characteristic.getProperties() &
                        (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                    complete(false, "Контроллер не принимает команды"); return;
                }
                if (targetOn) sendPower(true); // wake the LEDs before sending a low brightness
                else startFade();              // keep power on while dimming to minimum
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (finished || g != gatt || !writing) return;
                writing = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    complete(false, "Ошибка BLE-записи: " + status);
                    return;
                }
                if (phase == 0) { startFade(); return; }
                if (phase == 2) { complete(true, null); return; }
                // Dimming finished only when the last target level was actually written.
                if (SystemClock.uptimeMillis() - fadeStart >= FadeMath.DURATION_MS
                        && lastBrightness == (targetOn ? savedBrightness : 1)) {
                    if (targetOn) complete(true, null);
                    else { phase = 2; sendPower(false); }
                } else scheduleNextStep();
            });
        }
    };

    private void startFade() {
        phase = 1;
        fadeStart = SystemClock.uptimeMillis();
        lastBrightnessSentAt = 0L;
        lastBrightness = -1;
        sendFadeStep();
    }

    private void scheduleNextStep() {
        if (finished) return;
        if (nextStep != null) main.removeCallbacks(nextStep);
        long now = SystemClock.uptimeMillis();
        long earliest = Math.max(now + 20L, lastBrightnessSentAt + FadeMath.STEP_INTERVAL_MS);
        long deadline = fadeStart + FadeMath.DURATION_MS;
        long nextAt = Math.min(earliest, Math.max(now, deadline));
        nextStep = this::sendFadeStep;
        main.postDelayed(nextStep, Math.max(0L, nextAt - now));
    }

    private void sendFadeStep() {
        if (finished || writing || characteristic == null) return;
        long now = SystemClock.uptimeMillis();
        int level = FadeMath.level(targetOn ? 1 : savedBrightness,
                targetOn ? savedBrightness : 1, now - fadeStart);
        lastBrightness = level;
        lastBrightnessSentAt = now;
        write(LedProtocol.brightness(level, protocol));
    }

    private void sendPower(boolean on) { write(LedProtocol.power(on, protocol)); }

    private void write(byte[] data) {
        if (finished || gatt == null || characteristic == null || writing) return;
        try {
            int properties = characteristic.getProperties();
            int type = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            boolean accepted;
            writing = true;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gatt.writeCharacteristic(characteristic, data, type)
                        == android.bluetooth.BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(type);
                characteristic.setValue(data);
                accepted = gatt.writeCharacteristic(characteristic);
            }
            if (!accepted) { writing = false; complete(false, "Контроллер отказал в записи"); }
        } catch (Exception e) { writing = false; complete(false, "Ошибка BLE: " + e); }
    }

    private void complete(boolean ok, String error) {
        if (finished) return;
        finished = true;
        active = false;
        if (timeout != null) main.removeCallbacks(timeout);
        if (nextStep != null) main.removeCallbacks(nextStep);
        if (ok) LedWidgetProvider.prefs(this).edit().putBoolean("on", targetOn).apply();
        else Log.w("BingoLED", error == null ? "Неизвестная ошибка" : error);
        LedWidgetProvider.render(this, ok ? null : "ОШИБКА");
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; } }
        catch (Exception ignored) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override public void onDestroy() {
        if (timeout != null) main.removeCallbacks(timeout);
        if (nextStep != null) main.removeCallbacks(nextStep);
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; } }
        catch (Exception ignored) {}
        super.onDestroy();
    }
}
