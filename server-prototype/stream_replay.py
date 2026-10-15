#!/usr/bin/env python3
"""Replay a WAV through one persistent Nemotron diarization stream on CPU.

This is a file-backed, paced streaming experiment, not a microphone service.
The Nemotron C ABI model and the CAM++ extractor are each loaded once. A single
Nemotron stream receives successive PCM buffers and retains its speaker cache.
"""

from __future__ import annotations

import argparse
import ctypes as C
import json
import math
import os
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from verify_candidate import (
    SAMPLE_RATE,
    create_extractor,
    embedding_for,
    ensure_model,
    read_wav,
    split_around_reference,
    write_wav,
)


class DiarModelConfig(C.Structure):
    _fields_ = [
        ("size", C.c_size_t),
        ("model_path", C.c_char_p),
        ("gpu", C.c_int32),
        ("preset", C.c_char_p),
        ("chunk_frames", C.c_int32),
        ("right_context_frames", C.c_int32),
        ("left_context_frames", C.c_int32),
        ("fifo_frames", C.c_int32),
        ("spkcache_frames", C.c_int32),
        ("update_period_frames", C.c_int32),
    ]


class DiarSegment(C.Structure):
    _fields_ = [
        ("start_time", C.c_double),
        ("end_time", C.c_double),
        ("speaker", C.c_int32),
    ]


class NemotronStream:
    def __init__(self, dll: Path, model: Path, preset: str):
        if not dll.is_file() or not model.is_file():
            raise FileNotFoundError(f"Не найдены DLL или GGUF: {dll}, {model}")
        # Keep the dependency search handle alive for the lifetime of the DLL.
        self._dll_dir = os.add_dll_directory(str(dll.parent)) if os.name == "nt" else None
        self.lib = C.CDLL(str(dll))
        p = C.c_void_p
        self.lib.nemo_speech_asr_last_error.restype = C.c_char_p
        self.lib.nemo_speech_diar_create.argtypes = [C.POINTER(DiarModelConfig), C.POINTER(p)]
        self.lib.nemo_speech_diar_create.restype = C.c_int32
        self.lib.nemo_speech_diar_destroy.argtypes = [p]
        self.lib.nemo_speech_diar_stream_open.argtypes = [p, C.POINTER(p)]
        self.lib.nemo_speech_diar_stream_open.restype = C.c_int32
        self.lib.nemo_speech_diar_stream_push_f32.argtypes = [p, C.POINTER(C.c_float), C.c_size_t, C.c_int32]
        self.lib.nemo_speech_diar_stream_push_f32.restype = C.c_int32
        self.lib.nemo_speech_diar_stream_finish.argtypes = [p]
        self.lib.nemo_speech_diar_stream_finish.restype = C.c_int32
        self.lib.nemo_speech_diar_stream_close.argtypes = [p]
        self.lib.nemo_speech_diar_frame_count.argtypes = [p]
        self.lib.nemo_speech_diar_frame_count.restype = C.c_int64
        self.lib.nemo_speech_diar_seconds_per_frame.argtypes = [p]
        self.lib.nemo_speech_diar_seconds_per_frame.restype = C.c_double
        self.lib.nemo_speech_diar_segments.argtypes = [p, p, C.POINTER(DiarSegment), C.c_size_t, C.POINTER(C.c_size_t)]
        self.lib.nemo_speech_diar_segments.restype = C.c_int32
        self.model = p()
        self.stream = p()
        cfg = DiarModelConfig()
        cfg.size = C.sizeof(cfg)
        cfg.model_path = os.fsencode(model)
        cfg.gpu = -1
        cfg.preset = preset.encode("ascii")
        self._check(self.lib.nemo_speech_diar_create(C.byref(cfg), C.byref(self.model)))
        try:
            self._check(self.lib.nemo_speech_diar_stream_open(self.model, C.byref(self.stream)))
            self.frame_seconds = float(self.lib.nemo_speech_diar_seconds_per_frame(self.model))
        except BaseException:
            self.close()
            raise

    def _check(self, status: int) -> None:
        if status:
            raw = self.lib.nemo_speech_asr_last_error()
            detail = raw.decode("utf-8", "replace") if raw else "неизвестная ошибка"
            raise RuntimeError(f"Nemotron C API, код {status}: {detail}")

    def push(self, samples: np.ndarray) -> None:
        samples = np.ascontiguousarray(samples, dtype=np.float32)
        self._check(self.lib.nemo_speech_diar_stream_push_f32(
            self.stream, samples.ctypes.data_as(C.POINTER(C.c_float)), samples.size, SAMPLE_RATE
        ))

    def finish(self) -> None:
        self._check(self.lib.nemo_speech_diar_stream_finish(self.stream))

    def processed_seconds(self) -> float:
        return self.lib.nemo_speech_diar_frame_count(self.stream) * self.frame_seconds

    def segments(self) -> list[DiarSegment]:
        count = C.c_size_t()
        self._check(self.lib.nemo_speech_diar_segments(self.stream, None, None, 0, C.byref(count)))
        if not count.value:
            return []
        result = (DiarSegment * count.value)()
        self._check(self.lib.nemo_speech_diar_segments(self.stream, None, result, count.value, C.byref(count)))
        return list(result[: count.value])

    def close(self) -> None:
        if self.stream:
            self.lib.nemo_speech_diar_stream_close(self.stream)
            self.stream = C.c_void_p()
        if self.model:
            self.lib.nemo_speech_diar_destroy(self.model)
            self.model = C.c_void_p()
        if self._dll_dir is not None:
            self._dll_dir.close()
            self._dll_dir = None


