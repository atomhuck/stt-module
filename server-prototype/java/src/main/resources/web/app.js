const $ = id => document.getElementById(id);
const enroll = $('enroll'), finish = $('finish');
let stream, context, source, worklet, sessionId, mode = 'idle', sequence = 0;
let pending = Promise.resolve(), stopResolver, startTime = 0, polling = false;

async function request(path, body, headers = {}) {
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST', body,
    headers: {...headers, ...(sessionId ? {'X-Session-Id': sessionId} : {})}
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
function showError(message) {
  $('error').textContent = message;
  $('error').hidden = false;
}
function clearError() { $('error').hidden = true; }
function time(seconds) {
  const value = Math.floor(seconds);
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}
function render(status) {
  const phase = status.phase || 'idle';
  $('phase').textContent = ({idle:'Ожидание', enrolling:'Запись эталона', preparing:'Создаём эталон',
    retry:'Повторите эталон', live:'Можно говорить · анализ идёт', stopping:'Завершаем анализ',
    finished:'Интервью завершено', error:'Ошибка обработки'})[phase] || phase;
  $('light').classList.toggle('active', phase === 'enrolling' || phase === 'live');
  $('role').textContent = phase === 'live' || phase === 'stopping' ? status.role || 'Ожидаем речь' : '—';
  $('audio-time').textContent = `${(status.audio_seconds || 0).toFixed(1).replace('.', ',')} с`;
  $('queue').textContent = `${status.queue_depth_chunks || 0} чанков`;
  if (status.output_dir) $('output').textContent = status.output_dir;
  if (status.error) showError(status.error);
  if (phase === 'retry') {
    enroll.disabled = false; enroll.textContent = 'Повторить запись голоса'; finish.disabled = true;
  } else if (phase === 'live') {
    enroll.disabled = true; finish.disabled = false;
    $('hint').textContent = 'Говорите в микрофон. Роль предварительная; точный результат сохранится в JSON.';
  } else if (phase === 'finished' || phase === 'error') {
    enroll.disabled = false; enroll.textContent = 'Новое интервью'; finish.disabled = true;
  }
}
function stopAudio() {
  if (!worklet || (mode !== 'enroll' && mode !== 'live')) return Promise.resolve();
  const ended = new Promise(resolve => { stopResolver = resolve; });
  worklet.port.postMessage('stop');
  return ended;
}
function releaseMicrophone() {
  stream?.getTracks().forEach(track => track.stop());
  source?.disconnect(); worklet?.disconnect(); context?.close();
  stream = context = source = worklet = undefined;
}
async function prepareMicrophone() {
  stream = await navigator.mediaDevices.getUserMedia({audio: {channelCount: 1, echoCancellation: false, noiseSuppression: false}});
  stream.getAudioTracks()[0].onended = () => {
    showError('Микрофон отключился. Текущая запись завершается.');
    if (mode === 'live') finishInterview();
  };
  context = new AudioContext();
  await context.audioWorklet.addModule('/audio-worklet.js');
  source = context.createMediaStreamSource(stream);
  worklet = new AudioWorkletNode(context, 'mono-pcm');
  source.connect(worklet);
  worklet.connect(context.destination);
  worklet.port.onmessage = event => {
    if (event.data.type === 'stopped') { stopResolver?.(); stopResolver = undefined; return; }
    if (event.data.type !== 'audio') return;
    const path = mode === 'enroll' ? '/api/enrollment/audio' : '/api/audio';
    const number = sequence++;
    pending = pending.then(() => request(path, event.data.buffer, {'X-Sequence': String(number)}));
    pending.catch(error => {
      showError(error.message);
      if (mode === 'live') { mode = 'stopping'; worklet?.port.postMessage('stop'); }
    });
  };
  await context.resume();
}
async function startEnrollment() {
  clearError(); enroll.disabled = true; mode = 'opening';
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('Откройте страницу по http://localhost:8765 в браузере с поддержкой микрофона.');
    // Ask permission before creating a model session, so denial leaves the server idle.
    await prepareMicrophone();
    sessionId = undefined;
    const status = await request('/api/enrollment/start', new Uint8Array());
    sessionId = status.session_id;
    pending = Promise.resolve(); sequence = 0; mode = 'enroll'; startTime = performance.now();
    worklet.port.postMessage('start');
    enroll.disabled = false; enroll.textContent = 'Завершить запись голоса';
    $('hint').textContent = 'Говорите один около 10 секунд. Повторное нажатие завершит запись эталона.';
    render(status);
  } catch (error) {
    showError(error.message); releaseMicrophone(); mode = 'idle'; enroll.disabled = false;
  }
}
async function finishEnrollment() {
  enroll.disabled = true;
  try {
    await stopAudio(); mode = 'preparing'; await pending;
    const status = await request('/api/enrollment/finish', new Uint8Array());
    render(status);
    if (status.phase === 'retry') { mode = 'retry'; releaseMicrophone(); return; }
    if (status.phase !== 'live') throw new Error(status.error || 'Не удалось начать анализ.');
    pending = Promise.resolve(); sequence = 0; mode = 'live'; startTime = performance.now();
    worklet.port.postMessage('start');
  } catch (error) { showError(error.message); releaseMicrophone(); mode = 'done'; enroll.disabled = false; }
}
async function finishInterview() {
  finish.disabled = true;
  try {
    await stopAudio(); mode = 'stopping'; await pending;
    render(await request('/api/finish', new Uint8Array()));
    releaseMicrophone();
  } catch (error) { showError(error.message); releaseMicrophone(); }
}
enroll.onclick = () => {
  if (mode === 'enroll') finishEnrollment();
  else if (mode === 'idle' || mode === 'retry' || mode === 'done') startEnrollment();
};
finish.onclick = finishInterview;
setInterval(async () => {
  if (mode === 'enroll' || mode === 'live') $('timer').textContent = time((performance.now() - startTime) / 1000);
  if (polling) return;
  polling = true;
  try {
    const status = await request('/api/status');
    if (mode !== 'opening' && status.session_id && status.session_id === sessionId) {
      render(status);
      if ((status.phase === 'error' || status.phase === 'finished') && mode === 'live') {
        mode = 'stopping'; worklet?.port.postMessage('stop'); releaseMicrophone();
      }
      if (status.phase === 'finished' || status.phase === 'error') mode = 'done';
      if (status.phase === 'retry') mode = 'retry';
    }
  } catch (error) { showError(error.message); }
  finally { polling = false; }
}, 500);
