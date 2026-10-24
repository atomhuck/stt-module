package ru.example.interview.live;

import ru.example.interview.engine.InterviewDetails;

/**
 * Сохраняет итоговый отчёт (в приложении — Excel-файл).
 *
 * @version 1.0.0
 */
public interface ReportExporter {

    /**
     * Содержит результат сохранения отчёта.
     *
     * @param fileName    имя файла
     * @param path        полный путь на диске
     * @param downloadUrl ссылка для скачивания из браузера (null — скачивание выключено)
     * @version 1.0.0
     */
    record Result(String fileName, String path, String downloadUrl) {}

    /**
     * Сохраняет отчёт завершённого интервью.
     *
     * @param details вопросы, попытки и рассчитанные оценки
     * @return имя, путь и доступная ссылка на файл
     * @throws Exception при ошибке формирования или записи отчёта
     */
    Result export(InterviewDetails details) throws Exception;
}
