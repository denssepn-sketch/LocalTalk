const { app, BrowserWindow, session } = require('electron');

function createWindow() {
  const win = new BrowserWindow({
    width: 420,
    height: 800,
    autoHideMenuBar: true,
    backgroundColor: '#030A3C'
  });
  win.loadFile('index.html');
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, permission, cb) => {
    cb(permission === 'media');
  });
  createWindow();
});

app.on('window-all-closed', () => app.quit());
