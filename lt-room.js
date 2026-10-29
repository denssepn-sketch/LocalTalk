// lt-room.js — núcleo de conexão do Talk Local (Node/Electron).
// Mini-servidor local que só guarda o convite (offer) e a resposta (answer),
// mais descoberta por UDP: quem digita o código de 6 dígitos pergunta na rede
// "quem tem a sala X?" e o anfitrião responde com endereço e porta.
'use strict';
const http = require('http');
const dgram = require('dgram');
const os = require('os');
const crypto = require('crypto');

const HTTP_PORT = 8731;
const DISCOVERY_PORT = 8732;
const TTL_MS = 10 * 60 * 1000;      // sala aceita novos convidados por 10 min
const MAX_BODY = 64 * 1024;          // convite SDP comprimido cabe com folga

const newCode = () => String(crypto.randomInt(0, 1000000)).padStart(6, '0');

function localAddrs() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces()))
    for (const i of list || [])
      if (i.family === 'IPv4' && !i.internal) out.push(i.address);
  return out;
}

class Room {
  constructor({ name = 'Sala', httpPort = HTTP_PORT, discoveryPort = DISCOVERY_PORT, ttl = TTL_MS } = {}) {
    this.name = name;
    this.httpPort = httpPort;
    this.discoveryPort = discoveryPort;
    this.ttl = ttl;
    this.code = newCode();
    this.offer = null;
    this.answer = null;
    this.createdAt = Date.now();
    this.fails = new Map();          // ip -> tentativas erradas (anti força bruta)
    this.onAnswer = () => {};
  }

  expired() { return Date.now() - this.createdAt > this.ttl; }

  // Troca de convite: novo código, limpa resposta anterior.
  setOffer(offer) {
    this.offer = String(offer);
    this.answer = null;
    this.code = newCode();
    this.createdAt = Date.now();
    this.fails.clear();
    return this.code;
  }

  blocked(ip) { return (this.fails.get(ip) || 0) >= 5; }
  fail(ip) { this.fails.set(ip, (this.fails.get(ip) || 0) + 1); }

  start() {
    this.http = http.createServer((req, res) => this._handle(req, res));
    this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.udp.on('message', (msg, rinfo) => {
      const m = String(msg).trim();
      if (!m.startsWith('LT?')) return;
      const code = m.slice(3);
      if (this.expired() || this.blocked(rinfo.address)) return;
      if (code !== this.code) { this.fail(rinfo.address); return; }
      const reply = JSON.stringify({ lt: 1, name: this.name, port: this.httpPort });
      this.udp.send(reply, rinfo.port, rinfo.address);
    });
    return Promise.all([
      new Promise((ok, no) => { this.http.once('error', no); this.http.listen(this.httpPort, '0.0.0.0', ok); }),
      new Promise((ok, no) => { this.udp.once('error', no); this.udp.bind(this.discoveryPort, '0.0.0.0', ok); }),
    ]).then(() => { this.httpPort = this.http.address().port; this.discoveryPort = this.udp.address().port; return this; });
  }

  stop() {
    try { this.http && this.http.close(); } catch (e) {}
    try { this.udp && this.udp.close(); } catch (e) {}
  }

  _handle(req, res) {
    const ip = req.socket.remoteAddress.replace('::ffff:', '');
    const send = (c, o) => { res.writeHead(c, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(o)); };
    const url = new URL(req.url, 'http://x');
    const code = url.searchParams.get('code');
    if (this.expired()) return send(410, { err: 'sala expirada' });
    if (this.blocked(ip)) return send(429, { err: 'muitas tentativas' });
    if (code !== this.code) { this.fail(ip); return send(403, { err: 'código inválido' }); }

    if (req.method === 'GET' && url.pathname === '/offer') {
      if (!this.offer) return send(404, { err: 'sem convite' });
      return send(200, { name: this.name, offer: this.offer });
    }
    if (req.method === 'POST' && url.pathname === '/answer') {
      let body = '', big = false;
      req.on('data', d => { body += d; if (body.length > MAX_BODY) { big = true; req.destroy(); } });
      req.on('end', () => {
        if (big) return;
        if (this.answer) return send(409, { err: 'convite já usado' });
        let j; try { j = JSON.parse(body); } catch (e) { return send(400, { err: 'json inválido' }); }
        if (typeof j.answer !== 'string' || !j.answer.startsWith('LT1:')) return send(400, { err: 'resposta inválida' });
        this.answer = j.answer;
        this.onAnswer(j.answer);
        send(200, { ok: 1 });
      });
      return;
    }
    send(404, { err: 'não encontrado' });
  }
}

// ---- lado de quem entra ----

// Pergunta na rede quem tem a sala com esse código. `targets` permite testar em loopback.
function discover(code, { targets, port = DISCOVERY_PORT, timeout = 2500 } = {}) {
  return new Promise((resolve, reject) => {
    const s = dgram.createSocket('udp4');
    const t = setTimeout(() => { s.close(); reject(new Error('sala não encontrada na rede')); }, timeout);
    s.on('message', (msg, rinfo) => {
      try {
        const j = JSON.parse(String(msg));
        if (j.lt) { clearTimeout(t); s.close(); resolve({ host: rinfo.address, port: j.port, name: j.name }); }
      } catch (e) {}
    });
    s.bind(0, () => {
      s.setBroadcast(true);
      const list = targets || ['255.255.255.255', ...localAddrs().map(a => a.replace(/\.\d+$/, '.255'))];
      list.forEach(h => s.send('LT?' + code, port, h));
    });
  });
}

const get = (host, port, path) => new Promise((ok, no) => {
  http.get({ host, port, path }, r => { let b = ''; r.on('data', d => b += d); r.on('end', () => { let j = {}; try { j = JSON.parse(b); } catch (e) {} r.statusCode === 200 ? ok(j) : no(new Error(j.err || 'erro ' + r.statusCode)); }); }).on('error', no);
});
const post = (host, port, path, obj) => new Promise((ok, no) => {
  const d = JSON.stringify(obj);
  const q = http.request({ host, port, path, method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(d) } }, r => { let b = ''; r.on('data', c => b += c); r.on('end', () => { let j = {}; try { j = JSON.parse(b); } catch (e) {} r.statusCode === 200 ? ok(j) : no(new Error(j.err || 'erro ' + r.statusCode)); }); });
  q.on('error', no); q.end(d);
});

// Passo 1 de quem entra: acha o anfitrião e baixa o convite.
async function fetchOffer(code, opts = {}) {
  const found = opts.host ? { host: opts.host, port: opts.port } : await discover(code, opts);
  const { offer, name } = await get(found.host, found.port, '/offer?code=' + code);
  return { offer, name, host: found.host, port: found.port };
}
// Passo 2: devolve a resposta gerada pela página (WebRTC roda na página, não aqui).
const sendAnswer = (found, code, answer) => post(found.host, found.port, '/answer?code=' + code, { answer });

// Os dois passos juntos (usado nos testes).
async function joinRoom(code, makeAnswer, opts = {}) {
  const f = await fetchOffer(code, opts);
  await sendAnswer(f, code, await makeAnswer(f.offer));
  return { name: f.name, host: f.host };
}

module.exports = { Room, discover, fetchOffer, sendAnswer, joinRoom, localAddrs, newCode, HTTP_PORT, DISCOVERY_PORT };
