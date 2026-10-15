package com.example.bledobwidget;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/** BLEDOB Lights 2.0: an offline, dependency-free Lotus Lantern replacement. */
public class MainActivity extends Activity implements LiveBleController.Listener {
    private static final int PERMISSION_REQUEST = 42;
    private static final int BG = 0xff0b101a;
    private static final int PANEL = 0xff172132;
    private static final int PANEL_LIGHT = 0xff202c40;
    private static final int TEXT = 0xfff4f7fc;
    private static final int MUTED = 0xff91a0b5;
    private static final int ACCENT = 0xff42e4be;
    private static final int[] PRESETS = {
            0xffff474d, 0xffffa43a, 0xfffce77d, 0xff32db8a,
            0xff32ccff, 0xff3b7bff, 0xffaa65f4, 0xffffffff
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LiveBleController live;
    private TextView status, deviceLabel, brightnessValue, colorValue, powerLabel;
    private TextView previewName;
    private View statusDot, colorPreview;
    private ColorWheelView wheel;
    private SeekBar brightness;
    private Button powerButton, scanButton;
    private ListView deviceList;
    private ArrayAdapter<String> scanAdapter;
    private final ArrayList<String> addresses = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Runnable scanEnd;
    private Runnable pendingColorSend, pendingBrightnessSend;
    private int chosenColor;
    private int chosenBrightness;
    private Spinner protocol;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        live = new LiveBleController(this, this);
        chosenColor = LedWidgetProvider.prefs(this).getInt("color", 0xff35cffa);
        chosenBrightness = LedWidgetProvider.prefs(this).getInt("brightness", 85);
        buildUi();
        updatePowerLabel();
        updateColorUi();
        refreshDeviceLabel();
        onStatus("Подготовка Bluetooth…", false);
    }

