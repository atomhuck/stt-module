package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OnlineRoles {
    private static final class Interviewer {
        final int number;
        final List<float[]> embeddings = new ArrayList<>();
        Interviewer(int number, float[] initial) { this.number = number; embeddings.add(initial); }
        double similarity(float[] embedding) { return Support.dot(Support.meanPrototype(embeddings), embedding); }
    }

    private final float[] candidate;
    private final SpeakerEmbeddingExtractor extractor;
    private final double threshold;
    private final List<Interviewer> interviewers = new ArrayList<>();
    private final Map<String, Map<String, Double>> sourceVotes = new LinkedHashMap<>();
    private String lastLabel;

    OnlineRoles(float[] candidate, SpeakerEmbeddingExtractor extractor, double threshold) {
        this.candidate = candidate;
        this.extractor = extractor;
        this.threshold = threshold;
    }

    private String stableRole(String source) {
        Map<String, Double> votes = sourceVotes.get(source);
        if (votes == null) return null;
        String best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (var entry : votes.entrySet()) {
            if (entry.getValue() > score) { score = entry.getValue(); best = entry.getKey(); }
        }
        return best;
    }

    private void remember(String source, String label, double duration, boolean reliable) {
        sourceVotes.computeIfAbsent(source, ignored -> new LinkedHashMap<>())
                .merge(label, duration * (reliable ? 1.0 : .2), Double::sum);
        lastLabel = label;
    }

    Map<String, Object> preview(float[] audio, double start, double end, String source) {
        String label = stableRole(source);
        String basis;
        if (label != null) basis = "speaker ID в текущей потоковой сессии";
        else {
            float[] embedding = Support.embedding(extractor, audio, Math.max(start, end - 2.0), end, 0);
            if (embedding == null) {
                label = lastLabel;
                basis = label == null ? "ожидание достаточного фрагмента" : "предыдущая реплика";
            } else {
                double candidateScore = Support.dot(candidate, embedding);
                int best = -1;
                double bestScore = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < interviewers.size(); i++) {
                    double score = interviewers.get(i).similarity(embedding);
                    if (score > bestScore) { best = i; bestScore = score; }
                }
                if (candidateScore >= threshold || (best >= 0 && candidateScore >= bestScore)) label = "Кандидат";
                else if (best >= 0) label = "Интервьюер " + interviewers.get(best).number;
                else label = "Интервьюер 1";
                basis = "предварительное сравнение голосов";
            }
        }
        return Support.map("start", Support.round(start, 3), "observed_end", Support.round(end, 3),
                "source_speaker_id", source, "label", label, "status", "предварительно", "basis", basis);
    }

    Map<String, Object> classify(float[] audio, double start, double end, String source) {
        float[] embedding = Support.embedding(extractor, audio, start, end, .08);
        Double candidateScore = embedding == null ? null : Support.dot(candidate, embedding);
        int best = -1;
        Double bestScore = null;
        if (embedding != null) for (int i = 0; i < interviewers.size(); i++) {
            double score = interviewers.get(i).similarity(embedding);
            if (bestScore == null || score > bestScore) { best = i; bestScore = score; }
        }
        String stable = stableRole(source);
        double duration = end - start;
        String label, decision, confidence;
        boolean confident = duration >= 2.5;

        if (candidateScore != null && candidateScore >= threshold && (bestScore == null || candidateScore >= bestScore)) {
            label = "Кандидат";
            decision = "Сходство с эталоном выше порога";
            confidence = confident ? "высокая" : "средняя";
            remember(source, label, duration, true);
        } else if (bestScore != null && bestScore >= threshold && (candidateScore == null || bestScore > candidateScore)) {
            Interviewer group = interviewers.get(best);
            label = "Интервьюер " + group.number;
            decision = "Совпадение с группой интервьюера";
            confidence = confident ? "высокая" : "средняя";
            if (duration >= 1.5) group.embeddings.add(embedding);
            remember(source, label, duration, true);
        } else if (stable != null) {
            label = stable;
            decision = "Устойчивая роль speaker ID";
            confidence = "низкая";
            remember(source, label, duration, false);
        } else if (embedding != null && candidateScore != null && candidateScore < threshold
                && (interviewers.isEmpty() || (bestScore != null && bestScore < threshold && duration >= 3.0))) {
            Interviewer group = new Interviewer(interviewers.size() + 1, embedding);
            interviewers.add(group);
            label = "Интервьюер " + group.number;
            decision = "Создана группа другого голоса";
            confidence = duration < 2.5 ? "низкая" : "средняя";
            remember(source, label, duration, duration >= 2.5);
        } else if (candidateScore != null && (bestScore == null || candidateScore >= bestScore)) {
            label = "Кандидат";
            decision = "Наибольшее сходство с эталоном";
            confidence = "низкая";
            remember(source, label, duration, false);
        } else if (best >= 0) {
            label = "Интервьюер " + interviewers.get(best).number;
            decision = "Ближайшая группа другого голоса";
            confidence = "низкая";
            remember(source, label, duration, false);
        } else {
            label = lastLabel == null ? "Кандидат" : lastLabel;
            decision = "Контекст предыдущей реплики без надёжного эмбеддинга";
            confidence = "низкая";
            remember(source, label, duration, false);
        }
        return Support.map("start", Support.round(start, 3), "end", Support.round(end, 3),
                "duration", Support.round(duration, 3), "label", label,
                "candidate_similarity", candidateScore == null ? null : Support.round(candidateScore, 4),
                "interviewer_similarity", bestScore == null ? null : Support.round(bestScore, 4),
                "source_speaker_id", source, "confidence", confidence, "decision", decision);
    }
}
