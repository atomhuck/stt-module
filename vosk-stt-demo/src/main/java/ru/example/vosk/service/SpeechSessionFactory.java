package ru.example.vosk.service;

import org.springframework.stereotype.Component;
import ru.example.vosk.config.VoskModelHolder;
import ru.example.vosk.config.VoskProperties;

import java.io.IOException;

/**
 * Создаёт отдельный распознаватель Vosk для каждого соединения.
 *
 * @version 1.0.0
 */
@Component
public class SpeechSessionFactory {

    private final VoskModelHolder modelHolder;
    private final VoskProperties props;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param modelHolder параметр операции
     * @param props настройки приложения
     */
    public SpeechSessionFactory(VoskModelHolder modelHolder, VoskProperties props) {
        this.modelHolder = modelHolder;
        this.props = props;
    }

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @return новая речевая сессия
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    public SpeechSession create() throws IOException {
        return new SpeechSession(modelHolder.getModel(), props.sampleRate());
    }
}