@dataclass
class Interviewer:
    number: int
    embeddings: list[np.ndarray] = field(default_factory=list)

    def similarity(self, embedding: np.ndarray) -> float:
        mean = np.mean(np.stack(self.embeddings), axis=0)
        mean /= max(float(np.linalg.norm(mean)), 1e-12)
        return float(np.dot(mean, embedding))


class OnlineRoles:
    def __init__(self, candidate: np.ndarray, extractor: Any, threshold: float):
        self.candidate = candidate
        self.extractor = extractor
        self.threshold = threshold
        self.interviewers: list[Interviewer] = []
        self.source_votes: dict[str, Counter[str]] = {}
        self.last_label: str | None = None

    def _remember(self, source_id: str, label: str, duration: float, reliable: bool) -> None:
        # Weak early guesses still provide continuity until a stronger voice
        # comparison arrives. Later threshold matches receive full weight.
        self.source_votes.setdefault(source_id, Counter())[label] += duration if reliable else 0.2 * duration
        self.last_label = label

    def preview(self, audio: np.ndarray, start: float, end: float, source_id: str) -> dict[str, Any]:
        """A provisional live role; this does not change learned role state."""
        stable = self.source_votes.get(source_id)
        if stable:
            label = stable.most_common(1)[0][0]
            basis = "speaker ID в текущей потоковой сессии"
        else:
            # Limit the probe to two seconds to keep long live turns cheap.
            probe_start = max(start, end - 2.0)
            embedding = embedding_for(self.extractor, audio, probe_start, end, trim=0.0)
            if embedding is None:
                label = self.last_label
                basis = "предыдущая реплика" if label else "ожидание достаточного фрагмента"
            else:
                candidate_score = float(np.dot(self.candidate, embedding))
                scores = [group.similarity(embedding) for group in self.interviewers]
                best = int(np.argmax(scores)) if scores else None
                if candidate_score >= self.threshold or (best is not None and candidate_score >= scores[best]):
                    label = "Кандидат"
                elif best is not None:
                    label = f"Интервьюер {self.interviewers[best].number}"
                else:
                    label = "Интервьюер 1"
                basis = "предварительное сравнение голосов"
        return {
            "start": round(start, 3), "observed_end": round(end, 3),
            "source_speaker_id": source_id, "label": label,
            "status": "предварительно", "basis": basis,
        }

    def classify(self, audio: np.ndarray, start: float, end: float, source_id: str) -> dict[str, Any]:
        embedding = embedding_for(self.extractor, audio, start, end, trim=0.08)
        candidate_score = float(np.dot(self.candidate, embedding)) if embedding is not None else None
        similarities = [group.similarity(embedding) for group in self.interviewers] if embedding is not None else []
        best_index = int(np.argmax(similarities)) if similarities else None
        best_score = similarities[best_index] if best_index is not None else None
        stable = self.source_votes.get(source_id)
        stable_role = stable.most_common(1)[0][0] if stable else None
        duration = end - start
        confident = duration >= 2.5

        if candidate_score is not None and candidate_score >= self.threshold and (
            best_score is None or candidate_score >= best_score
        ):
            label, decision = "Кандидат", "Сходство с эталоном выше порога"
            confidence = "высокая" if confident else "средняя"
            self._remember(source_id, label, duration, True)
        elif best_score is not None and best_score >= self.threshold and (
            candidate_score is None or best_score > candidate_score
        ):
            group = self.interviewers[best_index]
            label, decision = f"Интервьюер {group.number}", "Совпадение с группой интервьюера"
            confidence = "высокая" if confident else "средняя"
            if duration >= 1.5:
                group.embeddings.append(embedding)
            self._remember(source_id, label, duration, True)
        elif stable_role is not None:
            label, decision, confidence = stable_role, "Устойчивая роль speaker ID", "низкая"
            self._remember(source_id, label, duration, False)
        elif embedding is not None and candidate_score is not None and candidate_score < self.threshold and (
            not self.interviewers or (best_score is not None and best_score < self.threshold and duration >= 3.0)
        ):
            group = Interviewer(len(self.interviewers) + 1, [embedding])
            self.interviewers.append(group)
            label = f"Интервьюер {group.number}"
            decision, confidence = "Создана группа другого голоса", "низкая" if duration < 2.5 else "средняя"
            self._remember(source_id, label, duration, duration >= 2.5)
        elif candidate_score is not None and (best_score is None or candidate_score >= best_score):
            label, decision, confidence = "Кандидат", "Наибольшее сходство с эталоном", "низкая"
            self._remember(source_id, label, duration, False)
        elif best_index is not None:
            label = f"Интервьюер {self.interviewers[best_index].number}"
            decision, confidence = "Ближайшая группа другого голоса", "низкая"
            self._remember(source_id, label, duration, False)
        else:
            label = self.last_label or "Кандидат"
            decision, confidence = "Контекст предыдущей реплики без надёжного эмбеддинга", "низкая"
            self._remember(source_id, label, duration, False)

        return {
            "start": round(start, 3),
            "end": round(end, 3),
            "duration": round(duration, 3),
            "label": label,
            "candidate_similarity": round(candidate_score, 4) if candidate_score is not None else None,
            "interviewer_similarity": round(best_score, 4) if best_score is not None else None,
            "source_speaker_id": source_id,
            "confidence": confidence,
            "decision": decision,
        }


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def run(args: argparse.Namespace) -> dict[str, Any]:
    if args.chunk_seconds <= 0 or args.settle_seconds < 0 or args.max_seconds < 0:
        raise ValueError("Размер буфера должен быть положительным; задержка и лимит — неотрицательными.")
    audio = read_wav(args.audio.resolve())
    audio_duration = len(audio) / SAMPLE_RATE
    ref_start = args.enroll_start
    ref_end = ref_start + args.enroll_duration
    if ref_start < 0 or ref_end > audio_duration or args.enroll_duration <= 0:
        raise ValueError("Окно эталона выходит за пределы записи.")
    replay_samples = min(len(audio), round(args.max_seconds * SAMPLE_RATE)) if args.max_seconds else len(audio)
    chunk_samples = round(args.chunk_seconds * SAMPLE_RATE)
    if chunk_samples <= 0:
        raise ValueError("Размер буфера меньше одного сэмпла.")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    log_path = args.output_dir / "events.jsonl"
    segments_path = args.output_dir / "segments.json"
    summary_path = args.output_dir / "summary.json"
    ensure_model(args.speaker_model)

    startup_at = time.perf_counter()
    diarizer = NemotronStream(args.nemo_lib.resolve(), args.nemo_model.resolve(), args.preset)
    try:
        extractor = create_extractor(args.speaker_model.resolve(), args.num_threads)
        candidate = embedding_for(extractor, audio, ref_start, ref_end)
        if candidate is None:
            raise ValueError("Из окна эталона не удалось получить эмбеддинг кандидата.")
        startup_seconds = time.perf_counter() - startup_at
        roles = OnlineRoles(candidate, extractor, args.threshold)
        emitted: list[dict[str, Any]] = []
        last_end: dict[str, float] = {}
        reference_emitted = False
        processing_ms: list[float] = []
        backlog_s: list[float] = []
        output_delay_s: list[float] = []
        run_start = time.perf_counter()

        def emit(handle: Any, payload: dict[str, Any]) -> None:
            line = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
            handle.write(line + "\n")
            handle.flush()
            print(line, flush=True)

        def completed_segments(raw: list[DiarSegment], cutoff: float, now: float, handle: Any) -> list[dict[str, Any]]:
            nonlocal reference_emitted
            rows: list[dict[str, Any]] = []
            for seg in sorted(raw, key=lambda item: (item.end_time, item.start_time)):
                source_id = str(seg.speaker)
                for start, end in split_around_reference(seg.start_time, seg.end_time, ref_start, ref_end):
                    start = max(0.0, start, last_end.get(source_id, 0.0))
                    end = min(end, replay_samples / SAMPLE_RATE)
                    if end <= start + 0.01 or end > cutoff + 0.001:
                        continue
                    row = roles.classify(audio[: min(replay_samples, round(now * SAMPLE_RATE))], start, end, source_id)
                    row["emitted_wall_s"] = round(time.perf_counter() - run_start, 3)
                    row["emitted_after_audio_s"] = round(now, 3)
                    if args.pace == "realtime":
                        delay = max(0.0, row["emitted_wall_s"] - end)
                        row["output_delay_s"] = round(delay, 3)
                        output_delay_s.append(delay)
                    emitted.append(row)
                    rows.append(row)
                    last_end[source_id] = end
            if not reference_emitted and now >= ref_end:
                reference_emitted = True
                reference = {
                    "start": round(ref_start, 3), "end": round(ref_end, 3),
                    "duration": round(args.enroll_duration, 3), "label": "Кандидат (эталон)",
                    "candidate_similarity": None, "interviewer_similarity": None,
                    "source_speaker_id": None, "confidence": "эталон",
                    "decision": "Эталон заранее взят из окна 20–30 с для воспроизведения файла",
                    "emitted_wall_s": round(time.perf_counter() - run_start, 3),
                    "emitted_after_audio_s": round(now, 3),
                }
                emitted.append(reference)
                rows.append(reference)
            return rows

        def active_previews(raw: list[DiarSegment], processed: float, fed: float) -> list[dict[str, Any]]:
            return [
                roles.preview(audio[:round(fed * SAMPLE_RATE)], seg.start_time, min(seg.end_time, fed), str(seg.speaker))
                for seg in raw
                if seg.end_time >= processed - 0.16 and seg.end_time > seg.start_time
            ]

        with log_path.open("w", encoding="utf-8") as handle:
                emit(handle, {
                    "type": "session_started", "audio": str(args.audio.resolve()),
                    "audio_duration_s": round(audio_duration, 3),
                    "replay_duration_s": round(replay_samples / SAMPLE_RATE, 3),
                    "chunk_seconds": args.chunk_seconds, "pace": args.pace,
                    "preset": args.preset, "device": "cpu",
                    "enrollment": {"start": ref_start, "end": ref_end, "preloaded": True},
                    "model_load_s": round(startup_seconds, 3),
                })
                for index, start_sample in enumerate(range(0, replay_samples, chunk_samples)):
                    end_sample = min(start_sample + chunk_samples, replay_samples)
                    audio_start = start_sample / SAMPLE_RATE
                    audio_end = end_sample / SAMPLE_RATE
                    if args.pace == "realtime":
                        due = run_start + audio_end
                        remaining = due - time.perf_counter()
                        if remaining > 0:
                            time.sleep(remaining)
                    chunk = audio[start_sample:end_sample]
                    if args.save_chunks:
                        write_wav(args.output_dir / "chunks" / f"chunk_{index:04d}_{audio_start:08.3f}.wav", chunk)
                    push_start = time.perf_counter()
                    diarizer.push(chunk)
                    push_ms = (time.perf_counter() - push_start) * 1000
                    processed = diarizer.processed_seconds()
                    raw = diarizer.segments()
                    new_rows = completed_segments(raw, processed - args.settle_seconds, audio_end, handle)
                    active = active_previews(raw, processed, audio_end)
                    elapsed = time.perf_counter() - run_start
                    total_ms = (time.perf_counter() - push_start) * 1000
                    processing_ms.append(total_ms)
                    if args.pace == "realtime":
                        backlog_s.append(max(0.0, elapsed - audio_end))
                    emit(handle, {
                        "type": "chunk", "index": index,
                        "audio_start_s": round(audio_start, 3), "audio_end_s": round(audio_end, 3),
                        "wall_elapsed_s": round(elapsed, 3),
                        "push_ms": round(push_ms, 2), "total_processing_ms": round(total_ms, 2),
                        "model_processed_audio_s": round(processed, 3),
                        "backlog_s": round(backlog_s[-1], 3) if backlog_s else None,
                        "active_speakers": active,
                        "segments": new_rows,
                    })
                flush_start = time.perf_counter()
                diarizer.finish()
                final_raw = diarizer.segments()
                final_rows = completed_segments(final_raw, replay_samples / SAMPLE_RATE + 1, replay_samples / SAMPLE_RATE, handle)
                flush_ms = (time.perf_counter() - flush_start) * 1000
                emit(handle, {"type": "stream_finished", "flush_ms": round(flush_ms, 2), "segments": final_rows})
                run_wall = time.perf_counter() - run_start
                role_counts = dict(Counter(row["label"] for row in emitted))
                confidence_counts = dict(Counter(row["confidence"] for row in emitted))
                compute_seconds = (sum(processing_ms) + flush_ms) / 1000
                summary = {
                    "audio": str(args.audio.resolve()),
                    "mode": "paced_file_replay" if args.pace == "realtime" else "unpaced_file_replay",
                    "device": "cpu",
                    "nemotron_model_load_once": True,
                    "nemotron_stream_open_once": True,
                    "speaker_model_load_once": True,
                    "enrollment": {"start": ref_start, "end": ref_end, "duration": args.enroll_duration, "preloaded": True},
                    "audio_seconds": round(replay_samples / SAMPLE_RATE, 3),
                    "chunks": len(processing_ms),
                    "chunk_seconds": args.chunk_seconds,
                    "model_startup_seconds": round(startup_seconds, 3),
                    "run_wall_seconds": round(run_wall, 3),
                    "total_wall_seconds": round(startup_seconds + run_wall, 3),
                    "replay_wall_over_audio": round(run_wall / (replay_samples / SAMPLE_RATE), 3),
                    "compute_seconds_sum": round(compute_seconds, 3),
                    "compute_over_audio": round(compute_seconds / (replay_samples / SAMPLE_RATE), 3),
                    "chunk_processing_ms": {
                        "mean": round(statistics.mean(processing_ms), 2),
                        "p50": round(percentile(processing_ms, 0.5), 2),
                        "p95": round(percentile(processing_ms, 0.95), 2),
                        "max": round(max(processing_ms), 2),
                    },
                    "max_backlog_seconds": round(max(backlog_s), 3) if backlog_s else None,
                    "output_delay_seconds": {
                        "p50": round(percentile(output_delay_s, 0.5), 3),
                        "p95": round(percentile(output_delay_s, 0.95), 3),
                        "max": round(max(output_delay_s), 3),
                    } if output_delay_s else None,
                    "flush_ms": round(flush_ms, 2),
                    "role_counts": role_counts,
                    "confidence_counts": confidence_counts,
                    "segments_emitted": len(emitted),
                    "outputs": {"events_jsonl": str(log_path), "segments_json": str(segments_path), "summary_json": str(summary_path)},
                    "note": "Точность ролей не измерена: для этого нужна эталонная разметка.",
                }
                segments_path.write_text(json.dumps(sorted(emitted, key=lambda row: (row["start"], row["end"])), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                emit(handle, {"type": "summary", **summary})
                return summary
    finally:
        diarizer.close()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="Одна потоковая сессия Nemotron по аудиофрагментам.")
    parser.add_argument("--audio", type=Path, required=True, help="Исходный WAV PCM16 16 кГц (mono/stereo).")
    parser.add_argument("--nemo-lib", type=Path, required=True, help="Путь к nemo_speech_asr_c.dll (или .so).")
    parser.add_argument("--nemo-model", type=Path, required=True, help="Локальный GGUF Nemotron.")
    parser.add_argument("--speaker-model", type=Path, required=True, help="Локальный ONNX CAMP++.")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--chunk-seconds", type=float, default=0.64)
    parser.add_argument("--settle-seconds", type=float, default=0.64)
    parser.add_argument("--pace", choices=["realtime", "fast"], default="realtime")
    parser.add_argument("--preset", default="v3-streaming")
    parser.add_argument("--enroll-start", type=float, default=20.0)
    parser.add_argument("--enroll-duration", type=float, default=10.0)
    parser.add_argument("--threshold", type=float, default=0.60)
    parser.add_argument("--num-threads", type=int, default=4)
    parser.add_argument("--max-seconds", type=float, default=0.0, help="Ограничить тест первыми N секундами.")
    parser.add_argument("--save-chunks", action="store_true", help="Сохранить WAV каждого отправленного буфера.")
    args = parser.parse_args()
    try:
        run(args)
        return 0
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
