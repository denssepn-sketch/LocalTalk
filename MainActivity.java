package br.com.localtalk;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.KeyEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 1;
    private static final int REQ_LOCATION = 2;
    private static final String NOTIF_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final String APP_URL = "file:///android_asset/index.html";

    private WebView webView;

    // Pedido de localização da página que está esperando a resposta do Android.
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    // A página pediu para manter o app vivo, mas o Android ainda não liberou o microfone.
    private boolean wantKeepAlive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> ask = new ArrayList<String>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ask.add(Manifest.permission.RECORD_AUDIO);
            }
            // Android 13+: sem esta permissão a notificação do serviço fica escondida (o serviço funciona igual).
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(NOTIF_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                ask.add(NOTIF_PERMISSION);
            }
            if (!ask.isEmpty()) {
                requestPermissions(ask.toArray(new String[0]), REQ_AUDIO);
            }
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);

        // O app só precisa dos próprios arquivos em file:///android_asset (isso não depende de setAllowFileAccess).
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        webView.setWebViewClient(new WebViewClient() {
            // Impede que um link leve o WebView para fora do app (isso derrubaria as conexões).
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // Microfone: concede só o áudio. Câmera, MIDI e o resto são negados.
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        List<String> allowed = new ArrayList<String>();
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                                allowed.add(r);
                            }
                        }
                        if (allowed.isEmpty()) {
                            request.deny();
                        } else {
                            request.grant(allowed.toArray(new String[0]));
                        }
                    }
                });
            }

            // Localização: só quando a página pede (aba Mapa) e só para o próprio app.
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                if (origin == null || !origin.startsWith("file:")) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                if (pendingGeoCallback != null) {
                    pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
                }
                pendingGeoCallback = callback;
                pendingGeoOrigin = origin;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        });

        // A página (modo segundo plano) liga e desliga o serviço por aqui.
        webView.addJavascriptInterface(new Bridge(), "LTAndroid");

        webView.loadUrl(APP_URL);
    }
    private volatile boolean volKeysOn = false;
static volatile MainActivity instance;

