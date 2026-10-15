package com.example.bledobwidget;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.UUID;

/** A single GATT session for interactive controls (no connection per slider movement).
 *  Commands are serialized and coalesced: only the latest requested RGB and brightness
 *  values are sent after an in-flight BLE write completes.
 */
final class LiveBleController {
    interface Listener {
        void onStatus(String message, boolean ready);
        void onApplied();
    }

    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final UUID ALT_SERVICE_UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb");
    private static final UUID ALT_CHARACTERISTIC_UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private boolean ready;
    private boolean writing;
    private int writeSequence;
    private Runnable connectionTimeout;
    private Runnable writeTimeout;
    private Boolean pendingPower;
    private Integer pendingColor;
    private Integer pendingBrightness;
    private String inFlightKind;
    private int inFlightValue;

    // A fade is a series of acknowledged, spaced-out BLE brightness writes.
    private boolean fading;
    private boolean fadeToOn;
    private int fadePhase; // 0: initial ON, 1: brightness ramp, 2: final OFF
    private int fadeBrightnessFrom;
    private int fadeBrightnessTo;
    private long fadeStartedAt;
    private long lastFadeWriteAt;
    private int lastFadeBrightness;
    private int fadeEpoch;
    private int lastConfirmedBrightness = -1;
    private int inFlightFadeEpoch;
    private Runnable nextFadeStep;


    LiveBleController(Context ctx, Listener listener) {
        context = ctx.getApplicationContext();
        this.listener = listener;
    }

