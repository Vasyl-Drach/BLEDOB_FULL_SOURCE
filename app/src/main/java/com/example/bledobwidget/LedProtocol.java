package com.example.bledobwidget;

/** Frames verified against public Lotus Lantern / ELK-BLEDOB implementations.
 *  The power-on variant is preserved from the working BLEDOB Widget 1.4 project.
 */
public final class LedProtocol {
    private LedProtocol() {}

    public static byte[] power(boolean on, int protocol) {
        if (protocol == 1) {
            return new byte[]{0x7e, 0x07, 0x04, (byte)(on ? 0xff : 0), 0x00,
                    (byte)(on ? 1 : 0), 0x02, 0x01, (byte)0xef};
        }
        return new byte[]{0x7e, 0x04, 0x04, (byte)(on ? 1 : 0), 0x00,
                (byte)(on ? 1 : 0), (byte)0xff, 0x00, (byte)0xef};
    }

    public static byte[] color(int argb) {
        return new byte[]{0x7e, 0x07, 0x05, 0x03,
                (byte)((argb >> 16) & 0xff), (byte)((argb >> 8) & 0xff),
                (byte)(argb & 0xff), 0x10, (byte)0xef};
    }

    public static byte[] brightness(int percent, int protocol) {
        int v = Math.max(1, Math.min(100, percent));
        if (protocol == 1) {
            // Documented ELK-BLEDOB / LotusLamp X colour brightness frame.
            return new byte[]{0x7e, 0x04, 0x01, (byte)v, 0x01,
                    (byte)0xff, 0x02, 0x01, (byte)0xef};
        }
        // Documented ELK-BLEDOM / Lotus Lantern brightness frame.
        return new byte[]{0x7e, 0x04, 0x01, (byte)v, (byte)0xff,
                (byte)0xff, (byte)0xff, 0x00, (byte)0xef};
    }
}
