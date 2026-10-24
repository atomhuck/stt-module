package ru.example.interview.export;

/**
 * Неизменяемое описание стиля ячейки. Дубликаты схлопываются в XlsxBook. Цвета — RRGGBB.
 *
 * @version 1.0.0
 */
public record XlsxStyle(boolean bold, boolean italic, double size, String color, String fill, boolean border,
                        String halign, String valign, boolean wrap, String numFmt) {

    public static final XlsxStyle BASE = new XlsxStyle(false, false, 10, "000000", null, false, null, "top", false, null);

    /**
     * Возвращает стиль с полужирным шрифтом.
     *
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle b() {
        return new XlsxStyle(true, italic, size, color, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с курсивным шрифтом.
     *
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle i() {
        return new XlsxStyle(bold, true, size, color, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с заданным размером шрифта.
     *
     * @param v новое значение
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle size(double v) {
        return new XlsxStyle(bold, italic, v, color, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с заданным цветом текста.
     *
     * @param rgb цвет в формате RGB
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle color(String rgb) {
        return new XlsxStyle(bold, italic, size, rgb, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с заданным цветом заливки.
     *
     * @param rgb цвет в формате RGB
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle fill(String rgb) {
        return new XlsxStyle(bold, italic, size, color, rgb, border, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с рамкой.
     *
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle bd() {
        return new XlsxStyle(bold, italic, size, color, fill, true, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с горизонтальным выравниванием.
     *
     * @param align выравнивание
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle h(String align) {
        return new XlsxStyle(bold, italic, size, color, fill, border, align, valign, wrap, numFmt);
    }

    /**
     * Возвращает стиль с вертикальным выравниванием.
     *
     * @param align выравнивание
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle v(String align) {
        return new XlsxStyle(bold, italic, size, color, fill, border, halign, align, wrap, numFmt);
    }

    /**
     * Возвращает стиль с переносом текста.
     *
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle w() {
        return new XlsxStyle(bold, italic, size, color, fill, border, halign, valign, true, numFmt);
    }

    /**
     * Возвращает стиль с форматом значения.
     *
     * @param code код формата
     * @return новый стиль; исходный объект не меняется
     */
    public XlsxStyle fmt(String code) {
        return new XlsxStyle(bold, italic, size, color, fill, border, halign, valign, wrap, code);
    }
}
