package ru.example.interview.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Отдаёт созданный XLSX-отчёт по безопасному имени файла.
 *
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExcelReportService reports;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param reports сервис файлов отчётов
     */
    public ReportController(ExcelReportService reports) {
        this.reports = reports;
    }

    /**
     * Отдаёт XLSX только для допустимого существующего имени при разрешённом скачивании.
     *
     * @param name имя файла или сущности
     * @return результат операции
     */
    @GetMapping("/{name:.+}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        Path file = reports.find(name);
        if (file == null) return ResponseEntity.notFound().build();
        ContentDisposition cd = ContentDisposition.attachment().filename(file.getFileName().toString(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(new FileSystemResource(file));
    }
}
