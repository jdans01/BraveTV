package com.bravetv.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class MainActivity extends Activity {

    private static final int MODE_BRAVE   = 0;
    private static final int MODE_PRIVATE = 1;
    private static final int MODE_YT_TV   = 2;
    private static final int MODE_YT_WEB  = 3;

    private int currentMode = MODE_BRAVE;

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout navBar;
    private Button btnVpn;
    private ScrollView homeScreen;
    private TextView homeVpnStatus;
    private LinearLayout recentsList;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean navBarHidden = false;

    // ─── Regiones ─────────────────────────────────────────────────────────────
    private static final String[] AUTO_REGIONS = {
        "US","GB","DE","CA","FR","NL","SE","CH","AU","JP"
    };
    private static final String[] AUTO_LABELS = {
        "🇺🇸 EE.UU.","🇬🇧 UK","🇩🇪 Alemania","🇨🇦 Canadá",
        "🇫🇷 Francia","🇳🇱 Países Bajos","🇸🇪 Suecia","🇨🇭 Suiza",
        "🇦🇺 Australia","🇯🇵 Japón"
    };

    private boolean autoVpnEnabled = true;
    private int regionIndex = 0;
    private boolean switchInProgress = false;
    private int switchCount = 0;
    private static final int MAX_SWITCHES = AUTO_REGIONS.length;

    // ─── Ad block ─────────────────────────────────────────────────────────────
    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        "ad.youtube.com","ads.youtube.com","googleadservices.com",
        "googlesyndication.com","doubleclick.net","googletagmanager.com",
        "googletagservices.com","adservice.google.com","adservice.google.com.mx",
        "imasdk.googleapis.com","pagead2.googlesyndication.com",
        "tpc.googlesyndication.com","www.googleadservices.com",
        "analytics.google.com","google-analytics.com","stats.g.doubleclick.net",
        "cm.g.doubleclick.net","ad.doubleclick.net","securepubads.g.doubleclick.net",
        "pubads.g.doubleclick.net","connect.facebook.net","graph.facebook.com",
        "outbrain.com","taboola.com","moatads.com","amazon-adsystem.com",
        "adsafeprotected.com","2mdn.net","adnxs.com"
    ));
    private static final Set<String> AD_URL_PATTERNS = new HashSet<>(Arrays.asList(
        "/pagead/","/ads/","/adview","ad_type=","doubleclick","/ptracking",
        "/get_video_info?","googlevideo.com/videoplayback?ctier=L",
        "youtube.com/api/stats/ads","youtube.com/pagead","youtube.com/ptracking"
    ));

    private boolean isAdUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                if (AD_HOSTS.contains(host)) return true;
                for (String h : AD_HOSTS)
                    if (host.endsWith("." + h) || host.equals(h)) return true;
            }
            for (String p : AD_URL_PATTERNS) if (url.contains(p)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ─── Región ───────────────────────────────────────────────────────────────
    private String applyRegion(String url) {
        if (!autoVpnEnabled || url == null) return url;
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return url;
        try {
            Uri.Builder b = Uri.parse(url).buildUpon();
            b.appendQueryParameter("gl", AUTO_REGIONS[regionIndex]);
            b.appendQueryParameter("hl", "es");
            return b.build().toString();
        } catch (Exception e) { return url; }
    }

    private void applyRegionCookies() {
        if (!autoVpnEnabled || currentMode == MODE_PRIVATE) return;
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
        updateHomeVpnStatus();
    }

    private void updateHomeVpnStatus() {
        if (homeVpnStatus == null) return;
        if (!autoVpnEnabled) {
            homeVpnStatus.setText("🌐 VPN AUTO desactivado");
            homeVpnStatus.setTextColor(0xFFAAAAAA);
        } else {
            homeVpnStatus.setText("🌐 VPN AUTO · " + AUTO_LABELS[regionIndex]);
            homeVpnStatus.setTextColor(0xFF2ECC71);
        }
    }

    private void onGeoBlockDetected(String reason) {
        if (!autoVpnEnabled || switchInProgress) return;
        if (switchCount >= MAX_SWITCHES) {
            switchCount = 0;
            Toast.makeText(this, "⚠️ Sin regiones disponibles. Reintentando desde EE.UU.", Toast.LENGTH_LONG).show();
            regionIndex = 0;
        }
        switchInProgress = true;
        switchCount++;
        regionIndex = (regionIndex + 1) % AUTO_REGIONS.length;
        Toast.makeText(this, "🔄 Bloqueado → cambiando a " + AUTO_LABELS[regionIndex], Toast.LENGTH_SHORT).show();
        updateVpnButton();
        applyRegionCookies();
        String url = webView.getUrl();
        String target = (url != null && url.contains("youtube.com"))
            ? applyRegion(url) : applyRegion("https://www.youtube.com/tv");
        handler.postDelayed(() -> { webView.loadUrl(target); switchInProgress = false; }, 800);
    }

    // ─── JS Interface ─────────────────────────────────────────────────────────
    private class BraveTVInterface {
        @JavascriptInterface
        public void onGeoBlock(String reason) {
            handler.post(() -> onGeoBlockDetected(reason));
        }
        @JavascriptInterface
        public void onVideoOk() {
            handler.post(() -> switchCount = 0);
        }
        @JavascriptInterface
        public void onFullscreen(boolean full) {
            handler.post(() -> setNavBarHidden(full));
        }
    }

    // ─── Navbar auto-hide ─────────────────────────────────────────────────────
    private void setNavBarHidden(boolean hide) {
        if (hide == navBarHidden) return;
        navBarHidden = hide;
        if (hide) {
            navBar.animate().translationY(-navBar.getHeight()).setDuration(300).start();
        } else {
            navBar.animate().translationY(0).setDuration(300).start();
        }
    }

    // ─── Recent history ───────────────────────────────────────────────────────
    private static final int MAX_RECENTS = 8;
    private static final String PREF_RECENTS = "recents_json";

    private void saveRecent(String url, String title) {
        if (url == null || title == null || title.isEmpty()) return;
        // Skip generic YouTube home pages
        String lower = title.toLowerCase();
        if (lower.equals("youtube") || url.matches(".*youtube\\.com/(tv)?\\??$")
                || url.matches(".*youtube\\.com/\\?.*") || title.length() < 3) return;

        try {
            String json = prefs.getString(PREF_RECENTS, "[]");
            JSONArray arr = new JSONArray(json);
            // Remove duplicate url
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("url").equals(url)) filtered.put(o);
            }
            // Prepend new entry
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("title", title);
            JSONArray result = new JSONArray();
            result.put(entry);
            for (int i = 0; i < filtered.length() && i < MAX_RECENTS - 1; i++)
                result.put(filtered.get(i));
            prefs.edit().putString(PREF_RECENTS, result.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void renderRecents() {
        if (recentsList == null) return;
        recentsList.removeAllViews();
        try {
            String json = prefs.getString(PREF_RECENTS, "[]");
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("Aún no hay páginas visitadas");
                empty.setTextColor(0xFF444444);
                empty.setTextSize(12f);
                empty.setPadding(dp(8), dp(8), dp(8), dp(8));
                recentsList.addView(empty);
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url   = o.optString("url", "");
                String title = o.optString("title", url);
                addRecentCard(url, title);
            }
        } catch (Exception ignored) {}
    }

    private void addRecentCard(String url, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(getDrawable(R.drawable.card_recent));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(88));
        lp.setMarginEnd(dp(10));
        card.setLayoutParams(lp);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        // Domain icon character
        String icon = "🌐";
        if (url.contains("youtube.com")) icon = "▶";
        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        iconView.setTextColor(0xFFFF6B35);
        iconView.setGravity(android.view.Gravity.CENTER);
        card.addView(iconView);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFCCCCCC);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(titleView);

        // Focus animation
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                v.setElevation(dp(16));
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                v.setElevation(dp(4));
            }
        });

        String finalUrl = url;
        card.setOnClickListener(v -> {
            currentMode = MODE_BRAVE;
            showBrowser();
            configureWebViewForMode(MODE_BRAVE);
            applyRegionCookies();
            updateVpnButton();
            loadUrl(applyRegion(finalUrl));
            webView.requestFocus();
        });

        recentsList.addView(card);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        webView       = findViewById(R.id.webView);
        urlBar        = findViewById(R.id.urlBar);
        progressBar   = findViewById(R.id.progressBar);
        statusText    = findViewById(R.id.statusText);
        navBar        = findViewById(R.id.navBar);
        btnVpn        = findViewById(R.id.btnVpn);
        homeScreen    = findViewById(R.id.homeScreen);
        homeVpnStatus = findViewById(R.id.homeVpnStatus);
        recentsList   = findViewById(R.id.recentsList);

        prefs = getSharedPreferences("bravetv", MODE_PRIVATE);
        autoVpnEnabled = prefs.getBoolean("vpn", true);
        regionIndex    = prefs.getInt("regionIdx", 0);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        setupWebView();
        setupButtons();
        setupCardFocusAnimations();
        updateHomeVpnStatus();
        showHome();
    }

    // ─── Card focus animations ────────────────────────────────────────────────
    private void setupCardFocusAnimations() {
        int[] cardIds = {R.id.cardBrave, R.id.cardTor, R.id.cardYtTv, R.id.cardYtWeb};
        for (int id : cardIds) {
            View card = findViewById(id);
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                    v.setElevation(dp(16));
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.setElevation(dp(4));
                }
            });
        }
    }

    // ─── Home / browser transitions ──────────────────────────────────────────
    private void showHome() {
        setNavBarHidden(false);
        renderRecents();
        updateHomeVpnStatus();

        homeScreen.setAlpha(0f);
        homeScreen.setVisibility(View.VISIBLE);
        homeScreen.animate().alpha(1f).setDuration(250).start();

        navBar.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> {
                navBar.setVisibility(View.GONE);
                navBar.setAlpha(1f);
            }).start();
        webView.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> {
                webView.setVisibility(View.GONE);
                webView.setAlpha(1f);
            }).start();
        progressBar.setVisibility(View.GONE);

        handler.postDelayed(() -> {
            View card = findViewById(R.id.cardBrave);
            if (card != null) card.requestFocus();
        }, 300);
    }

    private void showBrowser() {
        navBar.setAlpha(0f);
        navBar.setVisibility(View.VISIBLE);
        navBar.animate().alpha(1f).setDuration(250).start();

        webView.setAlpha(0f);
        webView.setVisibility(View.VISIBLE);
        webView.animate().alpha(1f).setDuration(250).start();

        homeScreen.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> {
                homeScreen.setVisibility(View.GONE);
                homeScreen.setAlpha(1f);
            }).start();
    }

    private void launchMode(int mode) {
        currentMode = mode;
        showBrowser();
        configureWebViewForMode(mode);

        String url;
        switch (mode) {
            case MODE_PRIVATE:
                clearBrowsingData();
                url = applyRegion("https://www.youtube.com/tv");
                break;
            case MODE_YT_TV:
                url = applyRegion("https://www.youtube.com/tv");
                break;
            case MODE_YT_WEB:
                url = autoVpnEnabled
                    ? "https://www.youtube.com/?gl=" + AUTO_REGIONS[regionIndex] + "&hl=es"
                    : "https://www.youtube.com/";
                break;
            default:
                url = applyRegion("https://www.youtube.com/tv");
                break;
        }

        applyRegionCookies();
        updateVpnButton();
        loadUrl(url);
        handler.postDelayed(() -> webView.requestFocus(), 350);
    }

    private void configureWebViewForMode(int mode) {
        WebSettings s = webView.getSettings();
        if (mode == MODE_PRIVATE) {
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(false);
        } else if (mode == MODE_YT_WEB) {
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(true);
        } else {
            s.setUserAgentString(
                "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) " +
                "AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1");
            CookieManager.getInstance().setAcceptCookie(true);
        }
    }

    private void clearBrowsingData() {
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    // ─── WebView setup ────────────────────────────────────────────────────────
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

        webView.addJavascriptInterface(new BraveTVInterface(), "BraveTV");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (isAdUrl(req.getUrl().toString()))
                    return new WebResourceResponse("text/plain", "utf-8",
                        new java.io.ByteArrayInputStream("".getBytes()));
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req,
                                            WebResourceResponse resp) {
                super.onReceivedHttpError(view, req, resp);
                if (resp != null && resp.getStatusCode() == 403) {
                    String url = req.getUrl().toString();
                    if (url.contains("youtube.com") && !isAdUrl(url) && currentMode != MODE_PRIVATE)
                        onGeoBlockDetected("http_403");
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

                // Save to recents (title via JS)
                view.evaluateJavascript("document.title", result -> {
                    if (result != null) {
                        String title = result.replaceAll("^\"|\"$", "").trim();
                        saveRecent(url, title);
                    }
                });

                // Hide ad elements
                view.evaluateJavascript(
                    "(function(){var s=document.createElement('style');" +
                    "s.innerHTML='ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer," +
                    "ytd-promoted-video-renderer,ytd-search-pyv-renderer,.ytp-ad-module," +
                    ".ytp-ad-overlay-container,.ytp-ad-text-overlay,.video-ads," +
                    "#player-ads,ytm-promoted-sparkles-web-renderer{display:none!important}';" +
                    "document.head.appendChild(s);})()", null);

                // Auto-skip ads
                view.evaluateJavascript(
                    "setInterval(function(){" +
                    "var sk=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');" +
                    "if(sk)sk.click();" +
                    "if(document.querySelector('.ad-showing')){" +
                    "var v=document.querySelector('video');if(v)v.currentTime=v.duration;}},300);", null);

                // Region cookie
                if (autoVpnEnabled && currentMode != MODE_PRIVATE) {
                    String gl = AUTO_REGIONS[regionIndex];
                    view.evaluateJavascript(
                        "document.cookie='PREF=f6=8&gl=" + gl + "; domain=.youtube.com; path=/';", null);
                }

                // Geo-block detector
                if (currentMode != MODE_PRIVATE) {
                    view.evaluateJavascript(
                        "(function(){var kw=['country','región','region','not available'," +
                        "'tu país','disponible','restricted','blocked'];" +
                        "var sel=['.ytp-error-content-wrap-subreason'," +
                        "'.ytp-error-content-wrap-reason','#error-screen yt-formatted-string'," +
                        "'#player .ytp-error-content'];" +
                        "function check(){for(var i=0;i<sel.length;i++){" +
                        "var el=document.querySelector(sel[i]);if(el){" +
                        "var t=el.textContent.toLowerCase();" +
                        "for(var j=0;j<kw.length;j++){if(t.indexOf(kw[j])>=0){" +
                        "if(typeof BraveTV!=='undefined')BraveTV.onGeoBlock(t.substring(0,80));return;}}}}" +
                        "var v=document.querySelector('video');" +
                        "if(v&&v.currentTime>3&&!v.paused&&typeof BraveTV!=='undefined')BraveTV.onVideoOk();}" +
                        "setInterval(check,3000);})()", null);
                }

                // Fullscreen / auto-hide navbar detector
                view.evaluateJavascript(
                    "(function(){var _fs=false;" +
                    "setInterval(function(){" +
                    "var v=document.querySelector('video');" +
                    "var fs=!!document.fullscreenElement||" +
                    "(!!v&&!v.paused&&v.currentTime>2&&" +
                    "!!document.querySelector('.ytp-fullscreen'));" +
                    "if(fs!==_fs){_fs=fs;" +
                    "if(typeof BraveTV!=='undefined')BraveTV.onFullscreen(fs);}},1500);})()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                view.loadUrl(currentMode != MODE_PRIVATE ? applyRegion(url) : url);
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
                navBarHidden = false;
                navBar.setTranslationY(0);
            }
        });

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
    }

    // ─── Buttons ──────────────────────────────────────────────────────────────
    private void setupButtons() {
        findViewById(R.id.cardBrave).setOnClickListener(v -> launchMode(MODE_BRAVE));
        findViewById(R.id.cardTor).setOnClickListener(v -> launchMode(MODE_PRIVATE));
        findViewById(R.id.cardYtTv).setOnClickListener(v -> launchMode(MODE_YT_TV));
        findViewById(R.id.cardYtWeb).setOnClickListener(v -> launchMode(MODE_YT_WEB));

        findViewById(R.id.btnYoutube).setOnClickListener(v ->
            loadUrl(currentMode == MODE_YT_WEB
                ? "https://www.youtube.com/" : applyRegion("https://www.youtube.com/tv")));
        findViewById(R.id.btnSearch).setOnClickListener(v -> showSearchDialog());
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btnHome).setOnClickListener(v -> showHome());

        btnVpn.setOnClickListener(v -> {
            autoVpnEnabled = !autoVpnEnabled;
            prefs.edit().putBoolean("vpn", autoVpnEnabled).apply();
            updateVpnButton();
            applyRegionCookies();
            Toast.makeText(this,
                autoVpnEnabled ? "🟢 VPN AUTO activado" : "🔴 VPN AUTO desactivado",
                Toast.LENGTH_SHORT).show();
            webView.reload();
        });

        btnVpn.setOnLongClickListener(v -> {
            if (!autoVpnEnabled) return true;
            new android.app.AlertDialog.Builder(this)
                .setTitle("Elegir región manualmente")
                .setSingleChoiceItems(AUTO_LABELS, regionIndex, (d, which) -> {
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

    private void loadUrl(String url) {
        webView.loadUrl(url);
        urlBar.setText(url);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (homeScreen.getVisibility() == View.VISIBLE) {
                finish();
                return true;
            }
            // Reveal navbar first if hidden
            if (navBarHidden) {
                setNavBarHidden(false);
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                showHome();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (homeScreen.getVisibility() == View.GONE) showSearchDialog();
            return true;
        }
        // D-pad scroll in WebView
        if (webView.getVisibility() == View.VISIBLE && webView.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                webView.evaluateJavascript("window.scrollBy(0,200)", null);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                webView.evaluateJavascript("window.scrollBy(0,-200)", null);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        prefs.edit().putInt("regionIdx", regionIndex).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webView.destroy();
    }
}
