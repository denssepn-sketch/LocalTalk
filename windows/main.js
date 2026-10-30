const { app, BrowserWindow, session, ipcMain } = require('electron');
const path = require('path');
const { Room, fetchOffer, sendAnswer } = require('./lt-room');

let win = null;
let room = null;

function createWindow() {
  win = new BrowserWindow({
    width: 420,
    height: 800,
    autoHideMenuBar: true,
    backgroundColor: '#030A3C',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  // O app é uma página só: impede que um link troque a página ou abra outra janela.
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  if (win.webContents.setWindowOpenHandler) {
    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  }
  win.loadFile('index.html');
}

// ---- Sala por código de 6 dígitos (servidor local + descoberta na rede) ----
// Só a janela do app pode chamar; erros voltam como { ok:false, err } para a página mostrar.
function handle(channel, fn) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (!win || event.sender !== win.webContents) return { ok: false, err: 'não permitido' };
    try {
      return { ok: true, v: await fn(...args) };
    } catch (e) {
      return { ok: false, err: String((e && e.message) || e) };
    }
  });
}

function stopRoom() {
  if (room) { room.stop(); room = null; }
}

handle('lt:host', async (name, offer) => {
  if (room && room.name !== name) stopRoom();
  if (!room) {
    const r = new Room({ name });
    r.onAnswer = (answer) => {
      // a resposta de quem entrou volta para a página, que conclui a conexão
      if (win && !win.isDestroyed()) {
        win.webContents.executeJavaScript(
          'window.ltOnAnswer&&window.ltOnAnswer(' + JSON.stringify(answer) + ')'
        ).catch(() => {});
      }
    };
    await r.start();
    room = r;
  }
  return room.setOffer(offer);
});

handle('lt:stop', async () => { stopRoom(); return 1; });

handle('lt:fetch', async (code) => fetchOffer(String(code), { timeout: 2500 }));

handle('lt:answer', async (found, code, answer) => {
  await sendAnswer({ host: String(found.host), port: Number(found.port) }, String(code), String(answer));
  return { ok: 1 };
});

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, permission, cb) => {
    cb(permission === 'media');
  });
  createWindow();
});

app.on('window-all-closed', () => { stopRoom(); app.quit(); });
