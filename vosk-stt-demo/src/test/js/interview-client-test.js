// Проверка клиентской логики синхронизации страницы interview.html без браузера:
//   node src/test/js/interview-client-test.js
// Фальшивые DOM, озвучка и таймеры позволяют проверить: отложенный показ вопроса, отмену решения, устаревшую озвучку.
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/interview.html'), 'utf-8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

function makeEnv() {
  const els = {};
  const mk = () => ({
    textContent: '', children: [], style: {}, checked: true, value: '1', onclick: null, oninput: null, onchange: null,
    classList: { _s: new Set(), add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); }, toggle(c, f) { f ? this._s.add(c) : this._s.delete(c); }, contains(c) { return this._s.has(c); } },
    append(...a) { this.children.push(...a); }, prepend(a) { this.children.unshift(a); }, replaceChildren() { this.children = []; },
    setAttribute() {},
  });
  const timers = []; let now = 0, seq = 0;
  const sent = [], spoken = []; let cancels = 0;
  const ctx = {
    document: { getElementById: id => els[id] || (els[id] = mk()), createElement: () => mk() },
    localStorage: { getItem: () => null, setItem() {} },
    navigator: {}, console,
    setTimeout: (f, ms) => { const id = ++seq; timers.push({ id, at: now + ms, f }); return id; },
    clearTimeout: id => { const i = timers.findIndex(t => t.id === id); if (i >= 0) timers.splice(i, 1); },
    WebSocket: { OPEN: 1 },
    speechSynthesis: { getVoices: () => [], speak: u => spoken.push(u), cancel: () => { cancels++; } },
    SpeechSynthesisUtterance: function (t) { this.text = t; },
  };
  ctx.window = ctx;
  ctx.document.getElementById('phones').checked = false;   // по умолчанию режим «в наушниках» выключен
  vm.createContext(ctx);
  vm.runInContext(script, ctx);
  vm.runInContext('ws = { readyState: 1, send: x => __sent.push(x) };', Object.assign(ctx, { __sent: sent }));
  return {
    els, sent, spoken, get cancels() { return cancels; },
    msg: m => vm.runInContext('onMessage(' + JSON.stringify(m) + ')', ctx),
    eval: code => vm.runInContext(code, ctx),
    tick(ms) {
      const end = now + ms;
      for (;;) {
        timers.sort((a, b) => a.at - b.at);
        if (!timers.length || timers[0].at > end) break;
        const t = timers.shift(); now = t.at; t.f();
      }
      now = end;
    },
    ready: () => sent.filter(s => JSON.parse(s).type === 'ready').length,
  };
}

const Q1 = { type: 'question', number: 1, total: 3, level: 'Стажёр', topic: 'Java Core', text: 'Вопрос один', spoken: 'вопрос один', followUp: false, followUpNumber: 0, graceMs: 0 };
const FU = { type: 'question', number: 1, total: 3, level: 'Стажёр', topic: 'Java Core', text: 'Уточнение', spoken: 'уточнение', followUp: true, followUpNumber: 1, graceMs: 1000 };
let passed = 0;
function test(name, fn) { fn(); passed++; console.log('PASS ' + name); }

test('первый вопрос показывается и озвучивается сразу, микрофон закрыт до конца озвучки', () => {
  const e = makeEnv();
  e.msg(Q1);
  assert.strictEqual(e.els.qtext.textContent, 'Вопрос один');
  assert.strictEqual(e.eval('canSend'), false);
  assert.strictEqual(e.spoken.length, 1);
  e.spoken[0].onend();
  assert.strictEqual(e.eval('canSend'), true);
  assert.strictEqual(e.ready(), 1);
});

test('вопрос после решения появляется только после окна отмены, а микрофон всё это время открыт', () => {
  const e = makeEnv();
  e.msg(Q1); e.spoken[0].onend();
  e.msg(FU);
  assert.strictEqual(e.els.qtext.textContent, 'Вопрос один', 'на экране прежний вопрос');
  assert.strictEqual(e.eval('canSend'), true, 'микрофон открыт в окне отмены');
  assert.strictEqual(e.spoken.length, 1, 'озвучка нового вопроса не началась');
  e.tick(999);
  assert.strictEqual(e.els.qtext.textContent, 'Вопрос один');
  e.tick(1);
  assert.strictEqual(e.els.qtext.textContent, 'Уточнение');
  assert.strictEqual(e.eval('canSend'), false, 'на время озвучки микрофон закрывается');
  assert.strictEqual(e.spoken.length, 2);
  e.spoken[1].onend();
  assert.strictEqual(e.ready(), 2);
});

