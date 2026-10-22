package ru.example.interview.engine;

import java.util.Random;

/** Синтетическое аудио для тестов: «речь» — синус громкостью 6000, «тишина» — шум 30. */
public final class Pcm {
    public static final int RATE = 16000;
    public static final int FRAME_MS = 50;
    public static final int FRAME_SAMPLES = RATE * FRAME_MS / 1000;

    private static final Random RND = new Random(42);

    private Pcm() {}

    public static byte[] frame(boolean speech) {
        byte[] b = new byte[FRAME_SAMPLES * 2];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            double v = speech ? 6000 * Math.sin(2 * Math.PI * 220 * i / RATE) + RND.nextGaussian() * 200
                              : RND.nextGaussian() * 30;
            short s = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            b[2 * i] = (byte) (s & 0xff);
            b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return b;
    }
}
