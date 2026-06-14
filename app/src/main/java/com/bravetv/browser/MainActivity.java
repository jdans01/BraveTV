package com.bravetv.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;

import java.util.*;

public class MainActivity extends Activity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout navBar;
    private Button btnRegion;
    private SharedPreferences prefs;

    // ─── Regiones disponibles ─────────────────────────────────────────────────
    private static final String[][] REGIONS = {
        {"AUTO",  "🌐 Auto"},
        {"US",    "🇺🇸 EE.UU."},
        {"MX",    "🇲🇽 México"},
        {"ES",    "🇪🇸 España"},
        {"AR",    "🇦🇷 Argentina"},
        {"CO",    "🇨🇴 Colombia"},
        {"GB",    "🇬🇧 UK"},
        {"DE",    "🇩🇪 Alemania"},
        {"FR",    "🇫🇷 Francia"},
        {"JP",    "🇯🇵 Japón"},
        {"CA",    "🇨🇦 Canadá"},
        {"BR",    "🇧🇷 Brasil"},
    };

    private String currentRegion = "AUTO";

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
        } catch (Exception e) { }
        return false;
    }

    // ─── Región / bypass geográfico ───────────────────────────────────────────
    /** Agrega parámetros gl= y hl= a URLs de YouTube para cambiar la región. */
    private String applyRegion(String url) {
        if (currentRegion.equals("AUTO") || url == null) return url;
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return url;
        try {
            Uri uri = Uri.parse(url);
            Uri.Builder b = uri.buildUpon();
            // Reemplaza o añade gl= y hl=
            b.appendQueryParameter("gl", currentRegion);
            b.appendQueryParameter("hl", "es");
            return b.build().toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** Inyecta cookies de preferencia de región en YouTube. */
    private void applyRegionCookies(String baseUrl) {
        if (currentRegion.equals("AUTO")) return;
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        // PREF: f6=8 = región manual, gl= código de país
        String pref = "PREF=f6=8&gl=" + currentRegion + "; domain=.youtube.com; path=/";
        cm.setCookie("https://www.youtube.com", pref);
        cm.setCookie("https://youtube.com", pref);
        cm.flush();
    }

    private String getRegionLabel(String code) {
        for (String[] r : REGIONS)
            if (r[0].equals(code)) return r[1];
        return "🌐";
    }

    private void showRegionDialog() {
        String[] labels = new String[REGIONS.length];
        for (int i = 0; i < REGIONS.length; i++) labels[i] = REGIONS[i][1];

        int current = 0;
        for (int i = 0; i < REGIONS.length; i++)
            if (REGIONS[i][0].equals(currentRegion)) { current = i; break; }

        new AlertDialog.Builder(this)
            .setTitle("Región / País de YouTube")
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                currentRegion = REGIONS[which][0];
                prefs.edit().putString("region", currentRegion).apply();
                btnRegion.setText(REGIONS[which][1]);
                applyRegionCookies("https://www.youtube.com");
                // Recarga YouTube con la nueva región
                String url = webView.getUrl();
                if (url != null && url.contains("youtube.com")) {
                    webView.loadUrl(applyRegion("https://www.youtube.com/tv"));
                }
                dialog.dismiss();
            })
            .setNegativeButton("Cancelar", null)
            .show();
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
        btnRegion   = findViewById(R.id.btnRegion);

        prefs = getSharedPreferences("bravetv", MODE_PRIVATE);
        currentRegion = prefs.getString("region", "AUTO");
        btnRegion.setText(getRegionLabel(currentRegion));

        // Cookies habilitadas
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        setupWebView();
        setupButtons();
        applyRegionCookies("https://www.youtube.com");
        loadUrl(applyRegion("https://www.youtube.com/tv"));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) " +
            "AppleWebKit/538.1 (KHTML, like Gecko) " +
            "Version/6.0 TV Safari/538.1"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isAdUrl(url)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                        new java.io.ByteArrayInputStream("".getBytes()));
                }
                return super.shouldInterceptRequest(view, request);
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

                // CSS: ocultar elementos de anuncios
                String css =
                    "ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer," +
                    "ytd-promoted-video-renderer,ytd-search-pyv-renderer," +
                    ".ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-text-overlay," +
                    ".video-ads,#player-ads,ytm-promoted-sparkles-web-renderer" +
                    "{ display: none !important; }";
                view.evaluateJavascript(
                    "(function(){var s=document.createElement('style');" +
                    "s.innerHTML='" + css + "';document.head.appendChild(s);})()", null);

                // Auto-skip de anuncios
                view.evaluateJavascript(
                    "setInterval(function(){" +
                    "  var skip=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');" +
                    "  if(skip)skip.click();" +
                    "  if(document.querySelector('.ad-showing')){" +
                    "    var v=document.querySelector('video');" +
                    "    if(v)v.currentTime=v.duration;" +
                    "  }" +
                    "},300);", null);

                // Forzar región vía JS si está seleccionada
                if (!currentRegion.equals("AUTO")) {
                    view.evaluateJavascript(
                        "(function(){" +
                        "  var gl='" + currentRegion + "';" +
                        "  document.cookie='PREF=f6=8&gl='+gl+'; domain=.youtube.com; path=/';" +
                        "})()", null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                view.loadUrl(applyRegion(url));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                navBar.setVisibility(View.GONE);
                webView.setVisibility(View.GONE);
                LinearLayout container = findViewById(R.id.fullscreenContainer);
                container.addView(view);
                container.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                LinearLayout container = findViewById(R.id.fullscreenContainer);
                container.removeAllViews();
                container.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                navBar.setVisibility(View.VISIBLE);
            }
        });

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
    }

    private void setupButtons() {
        findViewById(R.id.btnYoutube).setOnClickListener(v ->
            loadUrl(applyRegion("https://www.youtube.com/tv")));
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());
        btnRegion.setOnClickListener(v -> showRegionDialog());
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
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); webView.destroy(); }
}
