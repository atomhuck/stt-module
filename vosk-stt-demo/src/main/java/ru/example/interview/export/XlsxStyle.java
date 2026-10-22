package ru.example.interview.export;

 * @version 1.0.0
/** Неизменяемое описание стиля ячейки. Дубликаты схлопываются в XlsxBook. Цвета — RRGGBB. */
public record XlsxStyle(boolean bold, boolean italic, double size, String color, String fill, boolean border,
                        String halign, String valign, boolean wrap, String numFmt) {

    public static final XlsxStyle BASE = new XlsxStyle(false, false, 10, "000000", null, false, null, "top", false, null);

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle b() {
        return new XlsxStyle(true, italic, size, color, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle i() {
        return new XlsxStyle(bold, true, size, color, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Возвращает текущее значение без изменения состояния.
     *
     * @param v параметр операции
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle size(double v) {
        return new XlsxStyle(bold, italic, v, color, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @param rgb цвет в формате RGB
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle color(String rgb) {
        return new XlsxStyle(bold, italic, size, rgb, fill, border, halign, valign, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @param rgb цвет в формате RGB
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle fill(String rgb) {
        return new XlsxStyle(bold, italic, size, color, rgb, border, halign, valign, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle bd() {
        return new XlsxStyle(bold, italic, size, color, fill, true, halign, valign, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @param align выравнивание
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle h(String align) {
        return new XlsxStyle(bold, italic, size, color, fill, border, align, valign, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @param align выравнивание
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle v(String align) {
        return new XlsxStyle(bold, italic, size, color, fill, border, halign, align, wrap, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle w() {
        return new XlsxStyle(bold, italic, size, color, fill, border, halign, valign, true, numFmt);
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @param code код формата
     * @return тот же объект для последовательной настройки
     */
    public XlsxStyle fmt(String code) {
        return new XlsxStyle(bold, italic, size, color, fill, border, halign, valign, wrap, code);
    }
}
