package ru.example.interview.engine;

/**
 * Примитивная хеш-таблица long→int с открытой адресацией: без боксинга и аллокаций при чтении.
 *
 * @version 1.0.0
 */
public final class LongIntMap {
    private long[] keys;
    private int[] vals;
    private boolean[] used;
    private int size;
    private int mask;

    /**
     * Создаёт таблицу частот с ожидаемой ёмкостью.
     *
     * @param expected ожидаемое количество записей
     */
    public LongIntMap(int expected) {
        int cap = 16;
        while (cap < expected * 2) cap <<= 1;
        alloc(cap);
    }

    private void alloc(int cap) {
        keys = new long[cap];
        vals = new int[cap];
        used = new boolean[cap];
        mask = cap - 1;
    }

    private static int slot(long k, int mask) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return (int) k & mask;
    }

    /**
     * Возвращает значение по ключу либо заданное значение по умолчанию.
     *
     * @param key ключ
     * @param dflt значение при отсутствии ключа
     * @return целочисленное значение
     */
    public int get(long key, int dflt) {
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) return vals[i];
            i = (i + 1) & mask;
        }
        return dflt;
    }

    /**
     * Добавляет или заменяет значение по числовому ключу.
     *
     * @param key ключ
     * @param val значение
     */
    public void put(long key, int val) {
        if ((size + 1) * 2 > keys.length) grow();
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) {
                vals[i] = val;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        vals[i] = val;
        size++;
    }

    private void grow() {
        long[] ok = keys;
        int[] ov = vals;
        boolean[] ou = used;
        alloc(ok.length * 2);
        size = 0;
        for (int i = 0; i < ok.length; i++) {
            if (ou[i]) put(ok[i], ov[i]);
        }
    }

    /**
     * Возвращает число записей.
     *
     * @return целочисленное значение
     */
    public int size() {
        return size;
    }
}
