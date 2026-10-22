"""Render the current local interview MVP as a C4 container/component poster.

Run: python docs/architecture/render_current_system.py
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch


OUT = Path(__file__).resolve().parent
plt.rcParams.update({
    "font.family": "DejaVu Sans",
    "svg.fonttype": "path",  # portable Cyrillic text in SVG
})

fig, ax = plt.subplots(figsize=(22, 14.5), dpi=200)
fig.patch.set_facecolor("white")
ax.set_facecolor("white")
ax.set_xlim(0, 22)
ax.set_ylim(0, 14.5)
ax.axis("off")

ink = "#163047"
muted = "#4D6575"
border = "#D3DFE7"
blue = "#176A9C"
blue_light = "#EAF5FC"
green = "#16715B"
green_light = "#EAF8F3"
purple = "#6855A4"
purple_light = "#F1EDFA"
amber = "#9A6400"
amber_light = "#FFF6DF"
grey_light = "#F3F6F8"


def text(x, y, value, size=11, color=ink, weight="normal", ha="left", va="top", **kwargs):
    return ax.text(x, y, value, fontsize=size, color=color, weight=weight,
                   ha=ha, va=va, linespacing=1.35, zorder=10, **kwargs)


def box(x, y, w, h, title, body, color, fill, tag, title_size=13, body_size=10.5):
    ax.add_patch(FancyBboxPatch(
        (x, y), w, h, boxstyle="round,pad=0.015,rounding_size=0.13",
        linewidth=1.65, edgecolor=color, facecolor=fill, zorder=2))
    ax.add_patch(FancyBboxPatch(
        (x, y + h - 0.55), w, 0.55,
        boxstyle="round,pad=0.015,rounding_size=0.13",
        linewidth=0, facecolor=color, zorder=3))
    # Hide the rounded bottom of the header strip.
    ax.add_patch(plt.Rectangle((x, y + h - 0.55), w, 0.34, linewidth=0, facecolor=color, zorder=3))
    text(x + 0.16, y + h - 0.13, title, title_size, "white", "bold")
    text(x + 0.16, y + h - 0.68, tag, 8.6, color, "bold")
    text(x + 0.16, y + h - 1.03, body, body_size, ink)


def arrow(x1, y1, x2, y2, label=None, color=muted, label_x=None, label_y=None,
          size=9.3, style="-|>", weight="normal"):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=style,
                                 mutation_scale=13, linewidth=1.8,
                                 color=color, zorder=5, shrinkA=3, shrinkB=3))
    if label:
        xx = (x1 + x2) / 2 if label_x is None else label_x
        yy = max(y1, y2) + 0.10 if label_y is None else label_y
        text(xx, yy, label, size, color, weight, "center", "bottom",
             bbox={"facecolor": "white", "edgecolor": "none", "pad": 0.8})


text(0.65, 14.05, "Текущая система интервью: звук → роль → текст → подсказка", 24, ink, "bold")
text(0.67, 13.60, "C4: контейнеры и основные компоненты · локальный запуск на CPU · режим integrated", 11.5, muted)
ax.plot([0.65, 21.35], [13.35, 13.35], color=border, lw=1.5)

text(0.65, 13.12, "01  C4 / УРОВЕНЬ 2 — КОНТЕЙНЕРЫ", 12.5, blue, "bold")
ax.add_patch(FancyBboxPatch((3.7, 6.48), 17.65, 6.25,
                            boxstyle="round,pad=0.03,rounding_size=0.19",
                            linewidth=1.35, edgecolor=border,
                            facecolor="#FAFCFE", zorder=0))
text(20.98, 12.56, "Ноутбук интервьюера · localhost", 9.2, muted, "bold", ha="right")
text(4.10, 12.38, "Транспорт: браузер → Java HTTP; Java ↔ Артём WebSocket; Артём ↔ Whisper HTTP", 9.0, muted)

box(0.58, 9.52, 2.56, 2.35, "Участники", "Кандидат и интервьюеры\nчерез микрофон и/или\nсистемный звук компьютера", purple, purple_light, "[Person]", 12.5, 10.2)
box(0.58, 6.98, 2.56, 1.91, "Оператор", "Видит вопрос, роли,\nтекст и подсказку", purple, purple_light, "[Person]", 12.5, 10.2)

box(4.08, 9.05, 3.15, 2.91, "Chrome / Edge", "Микрофон + весь экран со звуком\nэталон: общий сигнал\nинтервью: (mic + system) / 2\nAudioWorklet, TTS, интерфейс", blue, blue_light, "[Container] Браузер", 12.7, 9.6)
box(8.00, 9.05, 4.00, 2.91, "Наш Java-сервис :8765", "HTTP API и страница\nLiveSession + IntegrationBridge\nNemotron/CAM++ и роли\nобщая шкала аудиосэмплов", blue, blue_light, "[Container] Java 17", 12.5, 10.3)
box(12.80, 9.05, 4.08, 2.91, "Сервис Артёма :8080", "/ws/stt: весь разговор\n/ws/interview: речь кандидата\nVosk, конец ответа, анализ\npreview / decision / report", green, green_light, "[Container] Spring Boot / Java", 12.5, 10.3)
box(17.65, 9.30, 3.30, 2.42, "Whisper :9000", "Python / faster-whisper\nУточняет завершённый\nответ асинхронно", amber, amber_light, "[Container] Python / CPU", 12.3, 10.2)

box(4.08, 6.87, 3.15, 1.29, "JSON прогона", "7 файлов: роли, STT, timeline, метрики", purple, purple_light, "[Data store] Локальные файлы", 11.3, 8.9)
box(8.00, 6.87, 4.00, 1.29, "Модели голосов", "Nemotron GGUF/DLL + CAM++ ONNX", blue, blue_light, "[Component / model] CPU", 11.3, 8.9)
box(12.80, 6.87, 4.08, 1.29, "Модель речи", "vosk-model-ru-0.42", green, green_light, "[Component / model] CPU", 11.3, 8.9)

arrow(3.16, 10.69, 4.02, 10.69, "звук", purple, 3.59, 10.83, 9.0)
arrow(3.16, 7.95, 3.60, 7.95, color=purple)
arrow(3.60, 7.95, 3.60, 9.55, "управление", purple, 3.53, 8.57, 8.7)
arrow(3.60, 9.55, 4.02, 9.55, color=purple)

arrow(7.25, 10.70, 7.94, 10.70, color=blue)
arrow(7.98, 9.47, 7.27, 9.47, color=blue)
arrow(12.03, 10.70, 12.75, 10.70, color=green)
arrow(12.77, 9.47, 12.04, 9.47, color=green)
arrow(16.91, 10.68, 17.60, 10.68, color=amber)
arrow(17.60, 9.61, 16.91, 9.61, color=amber)

arrow(8.00, 9.00, 7.25, 8.18, "пишет 7 JSON", purple, 7.65, 8.61, 8.5)
arrow(10.00, 9.00, 10.00, 8.20, "0,64 с; 1 сессия", blue, 10.00, 8.51, 8.4)
arrow(14.84, 9.00, 14.84, 8.20, "распознавание", green, 14.84, 8.51, 8.4)

text(0.65, 6.13, "02  C4 / УРОВЕНЬ 3 — КОМПОНЕНТНЫЙ ПОТОК СОПОСТАВЛЕНИЯ", 12.5, blue, "bold")
ax.plot([0.65, 21.35], [5.92, 5.92], color=border, lw=1.1)

box(0.70, 2.55, 3.60, 2.48, "Один поток", "Аудио интервью\nPCM16, 16 кГц\nкадры 0,16 с\nединый sample index", blue, blue_light, "[Component] AudioWorklet → HTTP", 12.2, 10.2)
box(4.91, 3.72, 4.05, 1.45, "Vosk / STT", "Слово + start/end + текст", green, green_light, "[Component] Общий разговор", 11.6, 9.3)
box(4.91, 1.94, 4.05, 1.45, "Nemotron + CAM++", "Сегмент + speaker ID + роль", blue, blue_light, "[Component] Диаризация", 11.6, 9.3)
box(9.62, 2.55, 5.02, 2.48, "IntegrationBridge.role()", "Пересечение слова с сегментами\nМаксимум ≥ ½ длины слова\nКонкурирующий сегмент < ¾ max\nИначе: «Не определено»", purple, purple_light, "[Component] Сопоставление по времени", 12.0, 10.0)
box(15.31, 2.55, 5.84, 2.48, "Хронология и анализ", "timeline.json: слово + время + роль\nпредварительное → подтверждённое\nтолько подтверждённый кандидат\nидёт в анализ ответа Артёма\nWhisper позже уточняет итоговый текст", green, green_light, "[Component] Выход + role gate", 12.0, 9.8)

arrow(4.33, 3.90, 4.86, 4.42, color=muted)
arrow(4.33, 3.28, 4.86, 2.66, color=muted)
arrow(8.99, 4.43, 9.56, 4.03, color=muted)
arrow(8.99, 2.67, 9.56, 3.16, color=muted)
arrow(14.67, 3.79, 15.26, 3.79, color=muted)

ax.add_patch(FancyBboxPatch((0.70, 0.75), 20.45, 0.75,
                            boxstyle="round,pad=0.015,rounding_size=0.10",
                            linewidth=1.1, edgecolor=border, facecolor=grey_light))
text(1.00, 1.30, "Пример:", 10.3, ink, "bold")
text(2.20, 1.30, "«виртуальная» [11,269–11,880] ∩ «Кандидат» [9,301–18,029] = 0,611 с (100% слова) → роль «Кандидат»", 10.2, ink)
text(0.75, 0.46, "Время start/end — секунды от начала интервью. Confidence Vosk и confidence_percent роли — разные показатели.", 9.1, muted)

fig.subplots_adjust(left=0, right=1, bottom=0, top=1)
fig.savefig(OUT / "c4-current-system.png", dpi=200, facecolor="white", bbox_inches=None)
fig.savefig(OUT / "c4-current-system.svg", facecolor="white", bbox_inches=None)
plt.close(fig)

