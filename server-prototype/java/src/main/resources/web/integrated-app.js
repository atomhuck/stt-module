const $ = id => document.getElementById(id);
const enroll = $('enroll'), finish = $('finish');
let stream, display, context, micSource, systemSource, worklet, sessionId;
let mode = 'idle', sequence = 0, closing = false, finishRequested = false, pending = Promise.resolve(), stopResolver;
let startTime = 0, polling = false, currentQuestionKey = '', scheduledQuestionKey = '', questionTimer;
let lastDecision = '', lastPreviewKey = '', lastPreviewId = '', lastReportSignature = '', eventLines = [];
let voices = [], activeQuestion = null, questionBlocked = false, questionRetracted = false;

async function request(path, body, headers = {}) {
  const response = await fetch(path, {method: body === undefined ? 'GET' : 'POST', body,
    headers: {...headers, ...(sessionId ? {'X-Session-Id': sessionId} : {})}});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
function showError(message) { $('error').textContent = message; $('error').hidden = false; }
function clearError() { $('error').hidden = true; }
function time(seconds) {
  const value = Math.floor(seconds);
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}
function log(message) {
  const row = document.createElement('div');
  row.textContent = `${new Date().toLocaleTimeString()} · ${message}`;
  $('log').prepend(row); eventLines.unshift(row);
  while (eventLines.length > 35) eventLines.pop()?.remove();
}
function recordTts(event, questionText) {
  request('/api/tts/event', JSON.stringify({event, question:questionText}), {'Content-Type':'application/json'})
    .catch(error => showError(error.message));
}
function actionText(action) {
  return ({ASK_FOLLOW_UP:'Нужно уточнение', NEXT_QUESTION:'Ответ достаточный · следующий вопрос',
    FINISH:'Вопросы завершены'})[action] || action || 'Решение';
}
function scoreText(value) {
  if (value == null || value === '') return '—';
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString('ru-RU', {maximumFractionDigits:2}) : String(value);
}
function renderFinalReport(report, phase) {
  const panel = $('final-report'), body = $('report-body');
  if (!report) {
    body.hidden = true;
    if (finishRequested || phase === 'stopping') {
      panel.hidden = false;
      $('report-state').textContent = phase === 'error'
        ? 'Итоговый отчёт не получен. Проверьте ошибку обработки и файлы результата.'
        : 'Завершаем обработку аудио и ждём итоговый отчёт. Если Whisper включён, сервис также ждёт его уточнения.';
    } else panel.hidden = true;
    return;
  }
  panel.hidden = false; body.hidden = false;
  $('report-state').textContent = 'Итоговая оценка готова. Для ответов, которые Whisper успел уточнить, использована его финальная расшифровка.';
  const signature = JSON.stringify(report);
  if (signature === lastReportSignature) return;
  lastReportSignature = signature;

  $('report-total').textContent = `${scoreText(report.total ?? report.totalText)} / 10`;
  $('report-verdict').textContent = report.verdict || 'Оценка завершена';
  const answered = report.answeredQuestions ?? '—', planned = report.plannedQuestions ?? '—';
  $('report-counts').textContent = `${report.finishedEarly ? 'Интервью завершено досрочно' : 'Интервью завершено'} · отвечено на ${answered} из ${planned} вопросов.`;

  const dimensions = [
    ['Полнота', 'completeness'], ['Техническая точность', 'technical'],
    ['Терминология', 'terminology'], ['Скорость', 'speed']
  ];
  const scores = $('report-scores'); scores.replaceChildren();
  for (const [label, key] of dimensions) {
    const item = document.createElement('div'); item.className = 'report-score';
    const name = document.createElement('span'); name.textContent = label;
    const value = document.createElement('strong'); value.textContent = `${scoreText(report[key])} / 10`;
    item.append(name, value); scores.append(item);
  }

  const questionList = $('report-questions'); questionList.replaceChildren();
  for (const [index, question] of (Array.isArray(report.questions) ? report.questions : []).entries()) {
    const item = document.createElement('article'); item.className = 'report-question';
    const title = document.createElement('strong');
    title.textContent = `Вопрос ${question.number ?? index + 1} · ${question.topic || question.level || 'Интервью'}`;
    const text = document.createElement('p'); text.textContent = question.question || '';
    const details = document.createElement('p');
    details.textContent = `Покрытие ${question.coveragePercent ?? '—'}% · попыток: ${question.attempts ?? '—'} · полнота ${scoreText(question.completeness)} · техническая точность ${scoreText(question.technical)}`;
    item.append(title, text, details);
    if (Array.isArray(question.missing) && question.missing.length) {
      const missing = document.createElement('p'); missing.textContent = `Не прозвучало: ${question.missing.join(', ')}`;
      item.append(missing);
    }
    questionList.append(item);
  }
}
function questionKey(q) { return [q.number, q.followUpNumber, q.text].join('|'); }
function renderTranscript(integration) {
  const rows = integration.timeline_words || [];
  const target = $('transcript'); target.replaceChildren();
  if (!rows.length) {
    target.textContent = integration.mixed_transcript || '—';
  } else {
    let previous = null, line = null;
    for (const word of rows) {
      const baseRole = word.role || 'Не определено';
      const role = word.role_status === 'preliminary' ? `${baseRole} · предварительно` : baseRole;
      if (role !== previous) {
        if (line) target.append(line, document.createElement('br'));
        line = document.createElement('span');
        const speaker = document.createElement('strong');
        speaker.className = role === 'Не определено' ? 'unknown' : 'speaker';
        speaker.textContent = `${role}: `; line.append(speaker); previous = role;
      }
      line.append(document.createTextNode(`${word.word || ''} `));
    }
    if (line) target.append(line);
  }
  $('partial').textContent = integration.mixed_partial
    ? `${integration.current_role || 'Не определено'} · ${integration.mixed_partial} (предварительно)`
    : 'Промежуточный текст Vosk появится во время речи.';
}
function render(status) {
  const phase = status.phase || 'idle', integration = status.integration || {};
  $('phase').textContent = ({idle:'Ожидание', enrolling:'Запись эталона с микрофона', preparing:'Создаём эталон',
    retry:'Повторите эталон', live:'Интервью · анализ идёт', stopping:'Завершаем анализ',
    finished:'Интервью завершено', error:'Ошибка обработки'})[phase] || phase;
  $('light').classList.toggle('active', phase === 'enrolling' || phase === 'live');
  $('role').textContent = phase === 'live' || phase === 'stopping'
    ? integration.current_role || status.role || 'Ожидаем речь' : '—';
  $('audio-time').textContent = `${(status.audio_seconds || 0).toFixed(1).replace('.', ',')} с`;
  $('queue').textContent = `${status.queue_depth_chunks || 0} кадров`;
  if (status.output_dir) $('output').textContent = status.output_dir;
  if (status.error) showError(status.error);
  if (integration.bridge_error) showError(integration.bridge_error);
  renderTranscript(integration);
  renderFinalReport(integration.report, phase);

  const question = integration.question;
  if (question) {
    activeQuestion = question;
    const key = questionKey(question), waitMs = integration.question_wait_ms || 0;
    questionRetracted = Boolean(integration.decision_retracted);
    questionBlocked = waitMs > 0 || questionRetracted;
    if (key !== currentQuestionKey) {
      currentQuestionKey = key; scheduledQuestionKey = '';
      clearTimeout(questionTimer);
      $('question').textContent = question.text || '—';
      $('question-meta').textContent = `Вопрос ${question.number || ''} · ${question.level || ''} · ${question.topic || ''}`;
      log(`Поступил вопрос ${question.number || ''}${question.followUp ? ' (уточнение)' : ''}`);
    }
    const ttsOn = $('tts-enabled').checked && 'speechSynthesis' in window;
    $('asked').hidden = ttsOn;
    $('asked').disabled = phase !== 'live' || waitMs > 0;
    $('skip').disabled = phase !== 'live' || waitMs > 0;
    if (integration.decision_retracted) {
      clearTimeout(questionTimer); scheduledQuestionKey = '';
      $('tts-state').textContent = 'Кандидат продолжил ответ: прежний вопрос остаётся активным, TTS ждёт нового решения.';
      $('asked').disabled = true; $('skip').disabled = true;
    } else {
    if (waitMs > 0) {
      $('tts-state').textContent = `Ждём окно отмены ответа: ${(waitMs / 1000).toFixed(1)} с.`;
      $('asked').disabled = true;
      if (scheduledQuestionKey !== key) {
        scheduledQuestionKey = key;
        const expected = key;
        questionTimer = setTimeout(() => {
          if (currentQuestionKey === expected && !questionRetracted) {
            questionBlocked = false;
            scheduledQuestionKey = expected;
            speakQuestion(question, expected);
          }
        }, waitMs + 25);
      }
    } else if (scheduledQuestionKey !== key) {
      scheduledQuestionKey = key;
      speakQuestion(question, key);
    }
    }
  }

  const preview = integration.preview;
  if (preview) {
    const previewKey = [preview.recognized, preview.coveragePercent, preview.reason, preview.suggestedFollowUp].join('|');
    $('preview').textContent = `Предварительно · ${actionText(preview.action)} · покрытие ${preview.coveragePercent}% · ${preview.answerWords} слов · ${preview.reason || ''}`
      + (preview.suggestedFollowUp ? ` · Уточнить: ${preview.suggestedFollowUp}` : '')
      + (preview.end_to_preview_ms != null ? ` · ${preview.end_to_preview_ms} мс` : '');
    if (previewKey !== lastPreviewKey) { lastPreviewKey = previewKey; log(`Предварительная подсказка: ${actionText(preview.action)}, ${preview.coveragePercent}%`); }
    if (preview.event_id && preview.event_id !== lastPreviewId) {
      lastPreviewId = preview.event_id;
      request('/api/ui/seen', new Uint8Array(), {'X-Event-Id':String(preview.event_id)})
        .catch(error => showError(error.message));
    }
  } else if (lastPreviewKey) {
    $('preview').textContent = 'Предварительная подсказка отменена: роль кандидата больше не подтверждается.';
    lastPreviewKey = ''; log('Предварительная подсказка отменена');
  }
  if (integration.decision) {
    const d = integration.decision, t = d.timing || {};
    $('decision').textContent = `${actionText(d.action)} · ${d.reason || ''} · покрытие ${d.coveragePercent ?? '—'}% · ${d.answerWords ?? '—'} слов · решение ${t.serverMs ?? '—'} мс внутри STT-сервиса`
      + (d.end_to_decision_ms != null ? ` · от конца речи ${d.end_to_decision_ms} мс` : '');
    if (d.event_id && d.event_id !== lastDecision) {
      lastDecision = d.event_id;
      log(`Итоговое решение Артёма: ${actionText(d.action)}, покрытие ${d.coveragePercent}%`);
      request('/api/ui/seen', new Uint8Array(), {'X-Event-Id': String(d.event_id)})
        .catch(error => showError(error.message));
    }
  } else if (lastDecision) {
    lastDecision = '';
    $('decision').textContent = 'Решение отменено: кандидат продолжил ответ; текущий вопрос сохранён.';
    log('Решение отменено, интервью продолжено');
  }
  $('whisper').textContent = 'Whisper: ' + (integration.whisper_state || 'ожидание')
    + (integration.refined?.text ? ` · уточнённый текст: ${integration.refined.text}` : '');
  if (integration.refined?.text) log(`Whisper уточнил ответ: ${integration.refined.text}`);

  if (phase === 'retry') {
    enroll.disabled = false; enroll.textContent = 'Повторить запись голоса'; finish.disabled = true;
  } else if (phase === 'live') {
    enroll.disabled = true; finish.disabled = false;
    $('hint').textContent = 'Эталон записан с микрофона. В интервью микрофон и звук компьютера смешиваются в один mono PCM 16 кГц поток.';
  } else if (phase === 'finished' || phase === 'error') {
    enroll.disabled = false; enroll.textContent = 'Новое интервью'; finish.disabled = true;
  }
}
function speakQuestion(question, key) {
  if (currentQuestionKey !== key || mode !== 'live' || questionBlocked) return;
  if (!$('tts-enabled').checked || !('speechSynthesis' in window)) {
    $('tts-state').textContent = 'Озвучка выключена: задайте вопрос вслух и нажмите кнопку ниже.';
    $('asked').hidden = false; $('asked').disabled = false; $('skip').disabled = false;
    return;
  }
  if (!voices.length) loadVoices();
  $('asked').hidden = true; $('asked').disabled = true; $('skip').disabled = true;
  $('tts-state').textContent = 'Озвучиваю вопрос. После озвучки автоматически начнётся анализ ответа.';
  const utterance = new SpeechSynthesisUtterance(question.spoken || question.text);
  const selected = voices.find(voice => voice.name === $('voice').value);
  if (selected) { utterance.voice = selected; utterance.lang = selected.lang; }
  else utterance.lang = 'ru-RU';
  utterance.rate = Number($('rate').value);
  let done = false;
  const after = async () => {
    if (done || currentQuestionKey !== key || mode !== 'live') return;
    done = true; $('tts-state').textContent = 'Вопрос озвучен · можно отвечать';
    try { await request('/api/question/asked', new Uint8Array()); log('TTS завершён, поток ответа открыт'); }
    catch (error) { showError(error.message); }
  };
  utterance.onstart = () => { recordTts('start', question.text); log(`TTS начал вопрос ${question.number || ''}`); };
  utterance.onend = () => { recordTts('finish', question.text); after(); };
  utterance.onerror = () => { recordTts('error', question.text); after(); };
  speechSynthesis.cancel(); speechSynthesis.speak(utterance);
  log(`TTS озвучивает вопрос ${question.number || ''}`);
}
function loadVoices() {
  if (!('speechSynthesis' in window)) return;
  voices = speechSynthesis.getVoices();
  const select = $('voice'), previous = select.value; select.replaceChildren();
  for (const voice of voices) {
    const option = document.createElement('option'); option.value = voice.name;
    option.textContent = `${voice.name}${voice.lang ? ` · ${voice.lang}` : ''}`; select.append(option);
  }
  const preferred = voices.find(voice => voice.name === previous) || voices.find(voice => /^ru/i.test(voice.lang));
  if (preferred) select.value = preferred.name;
}
if ('speechSynthesis' in window) { speechSynthesis.onvoiceschanged = loadVoices; loadVoices(); }
$('rate').oninput = () => $('rate-value').textContent = Number($('rate').value).toFixed(2).replace('.', ',');
$('tts-enabled').onchange = () => {
  if (!$('tts-enabled').checked && 'speechSynthesis' in window) {
    if (speechSynthesis.speaking && activeQuestion) recordTts('cancel', activeQuestion.text);
    speechSynthesis.cancel();
  }
  scheduledQuestionKey = '';
  if (activeQuestion && currentQuestionKey && !questionBlocked) {
    scheduledQuestionKey = currentQuestionKey;
    speakQuestion(activeQuestion, currentQuestionKey);
  }
};

function stopAudio() {
  if (!worklet || (mode !== 'enroll' && mode !== 'live')) return Promise.resolve();
  const ended = new Promise(resolve => { stopResolver = resolve; });
  worklet.port.postMessage('stop'); return ended;
}
function releaseAudio() {
  for (const captured of [stream, display]) captured?.getTracks().forEach(track => {
    track.onended = null; track.stop();
  });
  micSource?.disconnect(); systemSource?.disconnect(); worklet?.disconnect(); context?.close();
  stream = display = context = micSource = systemSource = worklet = undefined;
  if ('speechSynthesis' in window) speechSynthesis.cancel();
}
function systemAudioAvailable() {
  return display?.getVideoTracks()[0]?.readyState === 'live' && display?.getAudioTracks()[0]?.readyState === 'live';
}
async function sourceLost(reason) {
  if (closing || mode === 'done' || mode === 'idle') return;
  closing = true;
  showError(reason === 'system_audio_lost'
    ? 'Демонстрация экрана или системный звук прервались. Сохраняем обработанную часть интервью.'
    : 'Микрофон отключился. Сохраняем обработанную часть интервью.');
  try {
    if (mode === 'enroll' || mode === 'live') await stopAudio();
    mode = 'stopping'; try { await pending; } catch (error) { showError(error.message); }
    if (sessionId) render(await request('/api/finish', new Uint8Array(), {'X-Stop-Reason': reason}));
  } catch (error) { showError(error.message); }
  finally { mode = 'done'; releaseAudio(); enroll.disabled = false; closing = false; }
}
async function prepareAudio() {
  // getDisplayMedia must be invoked from the original button click.
  display = await navigator.mediaDevices.getDisplayMedia({video: {displaySurface:'monitor'}, audio:true,
    systemAudio:'include', monitorTypeSurfaces:'include'});
  const video = display.getVideoTracks()[0], systemAudio = display.getAudioTracks()[0];
  if (video?.getSettings().displaySurface !== 'monitor')
    throw new Error('Выберите весь экран, не окно или вкладку, и включите передачу системного звука.');
  if (!systemAudio || systemAudio.readyState !== 'live')
    throw new Error('Chrome/Edge не передал системный звук. Повторите выбор всего экрана со звуком.');
  stream = await navigator.mediaDevices.getUserMedia({audio:{channelCount:1, echoCancellation:false, noiseSuppression:false, autoGainControl:false}});
  context = new AudioContext(); await context.audioWorklet.addModule('/integrated-worklet.js');
  micSource = context.createMediaStreamSource(stream);
  systemSource = context.createMediaStreamSource(new MediaStream([systemAudio]));
  worklet = new AudioWorkletNode(context, 'mono-pcm', {numberOfInputs:2, numberOfOutputs:1, outputChannelCount:[1]});
  micSource.connect(worklet, 0, 1); systemSource.connect(worklet, 0, 0); worklet.connect(context.destination);
  worklet.port.onmessage = event => {
    if (event.data.type === 'stopped') { stopResolver?.(); stopResolver = undefined; return; }
    if (event.data.type !== 'audio') return;
    const path = mode === 'enroll' ? '/api/enrollment/audio' : '/api/audio';
    const number = sequence++;
    pending = pending.then(() => request(path, event.data.buffer, {'X-Sequence':String(number)}));
    pending.catch(error => { showError(error.message); if (mode === 'live') sourceLost('capture_error'); });
  };
  video.onended = systemAudio.onended = () => sourceLost('system_audio_lost');
  stream.getAudioTracks()[0].onended = () => sourceLost('microphone_lost');
  await context.resume();
  if (!systemAudioAvailable()) throw new Error('Демонстрация экрана или системный звук прервались до начала.');
}
async function startEnrollment() {
  clearError(); enroll.disabled = true; mode = 'opening'; sessionId = undefined;
  currentQuestionKey = scheduledQuestionKey = ''; lastDecision = ''; lastPreviewKey = lastPreviewId = '';
  finishRequested = false; lastReportSignature = '';
  $('final-report').hidden = true; $('report-body').hidden = true;
  $('report-state').textContent = 'Итоговая оценка появится после завершения интервью.';
  $('log').replaceChildren(); eventLines = [];
  $('question').textContent = 'Появится после записи эталона'; $('preview').textContent = 'Предварительной подсказки пока нет';
  $('decision').textContent = 'Ожидаем ответ кандидата';
  try {
    if (!navigator.mediaDevices?.getDisplayMedia || !navigator.mediaDevices?.getUserMedia)
      throw new Error('Откройте страницу на localhost в Chrome или Edge.');
    await prepareAudio();
    if (!systemAudioAvailable() || mode !== 'opening') throw new Error('Демонстрация экрана прервалась до начала записи.');
    const status = await request('/api/enrollment/start', new Uint8Array());
    sessionId = status.session_id; pending = Promise.resolve(); sequence = 0; mode = 'enroll'; startTime = performance.now();
    worklet.port.postMessage('enroll');
    enroll.disabled = false; enroll.textContent = 'Завершить запись эталона';
    $('hint').textContent = 'Говорите в микрофон около 10 секунд. В эталон попадёт только микрофон; системный звук нужен для интервью.';
    render(status); log('Началась запись эталона кандидата с микрофона');
  } catch (error) { showError(error.message); releaseAudio(); mode = 'idle'; enroll.disabled = false; }
}
async function finishEnrollment() {
  enroll.disabled = true;
  try {
    await stopAudio(); mode = 'preparing'; await pending;
    if (!systemAudioAvailable()) { await sourceLost('system_audio_lost'); return; }
    const status = await request('/api/enrollment/finish', new Uint8Array());
    if (status.phase === 'retry') { render(status); mode = 'retry'; releaseAudio(); return; }
    if (status.phase !== 'live') throw new Error(status.error || 'Не удалось начать анализ.');
    pending = Promise.resolve(); sequence = 0; mode = 'live'; startTime = performance.now(); worklet.port.postMessage('live');
    render(status);
    $('hint').textContent = 'Эталон готов. TTS задаст первый вопрос, затем автоматически откроется анализ ответа.';
    enroll.disabled = true; finish.disabled = false; log('Эталон принят, Nemotron и STT начали одну потоковую сессию');
  } catch (error) {
    showError(error.message);
    if (sessionId) try { await request('/api/finish', new Uint8Array(), {'X-Stop-Reason':'capture_error'}); } catch (_) {}
    releaseAudio(); mode = 'done'; enroll.disabled = false;
  }
}
async function finishInterview() {
  if (closing || mode !== 'live') return;
  closing = true; finishRequested = true; finish.disabled = true;
  renderFinalReport(null, 'stopping');
  try { await stopAudio(); mode = 'stopping'; await pending; render(await request('/api/finish', new Uint8Array(), {'X-Stop-Reason':'requested'})); }
  catch (error) { showError(error.message); }
  finally { releaseAudio(); closing = false; mode = 'done'; }
}
enroll.onclick = () => {
  if (mode === 'enroll') finishEnrollment();
  else if (mode === 'idle' || mode === 'retry' || mode === 'done') startEnrollment();
};
finish.onclick = finishInterview;
$('asked').onclick = async () => {
  $('asked').disabled = true;
  try { await request('/api/question/asked', new Uint8Array()); $('tts-state').textContent='Вопрос задан · можно отвечать'; log('Интервьюер отметил вопрос заданным'); }
  catch (error) { showError(error.message); $('asked').disabled = false; }
};
$('skip').onclick = async () => {
  $('skip').disabled = true;
  try { await request('/api/question/skip', new Uint8Array()); log('Вопрос пропущен'); }
  catch (error) { showError(error.message); $('skip').disabled = false; }
};
setInterval(async () => {
  if (mode === 'enroll' || mode === 'live') $('timer').textContent = time((performance.now() - startTime) / 1000);
  if (polling) return; polling = true;
  try {
    const status = await request('/api/status');
    if (mode !== 'opening' && status.session_id && status.session_id === sessionId) {
      render(status);
      if ((status.phase === 'error' || status.phase === 'finished') && mode === 'live') { mode='stopping'; releaseAudio(); }
      if (status.phase === 'finished' || status.phase === 'error') mode='done';
      if (status.phase === 'retry') mode='retry';
    }
  } catch (error) { showError(error.message); }
  finally { polling = false; }
}, 250);