static void tap(String k) {
    MainActivity a = instance;
    if (a != null) a.js("window.ltKeyTap&&window.ltKeyTap('" + k + "')");
}
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (volKeysOn && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getRepeatCount() == 0) {
                js("window.ltKey&&window.ltKey('" + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down") + "',true)");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (volKeysOn && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            js("window.ltKey&&window.ltKey('" + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down") + "',false)");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    // ---- Sala por código de 6 dígitos (o servidor e a descoberta ficam no RoomCore, abaixo) ----
    private RoomCore room;
    private WifiManager.MulticastLock multicast;

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(code, null);
            }
        });
    }

    private void done(String id, boolean ok, String payload) {
        js("window.ltNativeDone(" + RoomCore.quote(id) + "," + ok + "," + RoomCore.quote(payload) + ")");
    }

    private synchronized String hostRoom(String name, String offer) throws Exception {
        if (room != null && !room.name.equals(name)) stopRoom();
        if (room == null) {
            RoomCore r = new RoomCore(name, RoomCore.HTTP_PORT, RoomCore.DISCOVERY_PORT, RoomCore.TTL_MS);
            r.listener = new RoomCore.AnswerListener() {
                @Override
                public void onAnswer(String answer) {
                    js("window.ltOnAnswer&&window.ltOnAnswer(" + RoomCore.quote(answer) + ")");
                }
            };
            r.start();
            room = r;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                multicast = wm.createMulticastLock("localtalk:room");
                multicast.setReferenceCounted(false);
                multicast.acquire(); // sem isso muitos aparelhos descartam o broadcast de descoberta
            } catch (RuntimeException e) {
                // a sala ainda funciona por IP direto
            }
        }
        return room.setOffer(offer);
    }

    private synchronized void stopRoom() {
        if (room != null) {
            room.stop();
            room = null;
        }
        try {
            if (multicast != null && multicast.isHeld()) multicast.release();
        } catch (RuntimeException e) {
            // nada a fazer
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void roomHost(final String id, final String name, final String offer) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        done(id, true, RoomCore.quote(hostRoom(name, offer)));
                    } catch (Exception e) {
                        done(id, false, String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void roomStop() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    stopRoom();
                }
            }).start();
        }

        @JavascriptInterface
        public void roomFetch(final String id, final String code) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        RoomCore.Found f = RoomCore.fetchOffer(code, null, 0, null, 0, 2500);
                        done(id, true, "{\"offer\":" + RoomCore.quote(f.offer) + ",\"name\":"
                                + RoomCore.quote(f.name) + ",\"host\":" + RoomCore.quote(f.host)
                                + ",\"port\":" + f.port + "}");
                    } catch (Exception e) {
                        done(id, false, String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void roomAnswer(final String id, final String host, final String port,
                final String code, final String answer) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        RoomCore.sendAnswer(host, Integer.parseInt(port), code, answer);
                        done(id, true, "{\"ok\":1}");
                    } catch (Exception e) {
                        done(id, false, String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

                @JavascriptInterface
        public void volumeKeys(final boolean on) {
            volKeysOn = on;
        }@JavascriptInterface
        public void keepAlive(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setKeepAlive(on);
                }
            });
        }
    }

    private void setKeepAlive(boolean on) {
        Intent svc = new Intent(this, KeepAliveService.class);
        if (!on) {
            wantKeepAlive = false;
            stopService(svc);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            wantKeepAlive = true; // começa assim que o usuário liberar o microfone
            return;
        }
        wantKeepAlive = false;
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (RuntimeException e) {
            // O Android recusou iniciar o serviço agora; o app continua funcionando sem ele.
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && wantKeepAlive) {
            setKeepAlive(true);
        }
        if (requestCode == REQ_LOCATION && pendingGeoCallback != null) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) granted = true;
            }
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopRoom();
        if (instance == this) instance = null;
        stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    /**
     * Serviço em primeiro plano: mantém o app vivo com a tela bloqueada
     * (notificação fixa, CPU e Wi-Fi acordados). Fica dentro do MainActivity.java
     * para o build atual compilar sem mudanças.
     */
    public static class KeepAliveService extends Service {
        private static final int NOTIF_ID = 1;
        private static final String CHANNEL_ID = "localtalk_keepalive";

        private PowerManager.WakeLock wake;
        private WifiManager.WifiLock wifi;
              private PowerManager.WakeLock wake;
        private WifiManager.WifiLock wifi;
        private MediaSession session;

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && "br.com.localtalk.PTT".equals(intent.getAction())) {
                MainActivity.tap("hs");
                return START_NOT_STICKY;
            }
            Notification n = buildNotification();
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIF_ID, n);
                }
            } catch (RuntimeException e) {
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks();
            startSession();
            return START_NOT_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            stopSelf();
        }

        @Override
        public void onDestroy() {
            stopSession();
            releaseLocks();
            super.onDestroy();
        }

        private void startSession() {
            if (Build.VERSION.SDK_INT < 21 || session != null) return;
            try {
                session = new MediaSession(this, "LocalTalk");
                session.setCallback(new MediaSession.Callback() {
                    @Override
                    public boolean onMediaButtonEvent(Intent i) {
                        KeyEvent ke = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                        if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN
                                && ke.getRepeatCount() == 0) {
                            MainActivity.tap("hs");
                        }
                        return true;
                    }
                });
                session.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build());
                session.setActive(true);
            } catch (RuntimeException e) {
                session = null;
            }
        }

        private void stopSession() {
            if (session == null) return;
            try {
                session.setActive(false);
                session.release();
            } catch (RuntimeException e) {
                // nada a fazer
            }
            session = null;
        }

        private Notification buildNotification() {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Talk Local ativo", NotificationManager.IMPORTANCE_LOW));
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setContentTitle("Talk Local ativo")
                    .setContentText("Mantendo a conexão e o áudio com a tela bloqueada")
                    .setSmallIcon(android.R.drawable.stat_sys_headset)
                    .setContentIntent(pi)
                    .setOngoing(true);
            Intent ptt = new Intent(this, KeepAliveService.class).setAction("br.com.localtalk.PTT");
int f2 = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
PendingIntent pp = PendingIntent.getService(this, 1, ptt, f2);
b.addAction(android.R.drawable.ic_btn_speak_now, "Falar / parar", pp);
if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);
            return b.build();
        }

        private void acquireLocks() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (wake == null) {
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localtalk:keepalive");
                    wake.setReferenceCounted(false);
                }
                if (!wake.isHeld()) wake.acquire();

                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) {
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "localtalk:wifi");
                    wifi.setReferenceCounted(false);
                }
                if (!wifi.isHeld()) wifi.acquire();
            } catch (RuntimeException e) {
                // Sem os travamentos o serviço ainda segura o app em primeiro plano.
            }
        }

        private void releaseLocks() {
            try {
                if (wake != null && wake.isHeld()) wake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
            } catch (RuntimeException e) {
                // nada a fazer
            }
        }
    }
}