test('отмена решения в окне: новый вопрос не показывается и не озвучивается, «готов» не отправляется', () => {
  const e = makeEnv();
  e.msg(Q1); e.spoken[0].onend();
  const readyBefore = e.ready();
  e.msg(FU);
  e.tick(400);
  e.msg({ type: 'retract', reason: 'speech_resumed', question: Q1 });
  e.tick(5000);
  assert.strictEqual(e.els.qtext.textContent, 'Вопрос один');
  assert.strictEqual(e.spoken.length, 1, 'уточнение озвучено не было');
  assert.strictEqual(e.ready(), readyBefore, 'ложного «готов» нет');
  assert.strictEqual(e.eval('canSend'), true);
  assert.ok(e.els.log.children.length >= 1, 'отмена видна в журнале');
});

test('отмена пришла уже ПОСЛЕ показа вопроса: озвучка остановлена, прежний вопрос возвращён, устаревшее «готов» игнорируется', () => {
  const e = makeEnv();
  e.msg(Q1); e.spoken[0].onend();
  const readyBefore = e.ready();
  e.msg(FU);
  e.tick(1000);                                        // вопрос уже на экране и озвучивается
  assert.strictEqual(e.els.qtext.textContent, 'Уточнение');
  const cancelsBefore = e.cancels;
  e.msg({ type: 'retract', reason: 'speech_resumed', question: Q1 });
  assert.ok(e.cancels > cancelsBefore, 'озвучка остановлена');
  assert.strictEqual(e.els.qtext.textContent, 'Вопрос один', 'прежний вопрос восстановлен');
  assert.strictEqual(e.eval('canSend'), true);
  e.spoken[1].onend();                                 // «хвост» старой озвучки
  e.tick(60000);                                       // и страховочный таймер
  assert.strictEqual(e.ready(), readyBefore, 'устаревшее «готов» не отправляется');
});

test('«Пропустить» не работает, пока ждём окно отмены', () => {
  const e = makeEnv();
  e.msg(Q1); e.spoken[0].onend();
  e.msg(FU);
  const n = e.sent.length;
  e.els.skip.onclick();
  assert.strictEqual(e.sent.length, n);
});

test('предупреждение о медленном распознавании показывается и сам скрывается', () => {
  const e = makeEnv();
  e.msg({ type: 'warning', code: 'asr_slow', rtf: 1.3, message: 'Распознавание не успевает' });
  assert.ok(e.els.warn.textContent.includes('не успевает'));
  assert.ok(!e.els.warn.classList.contains('hidden'));
  e.tick(21000);
  assert.ok(e.els.warn.classList.contains('hidden'));
});

test('последнее решение (конец списка): микрофон остаётся открытым до отчёта', () => {
  const e = makeEnv();
  e.msg(Q1); e.spoken[0].onend();
  e.msg({ type: 'decision', action: 'FINISH', reason: 'sufficient', coveragePercent: 80, answerWords: 30, recognized: 'текст',
          timing: { serverMs: 600, silenceMs: 600, decisionMicros: 20 } });
  assert.strictEqual(e.eval('canSend'), true);
  assert.ok(e.els.status.textContent.includes('итоги'));
});

test('режим «в наушниках»: микрофон открыт во время озвучки, а время озвучки сообщается серверу', () => {
  const e = makeEnv();
  e.els.phones.checked = true;
  e.msg(Q1);
  assert.strictEqual(e.eval('canSend'), true, 'слушаем уже во время озвучки');
  assert.strictEqual(e.ready(), 1, '«готов» отправлен сразу');
  e.tick(3000);
  e.spoken[0].onend();
  const end = e.sent.map(JSON.parse).find(m => m.type === 'tts_end');
  assert.ok(end && end.afterMs >= 0, 'клиент сообщил, когда закончилась озвучка');
});

console.log('\n' + passed + ' passed');
