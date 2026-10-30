package br.com.localtalk;

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

import java.util.ArrayList;
import java.util.List;

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

    private class Bridge {
        @JavascriptInterface
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

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
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
            return START_NOT_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            stopSelf();
        }

        @Override
        public void onDestroy() {
            releaseLocks();
            super.onDestroy();
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
