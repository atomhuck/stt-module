package ru.example.interview.export;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

 * @version 1.0.0
/** Лист книги. Индексы строк и столбцов — с нуля. */
public final class XlsxSheet {

    record Cell(Object value, String formula, int style) {}

    private record Merge(int r1, int c1, int r2, int c2) {}

    private record Cf(String sqref, String xml) {}

    final String name;
    final XlsxBook book;
    final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();
    final TreeMap<Integer, Double> colWidths = new TreeMap<>();
    final TreeMap<Integer, Double> rowHeights = new TreeMap<>();
    final List<Merge> merges = new ArrayList<>();
    final List<Cf> conditional = new ArrayList<>();
    final List<XlsxChart> charts = new ArrayList<>();
    final List<int[]> chartAnchors = new ArrayList<>();

    String tabColor;
    boolean showGrid = true;
    boolean landscape = true;
    int freezeRows;
    int freezeCols;
    int[] filter;
    boolean repeatHeader;
    private int priority = 1;

    XlsxSheet(XlsxBook book, String name) {
        this.book = book;
        this.name = name;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param rgb цвет в формате RGB
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet tabColor(String rgb) {
        this.tabColor = rgb;
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet hideGrid() {
        this.showGrid = false;
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param col номер колонки
     * @param w ширина колонки
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet width(int col, double w) {
        colWidths.put(col, w);
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param row номер строки
     * @param pts высота в пунктах
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet height(int row, double pts) {
        rowHeights.put(row, Math.min(409, pts));
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param rows число строк
     * @param cols число колонок
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet freeze(int rows, int cols) {
        this.freezeRows = rows;
        this.freezeCols = cols;
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet filter(int r1, int c1, int r2, int c2) {
        this.filter = new int[]{r1, c1, r2, c2};
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet repeatHeaderRow() {
        this.repeatHeader = true;
        return this;
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param r номер строки
     * @param c номер колонки
     * @param value значение ячейки
     * @param style стиль ячейки
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet set(int r, int c, Object value, XlsxStyle style) {
        rows.computeIfAbsent(r, k -> new TreeMap<>()).put(c, new Cell(value, null, book.style(style)));
        return this;
    }

     * @param r номер строки
     * @param c номер колонки
     * @param formula формула ячейки
     * @param cached кэшированное значение формулы
     * @param style стиль ячейки
     * @return тот же объект для последовательной настройки
    /** Формула с кэшированным значением (его покажут и программы, которые не пересчитывают формулы). */
    public XlsxSheet formula(int r, int c, String formula, Object cached, XlsxStyle style) {
        rows.computeIfAbsent(r, k -> new TreeMap<>()).put(c, new Cell(cached, formula, book.style(style)));
        return this;
    }

     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @param value значение ячейки
     * @param style стиль ячейки
     * @return тот же объект для последовательной настройки
    /** Объединённая область: стиль применяется ко всем ячейкам (чтобы работали границы и заливка). */
    public XlsxSheet merge(int r1, int c1, int r2, int c2, Object value, XlsxStyle style) {
        for (int r = r1; r <= r2; r++) {
            for (int c = c1; c <= c2; c++) {
                set(r, c, (r == r1 && c == c1) ? value : null, style);
            }
        }
        if (r1 != r2 || c1 != c2) merges.add(new Merge(r1, c1, r2, c2));
        return this;
    }

    /**
     * Добавляет настройки к создаваемому XLSX-документу.
     *
     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @param formula формула ячейки
     * @param cached кэшированное значение формулы
     * @param style стиль ячейки
     * @return тот же объект для последовательной настройки
     */
    public XlsxSheet mergeFormula(int r1, int c1, int r2, int c2, String formula, Object cached, XlsxStyle style) {
        merge(r1, c1, r2, c2, null, style);
        formula(r1, c1, formula, cached, style);
        return this;
    }

     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @param min параметр операции
     * @param mid параметр операции
     * @param max параметр операции
     * @return тот же объект для последовательной настройки
    /** Трёхцветная шкала красный → жёлтый → зелёный (0 / mid / max). */
    public XlsxSheet colorScale(int r1, int c1, int r2, int c2, double min, double mid, double max) {
        String sq = ref(r1, c1, r2, c2);
        String xml = "<conditionalFormatting sqref=\"" + sq + "\"><cfRule type=\"colorScale\" priority=\"" + (priority++)
                + "\"><colorScale><cfvo type=\"num\" val=\"" + num(min) + "\"/><cfvo type=\"num\" val=\"" + num(mid)
                + "\"/><cfvo type=\"num\" val=\"" + num(max) + "\"/><color rgb=\"FFF8696B\"/><color rgb=\"FFFFEB84\"/>"
                + "<color rgb=\"FF63BE7B\"/></colorScale></cfRule></conditionalFormatting>";
        conditional.add(new Cf(sq, xml));
        return this;
    }

     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @param text текст ответа или документа
     * @param fontRgb параметр операции
     * @param fillRgb параметр операции
     * @return тот же объект для последовательной настройки
    /** Подкраска ячеек, равных тексту (например «Да» → зелёная заливка). */
    public XlsxSheet cellEquals(int r1, int c1, int r2, int c2, String text, String fontRgb, String fillRgb) {
        int dxf = book.dxf(fontRgb, fillRgb);
        String sq = ref(r1, c1, r2, c2);
        String xml = "<conditionalFormatting sqref=\"" + sq + "\"><cfRule type=\"cellIs\" dxfId=\"" + dxf + "\" priority=\""
                + (priority++) + "\" operator=\"equal\"><formula>\"" + XlsxBook.esc(text) + "\"</formula></cfRule></conditionalFormatting>";
        conditional.add(new Cf(sq, xml));
        return this;
    }

     * @param chart параметр операции
     * @param fromRow параметр операции
     * @param fromCol параметр операции
     * @param toRow параметр операции
     * @param toCol параметр операции
     * @return тот же объект для последовательной настройки
    /** Диаграмма; якорь — ячейки левого верхнего и правого нижнего углов (включительно). */
    public XlsxSheet chart(XlsxChart chart, int fromRow, int fromCol, int toRow, int toCol) {
        charts.add(chart);
        chartAnchors.add(new int[]{fromRow, fromCol, toRow, toCol});
        return this;
    }

    // ------------------------------------------------------------------ адреса

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param c номер колонки
     * @return строка результата
     */
    public static String col(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param r номер строки
     * @param c номер колонки
     * @return строка результата
     */
    public static String addr(int r, int c) {
        return col(c) + (r + 1);
    }

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @return строка результата
     */
    public static String ref(int r1, int c1, int r2, int c2) {
        return (r1 == r2 && c1 == c2) ? addr(r1, c1) : addr(r1, c1) + ":" + addr(r2, c2);
    }

     * @param r1 первая строка
     * @param c1 первая колонка
     * @param r2 последняя строка
     * @param c2 последняя колонка
     * @return строка результата
    /** Абсолютная ссылка вида 'Лист'!$A$2:$A$9 — для диаграмм и определённых имён. */
    public String absRef(int r1, int c1, int r2, int c2) {
        return "'" + name.replace("'", "''") + "'!$" + col(c1) + "$" + (r1 + 1) + ":$" + col(c2) + "$" + (r2 + 1);
    }

    private static String num(double d) {
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ XML

    String xml(boolean selected, int drawingRelId) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        sb.append("<sheetPr>");
        if (tabColor != null) sb.append("<tabColor rgb=\"FF").append(tabColor).append("\"/>");
        sb.append("<pageSetUpPr fitToPage=\"1\"/></sheetPr>");

        int maxR = 0, maxC = 0;
        for (var re : rows.entrySet()) {
            maxR = Math.max(maxR, re.getKey());
            if (!re.getValue().isEmpty()) maxC = Math.max(maxC, re.getValue().lastKey());
        }
        sb.append("<dimension ref=\"A1:").append(addr(maxR, maxC)).append("\"/>");

        sb.append("<sheetViews><sheetView");
        if (selected) sb.append(" tabSelected=\"1\"");
        if (!showGrid) sb.append(" showGridLines=\"0\"");
        sb.append(" workbookViewId=\"0\">");
        if (freezeRows > 0 || freezeCols > 0) {
            String top = addr(freezeRows, freezeCols);
            sb.append("<pane");
            if (freezeCols > 0) sb.append(" xSplit=\"").append(freezeCols).append("\"");
            if (freezeRows > 0) sb.append(" ySplit=\"").append(freezeRows).append("\"");
            String active = freezeRows > 0 && freezeCols > 0 ? "bottomRight" : freezeRows > 0 ? "bottomLeft" : "topRight";
            sb.append(" topLeftCell=\"").append(top).append("\" activePane=\"").append(active).append("\" state=\"frozen\"/>");
            if (freezeRows > 0 && freezeCols > 0) {
                sb.append("<selection pane=\"topRight\"/><selection pane=\"bottomLeft\"/><selection pane=\"bottomRight\"/>");
            } else {
                sb.append("<selection pane=\"").append(active).append("\"/>");
            }
        }
        sb.append("</sheetView></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>");

        if (!colWidths.isEmpty()) {
            sb.append("<cols>");
            for (var e : colWidths.entrySet()) {
                sb.append("<col min=\"").append(e.getKey() + 1).append("\" max=\"").append(e.getKey() + 1)
                        .append("\" width=\"").append(num(e.getValue() + 0.71)).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }

        sb.append("<sheetData>");
        for (var re : rows.entrySet()) {
            int r = re.getKey();
            sb.append("<row r=\"").append(r + 1).append("\"");
            Double h = rowHeights.get(r);
            if (h != null) sb.append(" ht=\"").append(num(h)).append("\" customHeight=\"1\"");
            sb.append(">");
            for (var ce : re.getValue().entrySet()) {
                appendCell(sb, r, ce.getKey(), ce.getValue());
            }
            sb.append("</row>");
        }
        // строки, у которых задана только высота
        sb.append("</sheetData>");

        if (filter != null) {
            sb.append("<autoFilter ref=\"").append(ref(filter[0], filter[1], filter[2], filter[3])).append("\"/>");
        }
        if (!merges.isEmpty()) {
            sb.append("<mergeCells count=\"").append(merges.size()).append("\">");
            for (Merge m : merges) sb.append("<mergeCell ref=\"").append(ref(m.r1, m.c1, m.r2, m.c2)).append("\"/>");
            sb.append("</mergeCells>");
        }
        for (Cf cf : conditional) sb.append(cf.xml);
        sb.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("<pageSetup paperSize=\"9\" orientation=\"").append(landscape ? "landscape" : "portrait")
                .append("\" fitToHeight=\"0\"/>");
        if (drawingRelId > 0) sb.append("<drawing r:id=\"rId").append(drawingRelId).append("\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private void appendCell(StringBuilder sb, int r, int c, Cell cell) {
        String a = addr(r, c);
        Object v = cell.value();
        sb.append("<c r=\"").append(a).append("\"");
        if (cell.style() != 0) sb.append(" s=\"").append(cell.style()).append("\"");
        if (cell.formula() != null) {
            boolean str = v instanceof String || v == null;
            if (str) sb.append(" t=\"str\"");
            sb.append("><f>").append(XlsxBook.esc(cell.formula())).append("</f>");
            if (v instanceof Number n) sb.append("<v>").append(numeric(n)).append("</v>");
            else if (v != null) sb.append("<v>").append(XlsxBook.esc(String.valueOf(v))).append("</v>");
            sb.append("</c>");
            return;
        }
        if (v == null) {
            sb.append("/>");
        } else if (v instanceof Number n) {
            String s = numeric(n);
            if (s == null) sb.append("/>");
            else sb.append("><v>").append(s).append("</v></c>");
        } else {
            sb.append(" t=\"s\"><v>").append(book.sharedString(String.valueOf(v))).append("</v></c>");
        }
    }

    private static String numeric(Number n) {
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        if (n instanceof Integer || n instanceof Long) return String.valueOf(n.longValue());
        return java.math.BigDecimal.valueOf(d).toPlainString();
    }
}