/**
 * Núcleo da sala por código (igual ao lt-room.js do Windows, mesmo protocolo):
 * UDP 8732 responde "LT?<código>" com o endereço da sala; HTTP 8731 entrega o
 * convite (GET /offer) e recebe a resposta (POST /answer). Só usa classes do Java.
 */
class RoomCore {
    interface AnswerListener {
        void onAnswer(String answer);
    }

    static class Found {
        String host;
        int port;
        String name;
        String offer;
    }

    static final int HTTP_PORT = 8731;
    static final int DISCOVERY_PORT = 8732;
    static final long TTL_MS = 10L * 60L * 1000L;
    static final int MAX_BODY = 64 * 1024;
    private static final int MAX_FAILS = 5;

    final String name;
    volatile AnswerListener listener;
    int httpPort;
    int discoveryPort;

    private final long ttl;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Integer> fails = new HashMap<String, Integer>();
    private String code;
    private String offer;
    private String answer;
    private long createdAt;
    private volatile boolean running;
    private ServerSocket server;
    private DatagramSocket udp;

    RoomCore(String name, int httpPort, int discoveryPort, long ttlMs) {
        this.name = name;
        this.httpPort = httpPort;
        this.discoveryPort = discoveryPort;
        this.ttl = ttlMs;
        setOffer(null);
    }

    synchronized String setOffer(String o) {
        offer = o;
        answer = null;
        code = String.format(Locale.ROOT, "%06d", rnd.nextInt(1000000));
        createdAt = System.currentTimeMillis();
        fails.clear();
        return code;
    }

    synchronized String getAnswer() {
        return answer;
    }

    private synchronized boolean expired() {
        return System.currentTimeMillis() - createdAt > ttl;
    }

    private synchronized boolean blocked(String ip) {
        Integer n = fails.get(ip);
        return n != null && n >= MAX_FAILS;
    }

    private synchronized void fail(String ip) {
        Integer n = fails.get(ip);
        fails.put(ip, n == null ? 1 : n + 1);
    }