    void connect() {
        main.removeCallbacksAndMessages(null);
        release();
        String address = prefs().getString("address", "");
        if (address.isEmpty()) { status("Сначала выбери контроллер", false); return; }
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status("Разреши доступ к Bluetooth", false);
            return;
        }
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { status("Включи Bluetooth", false); return; }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            status("Подключение…", false);
            BluetoothGatt next = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (next == null) { status("Не удалось подключиться", false); return; }
            gatt = next;
            connectionTimeout = () -> {
                if (!ready && gatt == next) {
                    release();
                    status("Тайм-аут соединения · нажми «Подключить»", false);
                }
            };
            main.postDelayed(connectionTimeout, 11000);
        } catch (Exception e) { release(); status("Ошибка Bluetooth: " + e.getMessage(), false); }
    }

    void close() {
        main.removeCallbacksAndMessages(null);
        fading = false;
        lastConfirmedBrightness = -1;
        release();
    }

    boolean isReady() { return ready; }

    /** Includes an in-progress fade, so a second tap can reverse direction. */
    boolean requestedPower() { return fading ? fadeToOn : prefs().getBoolean("on", false); }

    void setPower(boolean on) {
        // A new tap replaces the old fade; stale BLE write callbacks are ignored.
        ++fadeEpoch;
        cancelNextFadeStep();
        fading = true;
        fadeToOn = on;
        fadePhase = on ? 0 : 1;
        // If a fade is reversed midway, start from the last confirmed level.
        fadeBrightnessFrom = on ? 1 : (lastConfirmedBrightness > 0
                ? lastConfirmedBrightness : FadeMath.clamp(prefs().getInt("brightness", 85)));
        fadeBrightnessTo = on ? FadeMath.clamp(prefs().getInt("brightness", 85)) : 1;
        fadeStartedAt = 0;
        lastFadeBrightness = -1;
        lastFadeWriteAt = 0;
        pendingPower = null;
        status(on ? "Плавное включение (1,5 с)…" : "Плавное выключение (1,5 с)…", true);
        requestPump();
    }

    void setColor(int rgb) {
        pendingColor = rgb | 0xff000000;
        if (!prefs().getBoolean("on", false)) pendingPower = true;
        requestPump();
    }

    void setBrightness(int value) {
        pendingBrightness = Math.max(1, Math.min(100, value));
        if (!prefs().getBoolean("on", false)) pendingPower = true;
        requestPump();
    }

    private SharedPreferences prefs() { return LedWidgetProvider.prefs(context); }
    private void status(String text, boolean connected) { listener.onStatus(text, connected); }

    private void requestPump() {
        if (gatt == null) connect();
        if (ready) pump();
    }

    private void pump() {
        if (!ready || writing || gatt == null || characteristic == null) return;
        final byte[] payload;
        final int protocol = prefs().getInt("protocol", 0);
        if (fading) {
            inFlightFadeEpoch = fadeEpoch;
            if (fadePhase == 0) {
                inFlightKind = "fade_power_on";
                inFlightValue = 1;
                payload = LedProtocol.power(true, protocol);
            } else if (fadePhase == 2) {
                inFlightKind = "fade_power_off";
                inFlightValue = 0;
                payload = LedProtocol.power(false, protocol);
            } else {
                long now = SystemClock.uptimeMillis();
                if (fadeStartedAt == 0) fadeStartedAt = now;
                long remaining = lastFadeWriteAt + FadeMath.STEP_INTERVAL_MS - now;
                if (lastFadeWriteAt != 0 && remaining > 0) {
                    scheduleFadeStep(remaining);
                    return;
                }
                int level = FadeMath.level(fadeBrightnessFrom, fadeBrightnessTo,
                        now - fadeStartedAt);
                // Rounding to whole percent creates repeated levels near darkness.
                // Skip duplicate BLE packets, but keep the original 1.5 s clock.
                if (level == lastFadeBrightness && now - fadeStartedAt < FadeMath.DURATION_MS) {
                    lastFadeWriteAt = now;
                    scheduleFadeStep(FadeMath.STEP_INTERVAL_MS);
                    return;
                }
                inFlightKind = "fade_brightness";
                inFlightValue = level;
                payload = LedProtocol.brightness(level, protocol);
                lastFadeBrightness = level;
                lastFadeWriteAt = now;
            }
        } else if (pendingPower != null) {
            boolean value = pendingPower;
            pendingPower = null;
            inFlightKind = "power";
            inFlightValue = value ? 1 : 0;
            payload = LedProtocol.power(value, protocol);
        } else if (pendingColor != null) {
            inFlightKind = "color";
            inFlightValue = pendingColor;
            pendingColor = null;
            payload = LedProtocol.color(inFlightValue);
        } else if (pendingBrightness != null) {
            inFlightKind = "brightness";
            inFlightValue = pendingBrightness;
            pendingBrightness = null;
            payload = LedProtocol.brightness(inFlightValue, protocol);
        } else return;

        try {
            int props = characteristic.getProperties();
            int type = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            writing = true;
            int sequence = ++writeSequence;
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gatt.writeCharacteristic(characteristic, payload, type)
                        == android.bluetooth.BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(type);
                characteristic.setValue(payload);
                accepted = gatt.writeCharacteristic(characteristic);
            }
            if (!accepted) { failWrite("Контроллер не принял команду"); return; }
            writeTimeout = () -> {
                if (writing && sequence == writeSequence) failWrite("Нет подтверждения Bluetooth");
            };
            main.postDelayed(writeTimeout, 5000);
        } catch (Exception e) { failWrite("Ошибка отправки: " + e.getMessage()); }
    }

    private void onWriteFinished(int statusCode) {
        if (!writing) return;
        clearWriteTimeout();
        writing = false;
        if (statusCode != BluetoothGatt.GATT_SUCCESS) {
            failWrite("Ошибка записи BLE: " + statusCode);
            return;
        }
        if (inFlightKind != null && inFlightKind.startsWith("fade_")) {
            if (!fading || inFlightFadeEpoch != fadeEpoch) { pump(); return; }
            if ("fade_power_on".equals(inFlightKind)) {
                fadePhase = 1;
                fadeStartedAt = SystemClock.uptimeMillis();
                lastFadeWriteAt = 0;
                lastFadeBrightness = -1;
                pump();
                return;
            }
            if ("fade_power_off".equals(inFlightKind)) {
                finishFade();
                return;
            }
            lastConfirmedBrightness = lastFadeBrightness;
            long elapsed = SystemClock.uptimeMillis() - fadeStartedAt;
            if (elapsed >= FadeMath.DURATION_MS && lastFadeBrightness == fadeBrightnessTo) {
                if (fadeToOn) finishFade();
                else { fadePhase = 2; pump(); }
            } else {
                // Acknowledge each write before sending another BLE packet.
                long earliest = lastFadeWriteAt + FadeMath.STEP_INTERVAL_MS;
                long now = SystemClock.uptimeMillis();
                // Respect BLE write spacing even when the 1.5 s deadline has
                // passed; otherwise the last packet can be sent in a burst.
                long targetTime = Math.max(now + 10L, earliest);
                scheduleFadeStep(targetTime - now);
            }
            return;
        }
        SharedPreferences.Editor edit = prefs().edit();
        if ("power".equals(inFlightKind)) edit.putBoolean("on", inFlightValue == 1);
        else if ("color".equals(inFlightKind)) edit.putInt("color", inFlightValue);
        else if ("brightness".equals(inFlightKind)) {
            edit.putInt("brightness", inFlightValue);
            lastConfirmedBrightness = inFlightValue;
        }
        edit.apply();
        LedWidgetProvider.render(context, null);
        listener.onApplied();
        pump();
    }

    private void scheduleFadeStep(long delayMs) {
        cancelNextFadeStep();
        nextFadeStep = this::pump;
        main.postDelayed(nextFadeStep, delayMs);
    }

    private void cancelNextFadeStep() {
        if (nextFadeStep != null) main.removeCallbacks(nextFadeStep);
        nextFadeStep = null;
    }

    private void finishFade() {
        cancelNextFadeStep();
        fading = false;
        prefs().edit().putBoolean("on", fadeToOn).apply();
        LedWidgetProvider.render(context, null);
        listener.onApplied();
        status("BLEDOB подключён", true);
        pump();
    }

    private void failWrite(String why) {
        cancelNextFadeStep();
        fading = false;
        clearWriteTimeout();
        writing = false;
        release();
        status(why + " · попробуй ещё раз", false);
    }

    private void clearWriteTimeout() {
        if (writeTimeout != null) main.removeCallbacks(writeTimeout);
        writeTimeout = null;
    }

    private void release() {
        cancelNextFadeStep();
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        connectionTimeout = null;
        clearWriteTimeout();
        ++writeSequence;
        writing = false;
        ready = false;
        characteristic = null;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    status("Поиск команд контроллера…", false);
                    try {
                        if (!g.discoverServices()) { release(); status("Не найдены BLE-сервисы", false); }
                    } catch (Exception e) { release(); status("Ошибка обнаружения сервисов", false); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || statusCode != BluetoothGatt.GATT_SUCCESS) {
                    release();
                    fading = false;
                    status("Нет соединения · нажми «Подключить»", false);
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            main.post(() -> {
                if (g != gatt) return;
                if (statusCode != BluetoothGatt.GATT_SUCCESS) { release(); status("Не удалось прочитать BLE-сервисы", false); return; }
                BluetoothGattService service = g.getService(SERVICE_UUID);
                BluetoothGattCharacteristic candidate = service == null ? null : service.getCharacteristic(CHARACTERISTIC_UUID);
                if (candidate == null) {
                    service = g.getService(ALT_SERVICE_UUID);
                    candidate = service == null ? null : service.getCharacteristic(ALT_CHARACTERISTIC_UUID);
                }
                if (candidate == null || (candidate.getProperties() & (
                        BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                    release(); status("Контроллер не поддерживает запись FFF3/FFE9", false); return;
                }
                characteristic = candidate;
                ready = true;
                if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
                connectionTimeout = null;
                status("BLEDOB подключён", true);
                pump();
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            main.post(() -> { if (g == gatt && c.getUuid().equals(characteristic == null ? null : characteristic.getUuid())) onWriteFinished(statusCode); });
        }
    };
}
