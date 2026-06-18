package com.bravetv.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout navBar;
    private Button btnVpn;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ─── Regiones para rotación automática ───────────────────────────────────
    // Se prueban en orden cuando YouTube bloquea
    private static final String[] AUTO_REGIONS = {
        "US", "GB", "DE", "CA", "FR", "NL", "SE", "CH", "AU", "JP"
    };
    private static final String[] AUTO_LABELS = {
        "🇺🇸 EE.UU.", "🇬🇧 UK", "🇩🇪 Alemania", "🇨🇦 Canadá",
        "🇫🇷 Francia", "🇳🇱 Países Bajos", "🇸🇪 Suecia", "🇨🇭 Suiza",
        "🇦🇺 Australia", "🇯🇵 Japón"
    };

    private boolean autoVpnEnabled = true;
    private int regionIndex = 0;          // índice actual en AUTO_REGIONS
    private boolean switchInProgress = false;
    private int switchCount = 0;          // cuántas veces hemos rotado sin éxito
    private static final int MAX_SWITCHES = AUTO_REGIONS.length;

    // ─── Bloqueador de anuncios ───────────────────────────────────────────────
    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        "ad.youtube.com", "ads.youtube.com", "googleadservices.com",
        "googlesyndication.com", "doubleclick.net", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "adservice.google.com.mx",
        "imasdk.googleapis.com", "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com", "www.googleadservices.com",
        "analytics.google.com", "google-analytics.com", "stats.g.doubleclick.net",
        "cm.g.doubleclick.net", "ad.doubleclick.net", "securepubads.g.doubleclick.net",
        "pubads.g.doubleclick.net", "connect.facebook.net", "graph.facebook.com",
        "outbrain.com", "taboola.com", "moatads.com", "amazon-adsystem.com",
        "adsafeprotected.com", "2mdn.net", "adnxs.com"
    ));
    private static final Set<String> AD_URL_PATTERNS = new HashSet<>(Arrays.asList(
        "/pagead/", "/ads/", "/adview", "ad_type=", "doubleclick", "/ptracking",
        "/get_video_info?", "googlevideo.com/videoplayback?ctier=L",
        "youtube.com/api/stats/ads", "youtube.com/pagead", "youtube.com/ptracking"
    ));

    private boolean isAdUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                if (AD_HOSTS.contains(host)) return true;
                for (String adHost : AD_HOSTS)
                    if (host.endsWith("." + adHost) || host.equals(adHost)) return true;
            }
            for (String pattern : AD_URL_PATTERNS)
                if (url.contains(pattern)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    // ─── Región / bypass geográfico ───────────────────────────────────────────
    private String currentRegion() {
        return autoVpnEnabled ? AUTO_REGIONS[regionIndex] : "";
    }

    private String applyRegion(String url) {
        if (!autoVpnEnabled || url == null) return url;
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return url;
        try {
            Uri uri = Uri.parse(url);
            Uri.Builder b = uri.buildUpon();
            b.appendQueryParameter("gl", AUTO_REGIONS[regionIndex]);
            b.appendQueryParameter("hl", "es");
            return b.build().toString();
        } catch (Exception e) { return url; }
    }

    private void applyRegionCookies() {
        if (!autoVpnEnabled) return;
        String gl = AUTO_REGIONS[regionIndex];
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        String pref = "PREF=f6=8&gl=" + gl + "; domain=.youtube.com; path=/";
        cm.setCookie("https://www.youtube.com", pref);
        cm.setCookie("https://youtube.com", pref);
        cm.flush();
    }

    private void updateVpnButton() {
        if (!autoVpnEnabled) {
            btnVpn.setText("🌐 VPN OFF");
            btnVpn.setAlpha(0.5f);
        } else {
            btnVpn.setText(AUTO_LABELS[regionIndex]);
            btnVpn.setAlpha(1.0f);
        }
    }

    /** Llamado desde JS cuando YouTube detecta bloqueo geográfico. */
    private void onGeoBlockDetected(String reason) {
        if (!autoVpnEnabled || switchInProgress) return;
        if (switchCount >= MAX_SWITCHES) {
            // Probamos todas las regiones sin éxito, resetear
            switchCount = 0;
            Toast.makeText(this, "⚠️ Sin regiones disponibles. Reintentando desde EE.UU.", Toast.LENGTH_LONG).show();
            regionIndex = 0;
        }
        switchInProgress = true;
        switchCount++;
        regionIndex = (regionIndex + 1) % AUTO_REGIONS.length;
        String label = AUTO_LABELS[regionIndex];
        Toast.makeText(this, "🔄 Bloqueado → cambiando a " + label, Toast.LENGTH_SHORT).show();
        updateVpnButton();
        applyRegionCookies();
        String url = webView.getUrl();
        String target = (url != null && url.contains("youtube.com"))
            ? applyRegion(url)
            : applyRegion("https://www.youtube.com/tv");
        handler.postDelayed(() -> {
            webView.loadUrl(target);
            switchInProgress = false;
        }, 800);
    }

    /** Interfaz JavaScript → Java para detectar bloqueos desde el DOM. */
    private class BraveTVInterface {
        @JavascriptInterface
        public void onGeoBlock(String reason) {
            handler.post(() -> onGeoBlockDetected(reason));
        }

        @JavascriptInterface
        public void onVideoOk() {
            handler.post(() -> switchCount = 0);   // reset contador al reproducir con éxito
        }
    }

    // ─── Ciclo de vida ────────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        webView     = findViewById(R.id.webView);
        urlBar      = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        statusText  = findViewById(R.id.statusText);
        navBar      = findViewById(R.id.navBar);
        btnVpn      = findViewById(R.id.btnVpn);

        prefs = getSharedPreferences("bravetv", MODE_PRIVATE);
        autoVpnEnabled = prefs.getBoolean("vpn", true);
        regionIndex    = prefs.getInt("regionIdx", 0);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        setupWebView();
        setupButtons();
        updateVpnButton();
        applyRegionCookies();
        loadUrl(applyRegion("https://www.youtube.com/tv"));
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) " +
            "AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1");

        // Registrar interfaz JS↔Java
        webView.addJavascriptInterface(new BraveTVInterface(), "BraveTV");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Bloquear anuncios
                if (isAdUrl(url)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                        new java.io.ByteArrayInputStream("".getBytes()));
                }
                // Detectar 403 en requests de video (bloqueo geográfico a nivel red)
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req,
                                            WebResourceResponse resp) {
                super.onReceivedHttpError(view, req, resp);
                if (resp != null && resp.getStatusCode() == 403) {
                    String url = req.getUrl().toString();
                    // 403 en recurso principal de YouTube = bloqueo geo
                    if (url.contains("youtube.com") && !isAdUrl(url)) {
                        onGeoBlockDetected("http_403");
                    }
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                urlBar.setText(url);

                // 1. CSS — ocultar elementos de anuncios
                view.evaluateJavascript(
                    "(function(){" +
                    "var s=document.createElement('style');" +
                    "s.innerHTML='ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer," +
                    "ytd-promoted-video-renderer,ytd-search-pyv-renderer,.ytp-ad-module," +
                    ".ytp-ad-overlay-container,.ytp-ad-text-overlay,.video-ads," +
                    "#player-ads,ytm-promoted-sparkles-web-renderer{display:none!important}';" +
                    "document.head.appendChild(s);})()", null);

                // 2. Auto-skip de anuncios
                view.evaluateJavascript(
                    "setInterval(function(){" +
                    "var sk=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');" +
                    "if(sk)sk.click();" +
                    "if(document.querySelector('.ad-showing')){" +
                    "  var v=document.querySelector('video');if(v)v.currentTime=v.duration;}" +
                    "},300);", null);

                // 3. Inyectar cookie de región vía JS
                if (autoVpnEnabled) {
                    String gl = AUTO_REGIONS[regionIndex];
                    view.evaluateJavascript(
                        "document.cookie='PREF=f6=8&gl=" + gl + "; domain=.youtube.com; path=/';", null);
                }

                // 4. Detector de bloqueo geográfico — inspecciona el DOM cada 3s
                view.evaluateJavascript(
                    "(function startDetector(){" +
                    "  var keywords=['country','región','region','not available'," +
                    "                'tu país','disponible','restricted','blocked'];" +
                    "  var selectors=[" +
                    "    '.ytp-error-content-wrap-subreason'," +
                    "    '.ytp-error-content-wrap-reason'," +
                    "    '#error-screen yt-formatted-string'," +
                    "    'yt-formatted-string.ytd-background-promo-renderer'," +
                    "    '#player .ytp-error-content'" +
                    "  ];" +
                    "  function check(){" +
                    "    for(var i=0;i<selectors.length;i++){" +
                    "      var el=document.querySelector(selectors[i]);" +
                    "      if(el){" +
                    "        var t=el.textContent.toLowerCase();" +
                    "        for(var j=0;j<keywords.length;j++){" +
                    "          if(t.indexOf(keywords[j])>=0){" +
                    "            if(typeof BraveTV!=='undefined') BraveTV.onGeoBlock(t.substring(0,80));" +
                    "            return;" +
                    "          }" +
                    "        }" +
                    "      }" +
                    "    }" +
                    // Detectar video reproduciéndose con éxito
                    "    var v=document.querySelector('video');" +
                    "    if(v&&v.currentTime>3&&!v.paused){" +
                    "      if(typeof BraveTV!=='undefined') BraveTV.onVideoOk();" +
                    "    }" +
                    "  }" +
                    "  setInterval(check, 3000);" +
                    "})()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                view.loadUrl(applyRegion(url));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) { progressBar.setProgress(p); }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                navBar.setVisibility(View.GONE);
                webView.setVisibility(View.GONE);
                LinearLayout c = findViewById(R.id.fullscreenContainer);
                c.addView(view);
                c.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                LinearLayout c = findViewById(R.id.fullscreenContainer);
                c.removeAllViews();
                c.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                navBar.setVisibility(View.VISIBLE);
            }
        });

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
    }

    /** Abre un diálogo para buscar directamente en YouTube. */
    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint("Buscar en YouTube...");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle("🔍 Buscar en YouTube")
            .setView(input)
            .setPositiveButton("Buscar", (d, w) -> doYoutubeSearch(input.getText().toString()))
            .setNegativeButton("Cancelar", null)
            .create();

        input.setOnEditorActionListener((v, actionId, event) -> {
            doYoutubeSearch(input.getText().toString());
            dialog.dismiss();
            return true;
        });

        dialog.show();
        input.requestFocus();
    }

    private void doYoutubeSearch(String query) {
        query = query == null ? "" : query.trim();
        if (query.isEmpty()) return;
        String url = "https://www.youtube.com/results?search_query=" + Uri.encode(query);
        loadUrl(applyRegion(url));
    }

    private void setupButtons() {
        findViewById(R.id.btnYoutube).setOnClickListener(v ->
            loadUrl(applyRegion("https://www.youtube.com/tv")));
        findViewById(R.id.btnSearch).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());

        // Botón VPN: ON/OFF toggle
        btnVpn.setOnClickListener(v -> {
            autoVpnEnabled = !autoVpnEnabled;
            prefs.edit().putBoolean("vpn", autoVpnEnabled).apply();
            updateVpnButton();
            applyRegionCookies();
            String msg = autoVpnEnabled ? "🟢 VPN AUTO activado" : "🔴 VPN AUTO desactivado";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            webView.reload();
        });

        // Mantener pulsado el botón VPN → selector manual de región
        btnVpn.setOnLongClickListener(v -> {
            if (!autoVpnEnabled) return true;
            String[] labels = AUTO_LABELS;
            new android.app.AlertDialog.Builder(this)
                .setTitle("Elegir región manualmente")
                .setSingleChoiceItems(labels, regionIndex, (d, which) -> {
                    regionIndex = which;
                    switchCount = 0;
                    prefs.edit().putInt("regionIdx", regionIndex).apply();
                    updateVpnButton();
                    applyRegionCookies();
                    loadUrl(applyRegion("https://www.youtube.com/tv"));
                    d.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
            return true;
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            String url = urlBar.getText().toString().trim();
            if (!url.startsWith("http")) url = "https://" + url;
            loadUrl(url);
            return true;
        });
    }

    private void loadUrl(String url) {
        webView.loadUrl(url);
        urlBar.setText(url);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            showSearchDialog();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause()   { super.onPause();   webView.onPause();  prefs.edit().putInt("regionIdx", regionIndex).apply(); }
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); webView.destroy();  }
}
