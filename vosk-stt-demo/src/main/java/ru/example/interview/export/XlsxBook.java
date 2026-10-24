package ru.example.interview.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Минимальный генератор .xlsx (Office Open XML) без внешних зависимостей: стили, листы, формулы с кэшем,
 * объединённые ячейки, закрепление строк, автофильтр, условное форматирование, диаграммы.
 * Разметка сверена с файлами, которые создаёт XlsxWriter (заведомо открываются в Excel).
 * @version 1.0.0
 */
public final class XlsxBook {

    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final List<XlsxSheet> sheets = new ArrayList<>();
    private final Map<XlsxStyle, Integer> xfs = new LinkedHashMap<>();
    private final Map<String, Integer> fonts = new LinkedHashMap<>();
    private final Map<String, Integer> fills = new LinkedHashMap<>();
    private final Map<String, Integer> numFmts = new LinkedHashMap<>();
    private final List<String> dxfs = new ArrayList<>();
    private final Map<String, Integer> sst = new LinkedHashMap<>();
    private int sstRefs;

    /**
     * Создаёт пустую книгу XLSX.
     */
    public XlsxBook() {
        fonts.put("false|false|11.0|000000", 0);   // шрифт по умолчанию
        fills.put("none", 0);
        fills.put("gray125", 1);
    }

    /**
     * Создаёт и добавляет лист книги с указанным именем.
     *
     * @param name имя листа
     * @return созданный лист книги
     */
    public XlsxSheet sheet(String name) {
        XlsxSheet s = new XlsxSheet(this, name);
        sheets.add(s);
        return s;
    }

    // ------------------------------------------------------------------ реестры

    int style(XlsxStyle s) {
        if (s == null) return 0;
        Integer id = xfs.get(s);
        if (id != null) return id;
        fontId(s);
        fillId(s);
        numFmtId(s);
        id = xfs.size() + 1;           // 0 — стиль по умолчанию
        xfs.put(s, id);
        return id;
    }

    private int fontId(XlsxStyle s) {
        String key = s.bold() + "|" + s.italic() + "|" + s.size() + "|" + s.color();
        return fonts.computeIfAbsent(key, k -> fonts.size());
    }

    private int fillId(XlsxStyle s) {
        if (s.fill() == null) return 0;
        return fills.computeIfAbsent(s.fill(), k -> fills.size());
    }

    private int numFmtId(XlsxStyle s) {
        String f = s.numFmt();
        if (f == null || f.equals("General")) return 0;
        switch (f) {
            case "0": return 1;
            case "0.00": return 2;
            case "0%": return 9;
            default: return numFmts.computeIfAbsent(f, k -> 164 + numFmts.size());
        }
    }

    int dxf(String fontRgb, String fillRgb) {
        String xml = "<dxf><font><color rgb=\"FF" + fontRgb + "\"/></font><fill><patternFill><bgColor rgb=\"FF" + fillRgb
                + "\"/></patternFill></fill></dxf>";
        int i = dxfs.indexOf(xml);
        if (i >= 0) return i;
        dxfs.add(xml);
        return dxfs.size() - 1;
    }

    int sharedString(String s) {
        if (s.length() > 32767) s = s.substring(0, 32767);
        sstRefs++;
        return sst.computeIfAbsent(s, k -> sst.size());
    }

