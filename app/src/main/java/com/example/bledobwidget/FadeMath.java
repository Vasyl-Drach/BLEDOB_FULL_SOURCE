package com.example.bledobwidget;

/**
 * Shared perceptual fade for Bingo LED.
 * Brightness is transmitted as a 1..100 integer; no faster sampling can remove
 * the controller's native one-percent quantization (especially near OFF).
 */
final class FadeMath {
    static final long DURATION_MS = 1500L;
    static final long STEP_INTERVAL_MS = 50L; // BLE commands remain serialized / ACK-aware.
    static final double GAMMA = 2.2;

    private FadeMath() {}

    static int clamp(int brightness) {
        return Math.max(1, Math.min(100, brightness));
    }

    static int level(int from, int to, long elapsedMs) {
        final int initial = clamp(from);
        final int target = clamp(to);
        if (elapsedMs <= 0L) return initial;
        if (elapsedMs >= DURATION_MS) return target;
        if (initial == target) return initial;

        // Linear interpolation in perceived lightness rather than raw power.
        // Squaring the perceptual trajectory (exponent 2.2) makes the dark
        // portion gentler in BOTH directions while preserving endpoints.
        final double progress = elapsedMs / (double) DURATION_MS;
        final double perceivedFrom = Math.pow(initial / 100.0, 1.0 / GAMMA);
        final double perceivedTo = Math.pow(target / 100.0, 1.0 / GAMMA);
        final double perceived = perceivedFrom + (perceivedTo - perceivedFrom) * progress;
        final int result = (int) Math.round(100.0 * Math.pow(perceived, GAMMA));
        return Math.max(Math.min(initial, target), Math.min(Math.max(initial, target), result));
    }
}
