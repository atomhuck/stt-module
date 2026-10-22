package ru.example.interview.live;

import ru.example.interview.engine.InterviewDetails;

 * @version 1.0.0
/** Сохраняет итоговый отчёт (в приложении — Excel-файл). */
public interface ReportExporter {

    /**
     * @param fileName    имя файла
     * @param path        полный путь на диске
     * @param downloadUrl ссылка для скачивания из браузера (null — скачивание выключено)
     */
    record Result(String fileName, String path, String downloadUrl) {}

    Result export(InterviewDetails details) throws Exception;
}
