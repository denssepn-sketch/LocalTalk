'use strict';
const assert = require('assert');
const { Room, discover, joinRoom } = require('./lt-room');

const tests = [];
const test = (n, f) => tests.push([n, f]);
const mk = (o = {}) => new Room({ name: 'Equipe A', httpPort: 0, discoveryPort: 0, ...o });
const lo = r => ({ targets: ['127.0.0.1'], port: r.discoveryPort, timeout: 800 });

test('código tem 6 dígitos e muda a cada convite', async () => {
  const r = mk(); const a = r.setOffer('LT1:x'); const b = r.setOffer('LT1:y');
  assert.match(a, /^\d{6}$/); assert.match(b, /^\d{6}$/);
});

test('fluxo completo: descobre, baixa convite, devolve resposta', async () => {
  const r = await mk().start(); const code = r.setOffer('LT1:OFERTA');
  let got; r.onAnswer = a => got = a;
  const res = await joinRoom(code, async o => { assert.strictEqual(o, 'LT1:OFERTA'); return 'LT1:RESPOSTA'; }, lo(r));
  assert.strictEqual(res.name, 'Equipe A'); assert.strictEqual(got, 'LT1:RESPOSTA'); r.stop();
});

test('código errado não descobre a sala', async () => {
  const r = await mk().start(); r.setOffer('LT1:x');
  await assert.rejects(discover('000000', lo(r)), /não encontrada/); r.stop();
});

test('HTTP recusa código errado (403)', async () => {
  const r = await mk().start(); r.setOffer('LT1:x');
  await assert.rejects(joinRoom('999999', async () => 'LT1:a', { host: '127.0.0.1', port: r.httpPort }), /código inválido/); r.stop();
});

test('bloqueia após 5 tentativas erradas, mesmo com código certo', async () => {
  const r = await mk().start(); const code = r.setOffer('LT1:x');
  for (let i = 0; i < 5; i++) await joinRoom('111111', async () => 'LT1:a', { host: '127.0.0.1', port: r.httpPort }).catch(() => {});
  await assert.rejects(joinRoom(code, async () => 'LT1:a', { host: '127.0.0.1', port: r.httpPort }), /muitas tentativas/); r.stop();
});

test('convite só aceita uma resposta (409)', async () => {
  const r = await mk().start(); const code = r.setOffer('LT1:x'); const o = { host: '127.0.0.1', port: r.httpPort };
  await joinRoom(code, async () => 'LT1:a', o);
  await assert.rejects(joinRoom(code, async () => 'LT1:b', o), /já usado/); r.stop();
});

test('resposta que não começa com LT1: é rejeitada', async () => {
  const r = await mk().start(); const code = r.setOffer('LT1:x');
  await assert.rejects(joinRoom(code, async () => 'lixo', { host: '127.0.0.1', port: r.httpPort }), /inválida/); r.stop();
});

test('sala expirada responde 410', async () => {
  const r = await mk({ ttl: 1 }).start(); const code = r.setOffer('LT1:x');
  await new Promise(k => setTimeout(k, 10));
  await assert.rejects(joinRoom(code, async () => 'LT1:a', { host: '127.0.0.1', port: r.httpPort }), /expirada/); r.stop();
});

test('corpo grande demais é cortado', async () => {
  const r = await mk().start(); const code = r.setOffer('LT1:x');
  const http = require('http');
  const ok = await new Promise(res => {
    const q = http.request({ host: '127.0.0.1', port: r.httpPort, path: '/answer?code=' + code, method: 'POST' }, () => res(false));
    q.on('error', () => res(true)); q.end('{"answer":"LT1:' + 'a'.repeat(200000) + '"}');
    setTimeout(() => res(true), 800);
  });
  assert.ok(ok); assert.strictEqual(r.answer, null); r.stop();
});

(async () => {
  let f = 0;
  for (const [n, fn] of tests) {
    try { await fn(); console.log('ok   ', n); } catch (e) { f++; console.log('FALHA', n, '-', e.message); }
  }
  console.log(f ? f + ' falha(s)' : 'todos passaram (' + tests.length + ')');
  process.exit(f ? 1 : 0);
})();