    private void buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(24), dp(20), dp(38));
        scroller.addView(content);
        setContentView(scroller);

        // App header
        LinearLayout head = row(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.widget_power);
        head.addView(icon, lp(dp(56), dp(56)));
        LinearLayout brand = column();
        brand.setPadding(dp(12), 0, 0, 0);
        brand.addView(text("BLEDOB", 27, TEXT, true));
        brand.addView(text("LIGHT CONTROL", 11, ACCENT, true));
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(head);

        LinearLayout statusRow = row(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(12), dp(11), dp(12), dp(11));
        statusRow.setBackground(shape(PANEL, 16));
        statusDot = new View(this);
        statusDot.setBackground(shape(0xffffbd69, 50));
        statusRow.addView(statusDot, lp(dp(9), dp(9)));
        status = text("Подключение…", 13, MUTED, false);
        LinearLayout.LayoutParams stat = lp(-2, -2); stat.leftMargin = dp(10);
        statusRow.addView(status, stat);
        LinearLayout.LayoutParams statusLp = lp(-1, -2); statusLp.topMargin = dp(18);
        content.addView(statusRow, statusLp);

        LinearLayout hero = column();
        hero.setBackground(shape(PANEL, 25));
        hero.setPadding(dp(18), dp(18), dp(18), dp(20));
        LinearLayout heroRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout powerInfo = column();
        powerInfo.addView(text("СВЕТ", 12, MUTED, true));
        powerLabel = text("Выключено", 24, TEXT, true);
        powerInfo.addView(powerLabel);
        heroRow.addView(powerInfo, new LinearLayout.LayoutParams(0, -2, 1));
        powerButton = button("ВКЛЮЧИТЬ", ACCENT, BG);
        heroRow.addView(powerButton, lp(dp(134), dp(48)));
        powerButton.setOnClickListener(v -> togglePower());
        hero.addView(heroRow);

        LinearLayout colorCard = row(Gravity.CENTER_VERTICAL);
        colorCard.setPadding(dp(14), dp(11), dp(14), dp(11));
        colorCard.setBackground(shape(PANEL_LIGHT, 17));
        colorPreview = new View(this);
        colorCard.addView(colorPreview, lp(dp(42), dp(42)));
        LinearLayout colorLabels = column();
        colorLabels.setPadding(dp(13), 0, 0, 0);
        colorLabels.addView(text("Выбранный цвет", 12, MUTED, false));
        colorValue = text("#35CFFA", 18, TEXT, true);
        colorLabels.addView(colorValue);
        colorCard.addView(colorLabels, new LinearLayout.LayoutParams(0, -2, 1));
        previewName = text("RGB", 13, MUTED, true);
        colorCard.addView(previewName);
        LinearLayout.LayoutParams cp = lp(-1, -2); cp.topMargin = dp(17);
        hero.addView(colorCard, cp);
        LinearLayout.LayoutParams heroLp = lp(-1, -2); heroLp.topMargin = dp(18);
        content.addView(hero, heroLp);

        // The wheel draws actual HSV colors; dragging schedules nonblocking BLE sends.
        LinearLayout colorSection = column();
        colorSection.setPadding(dp(16), dp(17), dp(16), dp(17));
        colorSection.setBackground(shape(PANEL, 25));
        colorSection.addView(text("ВЫБОР ЦВЕТА", 14, TEXT, true));
        TextView hint = text("Коснись круга или проведи пальцем", 12, MUTED, false);
        LinearLayout.LayoutParams hintLp = lp(-1, -2); hintLp.topMargin = dp(5);
        colorSection.addView(hint, hintLp);
        wheel = new ColorWheelView(this);
        wheel.selectColor(chosenColor);
        wheel.setListener((rgb, finalChange) -> {
            chosenColor = rgb;
            updateColorUi();
            queueColor(finalChange);
        });
        LinearLayout.LayoutParams wheelLp = lp(-1, dp(300));
        wheelLp.topMargin = dp(10);
        colorSection.addView(wheel, wheelLp);
        colorSection.addView(text("ГОТОВЫЕ ЦВЕТА", 11, MUTED, true));
        LinearLayout presetRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams presetLp = lp(-1, dp(48)); presetLp.topMargin = dp(9);
        for (int c : PRESETS) {
            FrameLayout slot = new FrameLayout(this);
            View swatch = new View(this);
            swatch.setBackground(outlinedCircle(c));
            FrameLayout.LayoutParams circleLp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER);
            slot.addView(swatch, circleLp);
            presetRow.addView(slot, new LinearLayout.LayoutParams(0, -1, 1));
            slot.setContentDescription(String.format(Locale.ROOT, "RGB #%06X", c & 0xffffff));
            slot.setOnClickListener(v -> {
                chosenColor = c;
                wheel.selectColor(c);
                updateColorUi();
                queueColor(true);
            });
        }
        colorSection.addView(presetRow, presetLp);
        LinearLayout.LayoutParams wheelSectionLp = lp(-1, -2); wheelSectionLp.topMargin = dp(14);
        content.addView(colorSection, wheelSectionLp);

        LinearLayout brightCard = column();
        brightCard.setPadding(dp(16), dp(16), dp(16), dp(13));
        brightCard.setBackground(shape(PANEL, 25));
        LinearLayout brightTitle = row(Gravity.CENTER_VERTICAL);
        brightTitle.addView(text("ЯРКОСТЬ", 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        brightnessValue = text(chosenBrightness + "%", 19, ACCENT, true);
        brightTitle.addView(brightnessValue);
        brightCard.addView(brightTitle);
        brightness = new SeekBar(this);
        brightness.setMax(99);
        brightness.setProgress(chosenBrightness - 1);
        if (Build.VERSION.SDK_INT >= 21) {
            brightness.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            brightness.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        }
        LinearLayout.LayoutParams sliderLp = lp(-1, dp(42)); sliderLp.topMargin = dp(9);
        brightCard.addView(brightness, sliderLp);
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                chosenBrightness = progress + 1;
                brightnessValue.setText(chosenBrightness + "%");
                queueBrightness(false);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { queueBrightness(true); }
        });
        LinearLayout.LayoutParams brightLp = lp(-1, -2); brightLp.topMargin = dp(14);
        content.addView(brightCard, brightLp);

        // Configuration and shortcut, collapsed visually into the bottom section.
        LinearLayout settings = column();
        settings.setPadding(dp(16), dp(16), dp(16), dp(18));
        settings.setBackground(shape(PANEL, 25));
        settings.addView(text("УСТРОЙСТВО И НАСТРОЙКИ", 14, TEXT, true));
        deviceLabel = text("", 13, MUTED, false);
        LinearLayout.LayoutParams deviceLabelLp = lp(-1, -2); deviceLabelLp.topMargin = dp(10);
        settings.addView(deviceLabel, deviceLabelLp);
        LinearLayout connectionButtons = row(Gravity.CENTER_VERTICAL);
        Button connect = button("Подключить", PANEL_LIGHT, TEXT);
        Button scan = button("Найти BLEDOB", PANEL_LIGHT, TEXT);
        scanButton = scan;
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(44), 1);
        left.rightMargin = dp(8);
        connectionButtons.addView(connect, left);
        connectionButtons.addView(scan, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams buttonsLp = lp(-1, -2); buttonsLp.topMargin = dp(13);
        settings.addView(connectionButtons, buttonsLp);
        connect.setOnClickListener(v -> {
            if (ensurePermissions()) live.connect();
        });
        scan.setOnClickListener(v -> {
            if (ensurePermissions()) startScan();
        });

        deviceList = new ListView(this);
        deviceList.setBackground(shape(PANEL_LIGHT, 12));
        scanAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextColor(TEXT);
                item.setTextSize(13);
                return item;
            }
        };
        deviceList.setAdapter(scanAdapter);
        deviceList.setVisibility(View.GONE);
        LinearLayout.LayoutParams listLp = lp(-1, dp(176)); listLp.topMargin = dp(10);
        settings.addView(deviceList, listLp);
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            String address = addresses.get(position);
            LedWidgetProvider.prefs(this).edit().putString("address", address).apply();
            stopScan();
            deviceList.setVisibility(View.GONE);
            refreshDeviceLabel();
            LedWidgetProvider.render(this, null);
            live.connect();
        });

        TextView protTitle = text("Вариант BLE-протокола", 12, MUTED, false);
        LinearLayout.LayoutParams protLp = lp(-1, -2); protLp.topMargin = dp(13);
        settings.addView(protTitle, protLp);
        protocol = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"Lotus Lantern (основной)", "BLEDOB / LotusLamp X (альтернативный)"}) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextColor(TEXT);
                return item;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView item = (TextView) super.getDropDownView(position, convertView, parent);
                item.setTextColor(0xff172132);
                return item;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocol.setAdapter(adapter);
        protocol.setSelection(LedWidgetProvider.prefs(this).getInt("protocol", 0));
        settings.addView(protocol, lp(-1, dp(47)));
        protocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LedWidgetProvider.prefs(MainActivity.this).edit().putInt("protocol", position).apply();
            }
        });
        TextView protoHint = text("Если питание работает, но яркость нет — попробуй другой вариант протокола.", 12, MUTED, false);
        settings.addView(protoHint);

        Button shortcut = button("Добавить кнопку 1×1 на рабочий стол", 0xff283852, TEXT);
        LinearLayout.LayoutParams shLp = lp(-1, dp(48)); shLp.topMargin = dp(18);
        settings.addView(shortcut, shLp);
        shortcut.setOnClickListener(v -> pinPowerShortcut());
        TextView footer = text("Работает напрямую через Bluetooth, без интернета. Настройки сохраняются на телефоне.", 12, MUTED, false);
        LinearLayout.LayoutParams footLp = lp(-1, -2); footLp.topMargin = dp(12);
        settings.addView(footer, footLp);
        LinearLayout.LayoutParams setLp = lp(-1, -2); setLp.topMargin = dp(14);
        content.addView(settings, setLp);
    }

    private void queueColor(boolean immediate) {
        if (pendingColorSend != null) ui.removeCallbacks(pendingColorSend);
        final int rgb = chosenColor;
        pendingColorSend = () -> sendColor(rgb);
        ui.postDelayed(pendingColorSend, immediate ? 0 : 95);
    }

    private void sendColor(int rgb) {
        if (!ensurePermissions()) return;
        if (!hasDevice()) { onStatus("Сначала выбери BLEDOB", false); return; }
        live.setColor(rgb);
    }

    private void queueBrightness(boolean immediate) {
        if (pendingBrightnessSend != null) ui.removeCallbacks(pendingBrightnessSend);
        final int percent = chosenBrightness;
        pendingBrightnessSend = () -> {
            if (!ensurePermissions()) return;
            if (!hasDevice()) { onStatus("Сначала выбери BLEDOB", false); return; }
            live.setBrightness(percent);
        };
        ui.postDelayed(pendingBrightnessSend, immediate ? 0 : 120);
    }

    private void togglePower() {
        if (!ensurePermissions()) return;
        if (!hasDevice()) { onStatus("Сначала выбери контроллер внизу", false); return; }
        boolean state = LedWidgetProvider.prefs(this).getBoolean("on", false);
        live.setPower(!state);
    }

    private boolean hasDevice() { return !LedWidgetProvider.prefs(this).getString("address", "").isEmpty(); }

    private void updatePowerLabel() {
        boolean on = LedWidgetProvider.prefs(this).getBoolean("on", false);
        powerLabel.setText(on ? "Включено" : "Выключено");
        powerButton.setText(on ? "ВЫКЛЮЧИТЬ" : "ВКЛЮЧИТЬ");
        powerButton.setBackground(shape(on ? PANEL_LIGHT : ACCENT, 16));
        powerButton.setTextColor(on ? TEXT : BG);
    }

    private void updateColorUi() {
        colorValue.setText(String.format(Locale.ROOT, "#%06X", chosenColor & 0xffffff));
        colorPreview.setBackground(shape(chosenColor, 14));
    }

    private void refreshDeviceLabel() {
        String mac = LedWidgetProvider.prefs(this).getString("address", "");
        deviceLabel.setText(mac.isEmpty() ? "Контроллер не выбран" : "Выбран BLEDOB · " + mac);
    }

    @Override public void onStatus(String message, boolean ready) {
        if (status == null) return;
        status.setText(message);
        status.setTextColor(ready ? ACCENT : MUTED);
        statusDot.setBackground(shape(ready ? ACCENT : 0xffffbd69, 40));
    }

    @Override public void onApplied() { updatePowerLabel(); }

    private boolean ensurePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                onStatus("Bluetooth не разрешён в настройках телефона", false);
                return;
            }
        }
        if (hasDevice()) live.connect();
        else onStatus("Разрешения получены · выбери BLEDOB", false);
    }

    private void startScan() {
        stopScan();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { onStatus("Включи Bluetooth", false); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { onStatus("BLE-сканер недоступен", false); return; }
        addresses.clear(); seen.clear(); scanAdapter.clear();
        deviceList.setVisibility(View.VISIBLE);
        onStatus("Поиск устройств (12 секунд)…", false);
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                try {
                    BluetoothDevice device = result.getDevice();
                    String address = device.getAddress();
                    if (!seen.add(address)) return;
                    String name = device.getName();
                    if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
                    final String title = (name == null ? "Устройство BLE" : name) + " · " + address;
                    runOnUiThread(() -> {
                        addresses.add(address);
                        scanAdapter.add(title);
                    });
                } catch (SecurityException ignored) {}
            }
            @Override public void onScanFailed(int errorCode) {
                runOnUiThread(() -> onStatus("Ошибка поиска BLE: " + errorCode, false));
            }
        };
        try { scanner.startScan(scanCallback); }
        catch (Exception e) { stopScan(); onStatus("Ошибка запуска поиска: " + e.getMessage(), false); return; }
        scanEnd = () -> {
            stopScan();
            onStatus("Поиск окончен · выбери BLEDOB в списке", false);
        };
        ui.postDelayed(scanEnd, 12000);
    }

    private void stopScan() {
        if (scanEnd != null) ui.removeCallbacks(scanEnd);
        scanEnd = null;
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanCallback = null;
        scanner = null;
    }

    private void pinPowerShortcut() {
        ShortcutManager mgr = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
        if (mgr == null || !mgr.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Лаунчер не поддерживает добавление ярлыка", Toast.LENGTH_LONG).show();
            return;
        }
        Intent toggle = new Intent(this, ToggleActivity.class)
                .setAction("com.example.bledobwidget.PIN_TOGGLE_V2")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "bledob_power_v2")
                .setShortLabel("Свет")
                .setLongLabel("BLEDOB · вкл/выкл")
                .setIcon(Icon.createWithBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.widget_power)))
                .setIntent(toggle)
                .build();
        try {
            mgr.requestPinShortcut(info, null);
            Toast.makeText(this, "Подтверди добавление на рабочий стол", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось добавить ярлык: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (live != null && ensurePermissions()) {
            refreshDeviceLabel();
            updatePowerLabel();
            if (hasDevice()) live.connect();
            else onStatus("Выбери контроллер в настройках ниже", false);
        }
    }

    @Override protected void onPause() {
        stopScan();
        if (pendingColorSend != null) ui.removeCallbacks(pendingColorSend);
        if (pendingBrightnessSend != null) ui.removeCallbacks(pendingBrightnessSend);
        live.close();
        super.onPause();
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    private LinearLayout row(int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        return row;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(sp);
        if (bold) label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return label;
    }

    private Button button(String label, int bg, int fg) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(fg);
        button.setBackground(shape(bg, 14));
        return button;
    }

    private GradientDrawable shape(int color, int cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(cornerDp));
        return drawable;
    }

    private GradientDrawable outlinedCircle(int color) {
        GradientDrawable bg = shape(color, 50);
        bg.setStroke(dp(2), 0x45ffffff);
        return bg;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}
