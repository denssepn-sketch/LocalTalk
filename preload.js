// Ponte entre a página (index.html) e o programa do Windows. Só expõe a sala por código.
const { contextBridge, ipcRenderer } = require('electron');

async function call(channel, ...args) {
  const r = await ipcRenderer.invoke(channel, ...args);
  if (!r || !r.ok) throw new Error((r && r.err) || 'erro');
  return r.v;
}

contextBridge.exposeInMainWorld('LTNative', {
  host: (name, offer) => call('lt:host', String(name), String(offer)),
  stop: () => { ipcRenderer.invoke('lt:stop').catch(() => {}); },
  fetchOffer: (code) => call('lt:fetch', String(code)),
  sendAnswer: (found, code, answer) =>
    call('lt:answer', { host: String(found.host), port: Number(found.port) }, String(code), String(answer))
});
