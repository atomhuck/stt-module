package ru.example.interview.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ReportFilesTest {

    @Test
    void file_name_contains_date_and_sanitized_candidate() {
        String n = ReportFiles.fileName("Иван Петров", Instant.parse("2026-10-14T20:54:00Z"));
        assertTrue(n.startsWith("Interview_2026-10-1"), n);
        assertTrue(n.endsWith("_Иван_Петров.xlsx") || n.endsWith("_Ivan_Petrov.xlsx"), n);   // зависит от кодировки файловой системы
        assertTrue(ReportFiles.isSafeName(n));
    }

    @Test
    void dangerous_or_empty_names_are_neutralised() {
        String evil = ReportFiles.fileName("../../etc/passwd", Instant.now());
        assertFalse(evil.contains("/") || evil.contains(".."), evil);
        assertTrue(ReportFiles.isSafeName(evil));
        String blank = ReportFiles.fileName("   ", Instant.now());
        assertTrue(blank.endsWith("_кандидат.xlsx") || blank.endsWith("_kandidat.xlsx"), blank);
        assertTrue(ReportFiles.fileName("x".repeat(200), Instant.now()).length() < 90);
    }

    @Test
    void download_name_check_rejects_path_traversal_and_foreign_files() {
        assertFalse(ReportFiles.isSafeName("../secret.xlsx"));
        assertFalse(ReportFiles.isSafeName("Interview_a/b.xlsx"));
        assertFalse(ReportFiles.isSafeName("Interview_x.exe"));
        assertFalse(ReportFiles.isSafeName("application.yml"));
        assertFalse(ReportFiles.isSafeName(null));
        assertTrue(ReportFiles.isSafeName("Interview_2026-10-14_23-54_Иван.xlsx"));
    }

    @Test
    void transliteration_covers_russian_names() {
        assertEquals("Ivan_Petrov", ReportFiles.translit("Иван_Петров"));
        assertEquals("Shchukin_Yuliya", ReportFiles.translit("Щукин_Юлия"));
        assertEquals("kandidat", ReportFiles.translit("кандидат"));
        assertTrue(ReportFiles.isSafeName("Interview_2026-10-14_23-54_" + ReportFiles.translit("Пётр_Ёлкин") + ".xlsx"));
    }

    @Test
    void existing_report_is_never_overwritten() throws Exception {
        Path dir = Files.createTempDirectory("reports");
        Path first = ReportFiles.uniquePath(dir, "Interview_a.xlsx");
        Files.writeString(first, "1");
        Path second = ReportFiles.uniquePath(dir, "Interview_a.xlsx");
        assertNotEquals(first, second);
        assertTrue(second.getFileName().toString().endsWith("_2.xlsx"));
    }
}
