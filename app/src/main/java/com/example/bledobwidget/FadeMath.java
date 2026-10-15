package com.example.bledobwidget;

/** Timing and interpolation shared by the screen controls and the shortcut/widget. */
final class FadeMath {
    static final long DURATION_MS = 1500L;
    static final long STEP_INTERVAL_MS = 115L; // BLE writes are serialized, not flooded.

    private FadeMath() {}

    static int clamp(int brightness) {
        return Math.max(1, Math.min(100, brightness));
    }

    static int level(int from, int to, long elapsedMs) {
        long elapsed = Math.max(0L, Math.min(DURATION_MS, elapsedMs));
        int initial = clamp(from);
        int target = clamp(to);
        // Rounded linear interpolation. Keeps requested brightness unchanged in preferences.
        return clamp((int) Math.round(initial + (target - initial) * (elapsed / (double) DURATION_MS)));
    }
}
