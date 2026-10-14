#!/usr/bin/env python3
"""Verify a candidate voice against an enrollment window and label other speakers.

The program consumes speech intervals from NeMo-Speech.cpp and speaker embeddings
from sherpa-onnx. Every speech interval receives an automatic speaker role.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import sherpa_onnx


MODEL_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
    "speaker-recongition-models/"
    "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
)
DEFAULT_MODEL = Path(
    os.environ.get(
        "SPEAKER_EMBEDDING_MODEL",
        "models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
    )
)
SAMPLE_RATE = 16000


@dataclass
class VoiceInterval:
    start: float
    end: float
    source_speaker_id: str
    embedding: np.ndarray | None = None
    candidate_similarity: float | None = None
    interviewer_similarity: float | None = None
    cluster_index: int | None = None
    label: str = ""
    confidence: str = "низкая"
    decision: str = ""
    overlapped: bool = False

    @property
    def duration(self) -> float:
        return max(0.0, self.end - self.start)


@dataclass
class InterviewerCluster:
    embeddings: list[np.ndarray] = field(default_factory=list)
    first_start: float = math.inf

    def prototype(self) -> np.ndarray:
        return normalize(np.mean(np.stack(self.embeddings), axis=0))


def normalize(vector: np.ndarray) -> np.ndarray:
    return vector / max(float(np.linalg.norm(vector)), 1e-12)


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        if wav.getcomptype() != "NONE":
            raise ValueError("Поддерживаются только несжатые WAV-файлы.")
        channels = wav.getnchannels()
        rate = wav.getframerate()
        width = wav.getsampwidth()
        if rate != SAMPLE_RATE:
            raise ValueError(f"Ожидается WAV 16 кГц, у файла {rate} Гц.")
        if width != 2:
            raise ValueError("Ожидается 16-битный PCM WAV.")
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
    if channels > 1:
        samples = samples.reshape(-1, channels).astype(np.float32).mean(axis=1)
    else:
        samples = samples.astype(np.float32)
    return np.clip(samples / 32768.0, -1.0, 1.0).astype(np.float32)


def write_wav(path: Path, samples: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.clip(samples * 32767.0, -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm.tobytes())


def ensure_model(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(
            f"Не найдена ONNX-модель эмбеддингов: {path}. "
            "Разместите модель на сервере и передайте путь через --model "
            "или SPEAKER_EMBEDDING_MODEL. Скрипт ничего не скачивает."
        )


def maybe_run_nemotron(args: argparse.Namespace, output_path: Path, work_dir: Path) -> Path:
    if args.diarization_json:
        path = args.diarization_json.resolve()
        if not path.is_file():
            raise FileNotFoundError(f"Не найден JSON диаризации: {path}")
        return path

    if not args.nemo_exe or not args.nemo_model:
        raise ValueError(
            "Укажите --diarization-json с готовой диаризацией или оба параметра "
            "--nemo-exe и --nemo-model."
        )

    nemo_model = args.nemo_model.expanduser().resolve()
    if not nemo_model.is_file():
        raise FileNotFoundError(
            f"Не найдена локальная GGUF-модель Nemotron: {nemo_model}. "
            "Разместите веса на сервере заранее; удалённые идентификаторы моделей запрещены."
        )

    work_dir.mkdir(parents=True, exist_ok=True)
    staged_audio = work_dir / (args.audio.stem + ".wav")
    shutil.copy2(args.audio.resolve(), staged_audio)
    path = work_dir / (args.audio.stem + ".nemotron.json")
    command = [
        str(args.nemo_exe),
        "diarize",
        str(staged_audio),
        "--model",
        str(nemo_model),
        "--device",
        "cpu",
        "--format",
        "json",
        "--output",
        str(path),
        "--force",
    ]
    print("Запускаю Nemotron на CPU для временных границ речи...")
    subprocess.run(command, check=True)
    return path


def create_extractor(model_path: Path, num_threads: int) -> sherpa_onnx.SpeakerEmbeddingExtractor:
    config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(model_path),
        num_threads=num_threads,
        provider="cpu",
    )
    if not config.validate():
        raise ValueError(f"Некорректная конфигурация модели: {config}")
    return sherpa_onnx.SpeakerEmbeddingExtractor(config)


def embedding_for(
    extractor: sherpa_onnx.SpeakerEmbeddingExtractor,
    audio: np.ndarray,
    start: float,
    end: float,
    trim: float = 0.0,
) -> np.ndarray | None:
    if end - start > 2 * trim + 0.2:
        start += trim
        end -= trim
    first = max(0, int(round(start * SAMPLE_RATE)))
    last = min(len(audio), int(round(end * SAMPLE_RATE)))
    if last - first < int(0.65 * SAMPLE_RATE):
        return None
    signal = audio[first:last]
    if float(np.sqrt(np.mean(signal * signal))) < 0.003:
        return None

    stream = extractor.create_stream()
    stream.accept_waveform(sample_rate=SAMPLE_RATE, waveform=signal)
    stream.input_finished()
    result = np.asarray(extractor.compute(stream), dtype=np.float32).reshape(-1)
    if result.size == 0 or not np.all(np.isfinite(result)):
        return None
    return normalize(result)


def split_around_reference(start: float, end: float, ref_start: float, ref_end: float) -> list[tuple[float, float]]:
    if end <= ref_start or start >= ref_end:
        return [(start, end)]
    pieces: list[tuple[float, float]] = []
    if start < ref_start:
        pieces.append((start, ref_start))
    if end > ref_end:
        pieces.append((ref_end, end))
    return pieces


def mark_overlaps(intervals: list[VoiceInterval], min_overlap: float = 0.25) -> None:
    for i, current in enumerate(intervals):
        for j, other in enumerate(intervals):
            if i == j:
                continue
            overlap = min(current.end, other.end) - max(current.start, other.start)
            if overlap > min_overlap:
                current.overlapped = True
                break


def cluster_similarity(embedding: np.ndarray, cluster: InterviewerCluster) -> float:
    return float(np.dot(embedding, cluster.prototype()))


def make_output(args: argparse.Namespace) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    audio_path = args.audio.resolve()
    if not audio_path.is_file():
        raise FileNotFoundError(f"Не найден WAV: {audio_path}")
    audio = read_wav(audio_path)
    duration = len(audio) / SAMPLE_RATE
    ref_start = float(args.enroll_start)
    ref_end = ref_start + float(args.enroll_duration)
    if ref_start < 0 or ref_end > duration:
        raise ValueError(
            f"Окно эталона {ref_start:.2f}–{ref_end:.2f} с выходит за длительность {duration:.2f} с."
        )

    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if args.diarization_json:
        segments_path = maybe_run_nemotron(args, output_path, output_path.parent)
        diarization = json.loads(segments_path.read_text(encoding="utf-8"))
        diarization_source = segments_path.name
    else:
        temp_base = Path(os.environ.get("STT_WORK_DIR", tempfile.gettempdir()))
        temp_base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="stt-verify-", dir=temp_base) as work_dir:
            segments_path = maybe_run_nemotron(args, output_path, Path(work_dir))
            diarization = json.loads(segments_path.read_text(encoding="utf-8"))
        diarization_source = "generated by NeMo-Speech.cpp"
    raw_segments = diarization.get("segments", [])
    if not raw_segments:
        raise ValueError("В JSON диаризации нет речевых сегментов.")

    seed_samples = audio[int(ref_start * SAMPLE_RATE):int(ref_end * SAMPLE_RATE)]
    if float(np.sqrt(np.mean(seed_samples * seed_samples))) < 0.003:
        raise ValueError("В выбранном окне эталона почти нет звука; выберите другой интервал.")

    ref_ids = sorted(
        {
            str(seg.get("speaker", "?"))
            for seg in raw_segments
            if min(float(seg["end"]), ref_end) - max(float(seg["start"]), ref_start) >= 1.0
        }
    )
    warnings: list[str] = []
    if len(ref_ids) > 1:
        warnings.append(
            "Окно эталона пересекает несколько временных speaker ID по Nemotron: "
            + ", ".join(ref_ids)
            + ". Качество эталона может снизиться."
        )

    model_path = args.model.resolve()
    ensure_model(model_path)
    extractor = create_extractor(model_path, args.num_threads)
    candidate_embedding = embedding_for(extractor, audio, ref_start, ref_end)
    if candidate_embedding is None:
        raise ValueError("Не удалось извлечь голосовой эталон из выбранного интервала.")

    intervals: list[VoiceInterval] = []
    for segment in raw_segments:
        original_start = float(segment["start"])
        original_end = min(float(segment["end"]), duration)
        source_id = str(segment.get("speaker", "?"))
        for start, end in split_around_reference(original_start, original_end, ref_start, ref_end):
            if end - start < 0.45:
                continue
            item = VoiceInterval(start=start, end=end, source_speaker_id=source_id)
            item.embedding = embedding_for(extractor, audio, start, end, trim=0.08)
            if item.embedding is not None:
                item.candidate_similarity = float(np.dot(candidate_embedding, item.embedding))
            intervals.append(item)

    mark_overlaps(intervals)
    threshold = float(args.threshold)

    # Build interviewer prototypes from longer, clear non-candidate utterances.
    clusters: list[InterviewerCluster] = []
    bootstrap = [
        item for item in intervals
        if item.embedding is not None
        and item.candidate_similarity is not None
        and item.candidate_similarity < threshold
        and item.duration >= args.min_cluster_seconds
        and not item.overlapped
    ]
    for item in sorted(bootstrap, key=lambda x: (-x.duration, x.start)):
        similarities = [cluster_similarity(item.embedding, group) for group in clusters]
        if similarities and max(similarities) >= args.cluster_threshold:
            index = int(np.argmax(similarities))
            clusters[index].embeddings.append(item.embedding)
            clusters[index].first_start = min(clusters[index].first_start, item.start)
            item.cluster_index = index
            item.interviewer_similarity = similarities[index]
        else:
            clusters.append(InterviewerCluster([item.embedding], item.start))
            item.cluster_index = len(clusters) - 1
            item.interviewer_similarity = 1.0

    # Short interviews may have no long interviewer utterance to bootstrap from.
    if not clusters:
        fallback_seeds = [
            item for item in intervals
            if item.embedding is not None
            and item.candidate_similarity is not None
            and item.candidate_similarity < threshold
        ]
        if fallback_seeds:
            seed = max(fallback_seeds, key=lambda item: (item.duration, -item.start))
            clusters.append(InterviewerCluster([seed.embedding], seed.start))
            seed.cluster_index = 0

    # Compare all usable intervals with the interviewer prototypes.
    for item in intervals:
        if item.embedding is None or not clusters:
            continue
        similarities = [cluster_similarity(item.embedding, group) for group in clusters]
        index = int(np.argmax(similarities))
        item.interviewer_similarity = similarities[index]
        if similarities[index] >= args.cluster_threshold:
            item.cluster_index = index

    # Interviewer numbers follow first appearance in the recording.
    cluster_order = sorted(range(len(clusters)), key=lambda index: clusters[index].first_start)
    display_number = {cluster_index: number + 1 for number, cluster_index in enumerate(cluster_order)}
    for item in intervals:
        score = item.candidate_similarity
        interviewer_score = item.interviewer_similarity
        if score is not None and score >= threshold and (
            interviewer_score is None or score >= interviewer_score
        ):
            item.label = "Кандидат"
            item.confidence = "высокая" if item.duration >= 2.5 and not item.overlapped else "средняя"
            item.decision = "Сходство с эталоном выше порога"
        elif item.cluster_index is not None and interviewer_score is not None and (
            interviewer_score >= args.cluster_threshold and (score is None or interviewer_score > score)
        ):
            item.label = f"Интервьюер {display_number[item.cluster_index]}"
            item.confidence = "высокая" if item.duration >= 2.5 and not item.overlapped else "средняя"
            item.decision = "Совпадение с группой другого голоса"

    # Infer a stable role for each Nemotron speaker ID from confidently labeled
    # utterances, then use it only where local voice comparison is inconclusive.
    source_votes: dict[str, dict[str, float]] = {}
    for item in intervals:
        if item.label:
            votes = source_votes.setdefault(item.source_speaker_id, {})
            votes[item.label] = votes.get(item.label, 0.0) + item.duration
    source_roles = {
        source_id: max(votes, key=votes.get)
        for source_id, votes in source_votes.items()
    }

    for item in intervals:
        if item.label:
            continue
        source_role = source_roles.get(item.source_speaker_id)
        if source_role:
            item.label = source_role
            item.decision = "Автоматически назначено по устойчивой роли speaker ID"
        elif item.candidate_similarity is not None and (
            item.interviewer_similarity is None
            or item.candidate_similarity >= item.interviewer_similarity
        ):
            item.label = "Кандидат"
            item.decision = "Автоматически выбрано по максимальному сходству голосов"
        elif item.interviewer_similarity is not None and clusters:
            index = int(np.argmax([cluster_similarity(item.embedding, group) for group in clusters]))
            item.label = f"Интервьюер {display_number[index]}"
            item.decision = "Автоматически выбрано по максимальному сходству голосов"
        elif source_roles:
            nearest = min(
                (other for other in intervals if other.label and other is not item),
                key=lambda other: min(abs(item.start - other.end), abs(item.end - other.start)),
            )
            item.label = nearest.label
            item.decision = "Автоматически назначено по ближайшей реплике"
        else:
            item.label = "Кандидат"
            item.decision = "Голосовой эталон — единственный доступный ориентир"
        item.confidence = "низкая"

    events: list[dict[str, Any]] = []
    events.append(
        {
            "start": round(ref_start, 3),
            "end": round(ref_end, 3),
            "duration": round(ref_end - ref_start, 3),
            "label": "Кандидат (эталон)",
            "candidate_similarity": None,
            "interviewer_similarity": None,
            "source_speaker_id": ",".join(ref_ids),
            "confidence": "эталон",
            "decision": "Голос записан из заданного окна",
        }
    )
    for item in intervals:
        events.append(
            {
                "start": round(item.start, 3),
                "end": round(item.end, 3),
                "duration": round(item.duration, 3),
                "label": item.label,
                "candidate_similarity": round(item.candidate_similarity, 4)
                if item.candidate_similarity is not None else None,
                "interviewer_similarity": round(item.interviewer_similarity, 4)
                if item.interviewer_similarity is not None else None,
                "source_speaker_id": item.source_speaker_id,
                "confidence": item.confidence,
                "decision": item.decision,
            }
        )
    events.sort(key=lambda row: (row["start"], row["end"]))

    summary: dict[str, int] = {}
    for event in events:
        summary[event["label"]] = summary.get(event["label"], 0) + 1
    result = {
        "audio": audio_path.name,
        "diarization_json": diarization_source,
        "embedding_model": model_path.name,
        "embedding_model_source": MODEL_URL,
        "device": "cpu",
        "sample_rate_hz": SAMPLE_RATE,
        "enrollment": {"start": ref_start, "end": ref_end, "duration": ref_end - ref_start},
        "thresholds": {
            "candidate_similarity": threshold,
            "interviewer_cluster_similarity": args.cluster_threshold,
        },
        "summary": summary,
        "warnings": warnings,
        "segments": events,
    }
    return result, events


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(
        description="Сверяет реплики с эталоном кандидата и назначает роли говорящим."
    )
    parser.add_argument("--audio", type=Path, required=True, help="16 кГц mono PCM WAV")
    parser.add_argument("--diarization-json", type=Path, help="готовый JSON от nemo-speech diarize")
    parser.add_argument("--nemo-exe", type=Path, help="путь к nemo-speech.exe, если JSON нужно создать")
    parser.add_argument("--nemo-model", type=Path, help="путь к GGUF модели Nemotron, если JSON нужно создать")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL, help="ONNX модель speaker embeddings")
    parser.add_argument("--enroll-start", type=float, default=20.0, help="начало эталона, сек")
    parser.add_argument("--enroll-duration", type=float, default=10.0, help="длительность эталона, сек")
    parser.add_argument("--threshold", type=float, default=0.60, help="порог совпадения с кандидатом")
    parser.add_argument("--cluster-threshold", type=float, default=0.60, help="порог группировки интервьюеров")
    parser.add_argument("--min-cluster-seconds", type=float, default=3.0, help="минимум для создания новой группы")
    parser.add_argument("--num-threads", type=int, default=4, help="потоки CPU")
    parser.add_argument("--output", type=Path, help="JSON-результат; по умолчанию рядом с аудио")
    parser.add_argument("--csv", type=Path, help="CSV-результат; по умолчанию рядом с JSON")
    parser.add_argument("--reference-wav", type=Path, help="необязательно: сохранить эталонный отрезок для отладки")
    args = parser.parse_args()

    if args.output is None:
        args.output = args.audio.with_name(args.audio.stem + ".verified.json")
    if args.csv is None:
        args.csv = args.output.with_suffix(".csv")
    try:
        result, events = make_output(args)
        if args.reference_wav:
            audio = read_wav(args.audio.resolve())
            ref_start = int(args.enroll_start * SAMPLE_RATE)
            ref_end = int((args.enroll_start + args.enroll_duration) * SAMPLE_RATE)
            write_wav(args.reference_wav.resolve(), audio[ref_start:ref_end])
        args.output.resolve().write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        args.csv.resolve().parent.mkdir(parents=True, exist_ok=True)
        fields = [
            "start", "end", "duration", "label", "candidate_similarity",
            "interviewer_similarity", "source_speaker_id", "confidence", "decision",
        ]
        with args.csv.resolve().open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(events)
        if args.reference_wav:
            print(f"Эталон: {args.reference_wav.resolve()}")
        print(f"JSON:   {args.output.resolve()}")
        print(f"CSV:    {args.csv.resolve()}")
        print("Итог:")
        for label, count in result["summary"].items():
            print(f"  {label}: {count} сегм.")
        for warning in result["warnings"]:
            print(f"Предупреждение: {warning}")
        print("Всем речевым сегментам автоматически назначены роли.")
        return 0
    except Exception as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