    private synchronized boolean codeMatches(String c) {
        return c != null && c.equals(code);
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(httpPort));
        } catch (IOException e) {
            server.close();
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(0)); // porta ocupada: usa outra (vai no aviso de descoberta)
        }
        httpPort = server.getLocalPort();
        udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        udp.bind(new InetSocketAddress(discoveryPort));
        discoveryPort = udp.getLocalPort();
        running = true;
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        t1.setDaemon(true);
        t1.start();
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                udpLoop();
            }
        });
        t2.setDaemon(true);
        t2.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException e) {
            // ignora
        }
        if (udp != null) udp.close();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void udpLoop() {
        byte[] buf = new byte[256];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                if (!msg.startsWith("LT?")) continue;
                String ip = cleanIp(p.getAddress());
                if (expired() || blocked(ip)) continue;
                if (!codeMatches(msg.substring(3))) {
                    fail(ip);
                    continue;
                }
                byte[] r = ("{\"lt\":1,\"name\":" + quote(name) + ",\"port\":" + httpPort + "}")
                        .getBytes("UTF-8");
                udp.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private static String cleanIp(InetAddress a) {
        String s = a.getHostAddress();
        int i = s.indexOf('%');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String ip = cleanIp(s.getInetAddress());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            int len = 0;
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        len = Integer.parseInt(h.substring(15).trim());
                    } catch (NumberFormatException e) {
                        len = -1;
                    }
                }
            }
            String path = parts[1];
            String query = "";
            int qi = path.indexOf('?');
            if (qi >= 0) {
                query = path.substring(qi + 1);
                path = path.substring(0, qi);
            }
            String c = param(query, "code");
            if (expired()) {
                send(out, 410, "{\"err\":\"sala expirada\"}");
            } else if (blocked(ip)) {
                send(out, 429, "{\"err\":\"muitas tentativas\"}");
            } else if (!codeMatches(c)) {
                fail(ip);
                send(out, 403, "{\"err\":\"código inválido\"}");
            } else if (parts[0].equals("GET") && path.equals("/offer")) {
                String o;
                synchronized (this) {
                    o = offer;
                }
                if (o == null) send(out, 404, "{\"err\":\"sem convite\"}");
                else send(out, 200, "{\"name\":" + quote(name) + ",\"offer\":" + quote(o) + "}");
            } else if (parts[0].equals("POST") && path.equals("/answer")) {
                if (len < 0 || len > MAX_BODY) return; // corpo grande demais: derruba a conexão
                byte[] body = new byte[len];
                int got = 0;
                while (got < len) {
                    int n = in.read(body, got, len - got);
                    if (n < 0) return;
                    got += n;
                }
                String a = jsonString(new String(body, "UTF-8"), "answer");
                if (a == null || !a.startsWith("LT1:")) {
                    send(out, 400, "{\"err\":\"resposta inválida\"}");
                    return;
                }
                boolean used;
                synchronized (this) {
                    used = answer != null;
                    if (!used) answer = a;
                }
                if (used) {
                    send(out, 409, "{\"err\":\"convite já usado\"}");
                } else {
                    AnswerListener l = listener;
                    if (l != null) l.onAnswer(a);
                    send(out, 200, "{\"ok\":1}");
                }
            } else {
                send(out, 404, "{\"err\":\"não encontrado\"}");
            }
        } catch (IOException e) {
            // conexão caiu: nada a fazer
        } finally {
            try {
                s.close();
            } catch (IOException e) {
                // ignora
            }
        }
    }

    private static void send(OutputStream out, int status, String json) throws IOException {
        byte[] b = json.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\n"
                + "Cache-Control: no-store\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("linha longa demais");
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
        }
        return null;
    }

    // ---- JSON mínimo (sem depender de bibliotecas, para testar fora do Android) ----

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                default:
                    if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    /** Lê o valor texto de uma chave de um objeto JSON simples; null se não achar. */
    static String jsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int i = json.indexOf(':', k);
        if (i < 0) return null;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;
        StringBuilder b = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i++);
            if (ch == '"') return b.toString();
            if (ch == '\\' && i < json.length()) {
                char e = json.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > json.length()) return null;
                        try {
                            b.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(ch);
            }
        }
        return null;
    }

    // ---- lado de quem entra ----

    static List<String> broadcastTargets() {
        List<String> l = new ArrayList<String>();
        l.add("255.255.255.255");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !l.contains(b.getHostAddress())) l.add(b.getHostAddress());
                }
            }
        } catch (IOException e) {
            // fica só com o broadcast geral
        }
        return l;
    }

    static Found discover(String code, List<String> targets, int port, int timeoutMs) throws IOException {
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            byte[] req = ("LT?" + code).getBytes("UTF-8");
            for (String t : (targets != null ? targets : broadcastTargets())) {
                try {
                    s.send(new DatagramPacket(req, req.length, InetAddress.getByName(t), port));
                } catch (IOException e) {
                    // interface sem rota: tenta as outras
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IOException("sala não encontrada na rede");
                s.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    throw new IOException("sala não encontrada na rede");
                }
                String m = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (!m.contains("\"lt\"")) continue;
                int pi = m.indexOf("\"port\":");
                if (pi < 0) continue;
                int j = pi + 7, e2 = j;
                while (e2 < m.length() && Character.isDigit(m.charAt(e2))) e2++;
                Found f = new Found();
                f.host = cleanIp(p.getAddress());
                f.port = Integer.parseInt(m.substring(j, e2));
                f.name = jsonString(m, "name");
                return f;
            }
        } finally {
            s.close();
        }
    }

    private static String http(String method, String host, int port, String path, String body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod(method);
            if (body != null) {
                byte[] b = body.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                c.getOutputStream().write(b);
            }
            int status = c.getResponseCode();
            InputStream is = status == 200 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] tmp = new byte[4096];
                int n;
                while ((n = is.read(tmp)) > 0) bo.write(tmp, 0, n);
            }
            String text = bo.toString("UTF-8");
            if (status != 200) {
                String err = jsonString(text, "err");
                throw new IOException(err != null ? err : "erro " + status);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    /** Acha a sala (por host/porta, ou por broadcast se host for null) e baixa o convite. */
    static Found fetchOffer(String code, String host, int port, List<String> targets, int discPort,
            int timeoutMs) throws IOException {
        Found f;
        if (host != null) {
            f = new Found();
            f.host = host;
            f.port = port;
        } else {
            f = discover(code, targets, discPort > 0 ? discPort : DISCOVERY_PORT, timeoutMs);
        }
        String r = http("GET", f.host, f.port, "/offer?code=" + code, null);
        f.offer = jsonString(r, "offer");
        String n = jsonString(r, "name");
        if (n != null) f.name = n;
        if (f.offer == null) throw new IOException("convite inválido");
        return f;
    }

    static void sendAnswer(String host, int port, String code, String answer) throws IOException {
        http("POST", host, port, "/answer?code=" + code, "{\"answer\":" + quote(answer) + "}");
    }
}
