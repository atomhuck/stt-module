package ru.example.interview.export;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import ru.example.interview.engine.AnswerTiming;
import ru.example.interview.engine.CompiledQuestion;
import ru.example.interview.engine.DecisionPolicy;
import ru.example.interview.engine.InterviewDetails;
import ru.example.interview.engine.InterviewSession;
import ru.example.interview.engine.Level;
import ru.example.interview.engine.TestKb;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExcelReportTest {

    /** Проводит интервью: сильные ответы на чётные вопросы, «не знаю» на нечётные; возвращает данные для отчёта. */
    private InterviewDetails interview(List<Level> levels, int answers) {
        InterviewSession s = new InterviewSession(TestKb.get(), levels, new DecisionPolicy(2, 0.08));
        InterviewSession.Prompt p = s.start();
        int i = 0;
        while (p != null && i < answers) {
            CompiledQuestion cq = TestKb.get().byId(p.questionId());
            String ans = (i % 2 == 0) ? cq.question().reference() : "ну не знаю вроде как бы";
            p = s.submit(ans, ans, new AnswerTiming(1500 + 100L * i, 12000)).next();
            if (p == null || !p.followUp()) i++;
        }
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("Порог тишины", "450 мс");
        return s.details("Иван Петров", Instant.now().minusSeconds(600), settings);
    }

    private static Map<String, byte[]> unzip(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) parts.put(e.getName(), z.readAllBytes());
        }
        return parts;
    }

    private static Document parse(byte[] xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    @Test
    void file_is_a_well_formed_package_with_six_sheets() throws Exception {
        byte[] xlsx = InterviewExcelReport.build(interview(List.of(Level.INTERN), 100));
        Map<String, byte[]> parts = unzip(xlsx);
        for (var e : parts.entrySet()) parse(e.getValue());      // каждая часть — корректный XML
        for (String must : new String[]{"[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/sharedStrings.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet6.xml", "xl/charts/chart1.xml", "xl/charts/chart2.xml"}) {
            assertTrue(parts.containsKey(must), "нет части " + must);
        }
        String wb = new String(parts.get("xl/workbook.xml"), StandardCharsets.UTF_8);
        for (String sheet : new String[]{"Сводка", "Вопросы", "Диалог", "Понятия", "Эталоны", "Методика"}) {
            assertTrue(wb.contains("name=\"" + sheet + "\""), "нет листа " + sheet);
        }
    }

    @Test
    void file_contains_questions_answers_and_what_was_not_said() throws Exception {
        InterviewDetails d = interview(List.of(Level.INTERN), 100);
        Map<String, byte[]> parts = unzip(InterviewExcelReport.build(d));
        String sst = new String(parts.get("xl/sharedStrings.xml"), StandardCharsets.UTF_8);
        assertTrue(sst.contains("Иван Петров"));
        assertTrue(sst.contains("Что такое JVM, JRE и JDK?"), "исходный вопрос");
        assertTrue(sst.contains("не знаю вроде как бы"), "ответ кандидата");
        assertTrue(sst.contains("Не прозвучало"), "колонка «не прозвучало»");
        assertTrue(sst.contains("Эталонный ответ"), "эталоны");
        // по каждому вопросу — своя строка в листе «Вопросы»
        Matcher m = Pattern.compile("<dimension ref=\"A1:P(\\d+)\"/>").matcher(new String(parts.get("xl/worksheets/sheet2.xml"), StandardCharsets.UTF_8));
        assertTrue(m.find());
        assertEquals(d.questions().size() + 1, Integer.parseInt(m.group(1)));
    }

    @Test
    void summary_formulas_carry_cached_values_equal_to_the_report() throws Exception {
        InterviewDetails d = interview(List.of(Level.INTERN, Level.JUNIOR), 100);
        String sheet = new String(unzip(InterviewExcelReport.build(d)).get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertEquals(d.report().total(), cached(sheet, "B18"), 0.0001);
        assertEquals(d.report().total(), cached(sheet, "F5"), 0.0001);
        assertEquals(d.report().completeness(), cached(sheet, "B14"), 0.0001);
        assertEquals(d.report().technical(), cached(sheet, "B15"), 0.0001);
        assertEquals(d.report().terminology(), cached(sheet, "B16"), 0.0001);
        assertEquals(d.report().speed(), cached(sheet, "B17"), 0.0001);
        assertTrue(sheet.contains("<f>ROUND(AVERAGE('Вопросы'!K2:K"), "критерии должны быть формулами над листом «Вопросы»");
    }

    private static double cached(String sheetXml, String cell) {
        Matcher m = Pattern.compile("<c r=\"" + cell + "\"[^>]*>(?:<f>.*?</f>)?<v>([^<]+)</v>").matcher(sheetXml);
        assertTrue(m.find(), "нет ячейки " + cell);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void interview_without_answers_still_produces_a_file() throws Exception {
        InterviewDetails d = interview(List.of(Level.INTERN), 0);
        assertEquals(0, d.questions().size());
        byte[] xlsx = InterviewExcelReport.build(d);
        for (var e : unzip(xlsx).entrySet()) parse(e.getValue());
        assertTrue(xlsx.length > 2000);
    }

    @Test
    void special_and_control_characters_do_not_break_xml() throws Exception {
        XlsxBook b = new XlsxBook();
        XlsxSheet sh = b.sheet("Тест & <лист>");
        sh.set(0, 0, "a & b <c> \"q\" \u0001 \u000B конец", XlsxStyle.BASE);
        sh.formula(1, 0, "IF(A1<5,\"x\",\"y\")", "x", XlsxStyle.BASE);
        Map<String, byte[]> parts = unzip(b.toBytes());
        for (var e : parts.entrySet()) parse(e.getValue());
        String text = parse(parts.get("xl/sharedStrings.xml")).getDocumentElement().getTextContent();
        assertTrue(text.contains("a & b <c> \"q\""));
        assertFalse(text.contains("\u0001"));
    }

    @Test
    void russian_plurals_and_row_height_helpers() {
        assertEquals("слово", InterviewExcelReport.plural(1, "слово", "слова", "слов"));
        assertEquals("слова", InterviewExcelReport.plural(3, "слово", "слова", "слов"));
        assertEquals("слов", InterviewExcelReport.plural(11, "слово", "слова", "слов"));
        assertEquals("слов", InterviewExcelReport.plural(25, "слово", "слова", "слов"));
        assertEquals("слово", InterviewExcelReport.plural(21, "слово", "слова", "слов"));
        double shortRow = InterviewExcelReport.rowHeight(new String[]{"коротко"}, new double[]{40});
        double longRow = InterviewExcelReport.rowHeight(new String[]{"слово ".repeat(100)}, new double[]{40});
        assertTrue(longRow > shortRow * 3);
        assertTrue(longRow <= 409);
    }

    @Test
    void column_letters_are_correct() {
        assertEquals("A", XlsxSheet.col(0));
        assertEquals("Z", XlsxSheet.col(25));
        assertEquals("AA", XlsxSheet.col(26));
        assertEquals("P2", XlsxSheet.addr(1, 15));
    }
}