    // ------------------------------------------------------------------ XML-утилиты

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    boolean ok = c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                            || (c >= 0xE000 && c <= 0xFFFD) || Character.isSurrogate(c);
                    if (ok) sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String num(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ запись

    /**
     * Формирует выходное представление отчёта.
     *
     * @return байты XLSX-файла
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
        write(bos);
        return bos.toByteArray();
    }

    /**
     * Формирует выходное представление отчёта.
     *
     * @param out выходной поток
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    public void write(OutputStream out) throws IOException {
        // 1) листы и диаграммы (после этого известны все стили и общие строки)
        List<String> sheetXml = new ArrayList<>();
        List<String> drawingXml = new ArrayList<>();
        List<String> chartXml = new ArrayList<>();
        List<int[]> chartOfSheet = new ArrayList<>();   // {индекс листа, номер диаграммы с 1}
        int chartNo = 0;
        for (int i = 0; i < sheets.size(); i++) {
            XlsxSheet sh = sheets.get(i);
            boolean hasCharts = !sh.charts.isEmpty();
            sheetXml.add(sh.xml(i == 0, hasCharts ? 1 : 0));
            if (hasCharts) {
                StringBuilder d = new StringBuilder();
                d.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
                d.append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">");
                for (int k = 0; k < sh.charts.size(); k++) {
                    chartNo++;
                    int[] a = sh.chartAnchors.get(k);
                    d.append("<xdr:twoCellAnchor><xdr:from><xdr:col>").append(a[1]).append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>")
                            .append(a[0]).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>").append(a[3] + 1)
                            .append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(a[2] + 1)
                            .append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr>"
                                    + "<xdr:cNvPr id=\"").append(k + 2).append("\" name=\"Chart ").append(k + 1)
                            .append("\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/>"
                                    + "<a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData "
                                    + "uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart "
                                    + "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:r=\"").append(NS_REL)
                            .append("\" r:id=\"rId").append(k + 1).append("\"/></a:graphicData></a:graphic></xdr:graphicFrame>"
                                    + "<xdr:clientData/></xdr:twoCellAnchor>");
                    chartXml.add(chartXml(sh.charts.get(k), sh));
                    chartOfSheet.add(new int[]{i, chartNo});
                }
                d.append("</xdr:wsDr>");
                drawingXml.add(d.toString());
            } else {
                drawingXml.add(null);
            }
        }

        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", contentTypes(chartNo));
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"" + NS_REL + "/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>");
            put(z, "docProps/core.xml", core());
            put(z, "docProps/app.xml", app());
            put(z, "xl/workbook.xml", workbook());
            put(z, "xl/_rels/workbook.xml.rels", workbookRels());
            put(z, "xl/styles.xml", styles());
            put(z, "xl/sharedStrings.xml", sharedStrings());
            int drawingNo = 0;
            int globalChart = 0;
            for (int i = 0; i < sheets.size(); i++) {
                put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml.get(i));
                if (drawingXml.get(i) != null) {
                    drawingNo++;
                    put(z, "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels", rels("drawing", "../drawings/drawing" + drawingNo + ".xml", 1));
                    put(z, "xl/drawings/drawing" + drawingNo + ".xml", drawingXml.get(i));
                    StringBuilder dr = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
                    for (int k = 0; k < sheets.get(i).charts.size(); k++) {
                        globalChart++;
                        dr.append("<Relationship Id=\"rId").append(k + 1).append("\" Type=\"").append(NS_REL)
                                .append("/chart\" Target=\"../charts/chart").append(globalChart).append(".xml\"/>");
                    }
                    dr.append("</Relationships>");
                    put(z, "xl/drawings/_rels/drawing" + drawingNo + ".xml.rels", dr.toString());
                }
            }
            for (int k = 0; k < chartXml.size(); k++) put(z, "xl/charts/chart" + (k + 1) + ".xml", chartXml.get(k));
        }
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String rels(String type, String target, int id) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId"
                + id + "\" Type=\"" + NS_REL + "/" + type + "\" Target=\"" + target + "\"/></Relationships>";
    }

    private String contentTypes(int charts) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        int drawingNo = 0;
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            if (!sheets.get(i).charts.isEmpty()) {
                drawingNo++;
                sb.append("<Override PartName=\"/xl/drawings/drawing").append(drawingNo)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
            }
        }
        for (int k = 1; k <= charts; k++) {
            sb.append("<Override PartName=\"/xl/charts/chart").append(k)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String core() {
        String now = Instant.now().toString().replaceAll("\\.\\d+Z$", "Z");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>Отчёт по собеседованию</dc:title><dc:creator>Интервью Java Backend</dc:creator>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified></cp:coreProperties>";
    }

    private String app() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
                + "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>Microsoft Excel</Application>"
                + "<DocSecurity>0</DocSecurity><ScaleCrop>false</ScaleCrop><HeadingPairs><vt:vector size=\"2\" baseType=\"variant\">"
                + "<vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>").append(sheets.size())
                .append("</vt:i4></vt:variant></vt:vector></HeadingPairs><TitlesOfParts><vt:vector size=\"").append(sheets.size())
                .append("\" baseType=\"lpstr\">");
        for (XlsxSheet s : sheets) sb.append("<vt:lpstr>").append(esc(s.name)).append("</vt:lpstr>");
        return sb.append("</vt:vector></TitlesOfParts><AppVersion>12.0000</AppVersion></Properties>").toString();
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"")
                .append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">"
                        + "<fileVersion appName=\"xl\" lastEdited=\"4\" lowestEdited=\"4\" rupBuild=\"4505\"/>"
                        + "<workbookPr defaultThemeVersion=\"124226\"/><bookViews><workbookView xWindow=\"240\" yWindow=\"15\" "
                        + "windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(esc(sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets>");
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            XlsxSheet s = sheets.get(i);
            if (s.repeatHeader) {
                names.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(i).append("\">'")
                        .append(esc(s.name)).append("'!$1:$1</definedName>");
            }
            if (s.filter != null) {
                names.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">")
                        .append(esc(s.absRef(s.filter[0], s.filter[1], s.filter[2], s.filter[3]))).append("</definedName>");
            }
        }
        if (names.length() > 0) sb.append("<definedNames>").append(names).append("</definedNames>");
        sb.append("<calcPr calcId=\"124519\" fullCalcOnLoad=\"1\"/></workbook>");
        return sb.toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(NS_REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
        }
        int n = sheets.size();
        sb.append("<Relationship Id=\"rId").append(n + 1).append("\" Type=\"").append(NS_REL).append("/styles\" Target=\"styles.xml\"/>");
        sb.append("<Relationship Id=\"rId").append(n + 2).append("\" Type=\"").append(NS_REL)
                .append("/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>");
        return sb.toString();
    }

    private String sharedStrings() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<sst xmlns=\"")
                .append(NS_MAIN).append("\" count=\"").append(sstRefs).append("\" uniqueCount=\"").append(sst.size()).append("\">");
        for (String s : sst.keySet()) {
            sb.append("<si><t xml:space=\"preserve\">").append(esc(s)).append("</t></si>");
        }
        return sb.append("</sst>").toString();
    }

    private String styles() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<styleSheet xmlns=\"")
                .append(NS_MAIN).append("\">");
        if (!numFmts.isEmpty()) {
            sb.append("<numFmts count=\"").append(numFmts.size()).append("\">");
            numFmts.forEach((code, id) -> sb.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"").append(esc(code)).append("\"/>"));
            sb.append("</numFmts>");
        }
        sb.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (String key : fonts.keySet()) {
            String[] p = key.split("\\|");
            sb.append("<font>");
            if (Boolean.parseBoolean(p[0])) sb.append("<b/>");
            if (Boolean.parseBoolean(p[1])) sb.append("<i/>");
            sb.append("<sz val=\"").append(num(Double.parseDouble(p[2]))).append("\"/><color rgb=\"FF").append(p[3])
                    .append("\"/><name val=\"Calibri\"/><family val=\"2\"/></font>");
        }
        sb.append("</fonts><fills count=\"").append(fills.size()).append("\">");
        for (String key : fills.keySet()) {
            if (key.equals("none") || key.equals("gray125")) {
                sb.append("<fill><patternFill patternType=\"").append(key).append("\"/></fill>");
            } else {
                sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF").append(key)
                        .append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
            }
        }
        sb.append("</fills><borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border>"
                + "<left style=\"thin\"><color rgb=\"FFBFBFBF\"/></left><right style=\"thin\"><color rgb=\"FFBFBFBF\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFBFBFBF\"/></top><bottom style=\"thin\"><color rgb=\"FFBFBFBF\"/></bottom>"
                + "<diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        sb.append("<cellXfs count=\"").append(xfs.size() + 1).append("\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>");
        for (XlsxStyle s : xfs.keySet()) {
            int font = fonts.get(s.bold() + "|" + s.italic() + "|" + s.size() + "|" + s.color());
            int fill = s.fill() == null ? 0 : fills.get(s.fill());
            int fmt = numFmtId(s);
            sb.append("<xf numFmtId=\"").append(fmt).append("\" fontId=\"").append(font).append("\" fillId=\"").append(fill)
                    .append("\" borderId=\"").append(s.border() ? 1 : 0).append("\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\""
                            + " applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment");
            if (s.halign() != null) sb.append(" horizontal=\"").append(s.halign()).append("\"");
            if (s.valign() != null) sb.append(" vertical=\"").append(s.valign()).append("\"");
            if (s.wrap()) sb.append(" wrapText=\"1\"");
            sb.append("/></xf>");
        }
        sb.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        sb.append("<dxfs count=\"").append(dxfs.size()).append("\">");
        for (String d : dxfs) sb.append(d);
        sb.append("</dxfs><tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium9\" defaultPivotStyle=\"PivotStyleLight16\"/></styleSheet>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ диаграммы

    private static String chartXml(XlsxChart c, XlsxSheet sh) {
        String catRef = sh.absRef(c.firstRow(), c.catCol(), c.lastRow(), c.catCol());
        String valRef = sh.absRef(c.firstRow(), c.valCol(), c.lastRow(), c.valCol());
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + NS_REL + "\">"
                + "<c:lang val=\"ru-RU\"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"1200\" b=\"1\"/></a:pPr>"
                + "<a:r><a:rPr lang=\"ru-RU\" sz=\"1200\" b=\"1\"/><a:t>").append(esc(c.title()))
                .append("</a:t></a:r></a:p></c:rich></c:tx><c:layout/></c:title><c:plotArea><c:layout/><c:barChart><c:barDir val=\"")
                .append(c.horizontal() ? "bar" : "col").append("\"/><c:grouping val=\"clustered\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>"
                        + "<c:spPr><a:solidFill><a:srgbClr val=\"").append(c.color()).append("\"/></a:solidFill></c:spPr>"
                        + "<c:dLbls><c:numFmt formatCode=\"").append(esc(c.labelFormat())).append("\" sourceLinked=\"0\"/><c:showVal val=\"1\"/></c:dLbls>"
                        + "<c:cat><c:strRef><c:f>").append(esc(catRef)).append("</c:f><c:strCache><c:ptCount val=\"").append(c.cats().size()).append("\"/>");
        for (int i = 0; i < c.cats().size(); i++) {
            sb.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(esc(c.cats().get(i))).append("</c:v></c:pt>");
        }
        sb.append("</c:strCache></c:strRef></c:cat><c:val><c:numRef><c:f>").append(esc(valRef))
                .append("</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"").append(c.vals().size()).append("\"/>");
        for (int i = 0; i < c.vals().size(); i++) {
            sb.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(num(c.vals().get(i))).append("</c:v></c:pt>");
        }
        sb.append("</c:numCache></c:numRef></c:val></c:ser><c:gapWidth val=\"60\"/><c:axId val=\"50010001\"/><c:axId val=\"50010002\"/></c:barChart>");
        // ось категорий
        sb.append("<c:catAx><c:axId val=\"50010001\"/><c:scaling><c:orientation val=\"").append(c.horizontal() ? "maxMin" : "minMax")
                .append("\"/></c:scaling><c:axPos val=\"").append(c.horizontal() ? "l" : "b")
                .append("\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"50010002\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/>"
                        + "<c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/></c:catAx>");
        // ось значений
        sb.append("<c:valAx><c:axId val=\"50010002\"/><c:scaling><c:orientation val=\"minMax\"/><c:max val=\"").append(num(c.axisMax()))
                .append("\"/><c:min val=\"0\"/></c:scaling><c:axPos val=\"").append(c.horizontal() ? "b" : "l")
                .append("\"/><c:majorGridlines><c:spPr><a:ln><a:solidFill><a:srgbClr val=\"D9D9D9\"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>"
                        + "<c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"50010001\"/><c:crosses val=\"")
                .append(c.horizontal() ? "max" : "autoZero").append("\"/><c:crossBetween val=\"between\"/></c:valAx></c:plotArea><c:plotVisOnly val=\"1\"/></c:chart>"
                        + "<c:printSettings><c:headerFooter/><c:pageMargins b=\"0.75\" l=\"0.7\" r=\"0.7\" t=\"0.75\" header=\"0.3\" footer=\"0.3\"/>"
                        + "<c:pageSetup/></c:printSettings></c:chartSpace>");
        return sb.toString();
    }
}
