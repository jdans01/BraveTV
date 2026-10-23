package com.bravetv.browser;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class MainActivity extends Activity {

    private static final int NAV_BRAVE     = 0;
    private static final int NAV_INCOGNITO = 1;
    private static final int NAV_YT_TV    = 2;
    private static final int NAV_YT_WEB   = 3;

    private int currentMode = NAV_BRAVE;

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
    private int blockedCount = 0;
    private TextView btnBlockCount;
    private TextView homeBlockCount;
    private View btnRevealNav;

    // ─── Pointer / cursor mode ────────────────────────────────────────────────
    private boolean pointerMode = false;
    private View cursorDot;
    private Button btnCursorToggle;
    private final PointF cursor = new PointF(640, 360);
    private static final int CURSOR_STEP = 32;
    private final Runnable cursorHideRunnable = () -> {
        if (cursorDot != null) cursorDot.setVisibility(View.GONE);
    };

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
        "adsafeprotected.com","2mdn.net","adnxs.com",
        // Additional ad/tracking domains
        "yt3.ggpht.com","sb.scorecardresearch.com","pixel.quantserve.com",
        "s.ytimg.com/yts/imgbin","static.doubleclick.net",
        "survey.g.doubleclick.net","www.youtube-nocookie.com"
    ));
    private static final Set<String> AD_URL_PATTERNS = new HashSet<>(Arrays.asList(
        "/pagead/","/ads/","/adview","ad_type=","doubleclick","/ptracking",
        "/get_video_info?","googlevideo.com/videoplayback?ctier=L",
        "youtube.com/api/stats/ads","youtube.com/pagead","youtube.com/ptracking",
        "/api/stats/qoe?","/api/stats/atr?","/youtubei/v1/log_event",
        "ad_video_id=","&ad_cpn=","ctier=L&","&oad=1","&adformat="
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
        if (!autoVpnEnabled || currentMode == NAV_INCOGNITO) return;
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
            navBar.animate()
                .translationY(-navBar.getHeight() - dp(4))
                .alpha(0f)
                .setDuration(350)
                .withEndAction(() -> {
                    navBar.setVisibility(View.INVISIBLE);
                    if (btnRevealNav != null) btnRevealNav.setVisibility(View.VISIBLE);
                })
                .start();
        } else {
            if (btnRevealNav != null) btnRevealNav.setVisibility(View.GONE);
            navBar.setVisibility(View.VISIBLE);
            navBar.animate()
                .translationY(0)
                .alpha(1f)
                .setDuration(280)
                .start();
        }
    }

    // ─── Recent history ───────────────────────────────────────────────────────
    private static final int MAX_RECENTS = 8;
    private static final String PREF_RECENTS = "recents_json";

    private void saveRecent(String url, String title) {
        if (url == null || title == null || title.isEmpty()) return;
        String lower = title.toLowerCase();
        // Skip generic home pages and duplicate YouTube TV entries
        if (lower.equals("youtube") || lower.contains("youtube en la televisión")
                || lower.equals("youtube tv") || lower.equals("youtube music")
                || url.matches(".*youtube\\.com/tv[?#]?$") || url.matches(".*youtube\\.com/tv\\?gl.*#$")
                || url.matches(".*youtube\\.com/(\\?.*)?$") || title.length() < 4) return;

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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(188), dp(116));
        lp.setMarginEnd(dp(12));
        card.setLayoutParams(lp);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        // Domain icon
        String icon = "🌐";
        if (url.contains("youtube.com")) icon = "▶";
        else if (url.contains("brave.com"))  icon = "B";
        else if (url.contains("duckduckgo")) icon = "🔒";
        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        iconView.setTextColor(url.contains("youtube") ? 0xFFEF4444 : 0xFFC084FC);
        iconView.setGravity(android.view.Gravity.CENTER);
        card.addView(iconView);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFD0D0E8);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
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
            currentMode = NAV_BRAVE;
            showBrowser();
            configureWebViewForMode(NAV_BRAVE);
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
        homeVpnStatus  = findViewById(R.id.homeVpnStatus);
        recentsList    = findViewById(R.id.recentsList);
        btnBlockCount  = findViewById(R.id.btnBlockCount);
        homeBlockCount = findViewById(R.id.homeBlockCount);
        btnRevealNav   = findViewById(R.id.btnRevealNav);
        btnRevealNav.setOnClickListener(v -> setNavBarHidden(false));
        cursorDot      = findViewById(R.id.cursorDot);
        btnCursorToggle = findViewById(R.id.btnCursor);
        btnCursorToggle.setOnClickListener(v -> togglePointerMode());

        prefs = getSharedPreferences("bravetv", 0); // Context.MODE_PRIVATE = 0
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

    // ─── Pointer mode ─────────────────────────────────────────────────────────
    private void togglePointerMode() {
        pointerMode = !pointerMode;
        if (btnCursorToggle != null) {
            btnCursorToggle.setTextColor(pointerMode ? 0xFF4ADE80 : 0xFFC084FC);
        }
        if (pointerMode) {
            // Center cursor on screen
            webView.post(() -> {
                cursor.set(webView.getWidth() / 2f, webView.getHeight() / 2f);
                moveCursorTo(cursor.x, cursor.y);
                cursorDot.setVisibility(View.VISIBLE);
            });
        } else {
            handler.removeCallbacks(cursorHideRunnable);
            if (cursorDot != null) cursorDot.setVisibility(View.GONE);
        }
        Toast.makeText(this,
            pointerMode ? "🖱️ Puntero activado — D-pad mueve, OK clic" : "🎮 D-pad normal",
            Toast.LENGTH_SHORT).show();
    }

    private void moveCursorTo(float x, float y) {
        if (cursorDot == null) return;
        int w = webView.getWidth();
        int h = webView.getHeight();
        float cx = Math.max(0, Math.min(x, w));
        float cy = Math.max(0, Math.min(y, h));
        cursor.set(cx, cy);
        int dotW = cursorDot.getWidth();
        int dotH = cursorDot.getHeight();
        cursorDot.setX(cx - dotW / 2f);
        cursorDot.setY(cy - dotH / 2f);
        cursorDot.setVisibility(View.VISIBLE);
        handler.removeCallbacks(cursorHideRunnable);
        handler.postDelayed(cursorHideRunnable, 3000);
    }

    private void simulateClick(float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up   = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, x, y, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    // ─── Card focus animations ────────────────────────────────────────────────
    private void setupCardFocusAnimations() {
        DecelerateInterpolator interp = new DecelerateInterpolator(1.5f);
        int[] cardIds = {R.id.cardBrave, R.id.cardTor, R.id.cardYtTv, R.id.cardYtWeb};
        for (int id : cardIds) {
            View card = findViewById(id);
            card.setOnFocusChangeListener((v, focused) -> {
                ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", focused ? 1.08f : 1f);
                ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", focused ? 1.08f : 1f);
                ObjectAnimator tz = ObjectAnimator.ofFloat(v, "translationZ", focused ? dp(12) : 0);
                AnimatorSet set = new AnimatorSet();
                set.playTogether(sx, sy, tz);
                set.setDuration(180);
                set.setInterpolator(interp);
                set.start();
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
            case NAV_BRAVE:
                url = "https://search.brave.com/";
                break;
            case NAV_INCOGNITO:
                clearBrowsingData();
                url = "https://duckduckgo.com/";
                break;
            case NAV_YT_TV:
                url = applyRegion("https://www.youtube.com/tv");
                break;
            case NAV_YT_WEB:
                url = autoVpnEnabled
                    ? "https://www.youtube.com/?gl=" + AUTO_REGIONS[regionIndex] + "&hl=es"
                    : "https://www.youtube.com/";
                break;
            default:
                url = "https://search.brave.com/";
                break;
        }

        applyRegionCookies();
        updateVpnButton();
        loadUrl(url);
        handler.postDelayed(() -> webView.requestFocus(), 350);
    }

    private void configureWebViewForMode(int mode) {
        WebSettings s = webView.getSettings();
        if (mode == NAV_INCOGNITO) {
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(false);
        } else if (mode == NAV_YT_WEB) {
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(true);
        } else {
            // Desktop Chrome UA for YouTube TV → enables 1080p/4K selection
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
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

    // ─── Ad blocker injection (called on page load AND on SPA navigation) ────
    private void injectAdBlocker(WebView view) {
        // CSS: hide all ad containers
        view.evaluateJavascript(
            "(function(){" +
            "if(document.getElementById('_btv_css')) return;" +
            "var s=document.createElement('style');s.id='_btv_css';" +
            "s.innerHTML=" +
            "'ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer," +
            "ytd-promoted-video-renderer,ytd-search-pyv-renderer," +
            "ytd-banner-promo-renderer,ytd-statement-banner-renderer," +
            ".ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-text-overlay," +
            ".ytp-ad-skip-button-container+div,.video-ads,#player-ads," +
            "ytm-promoted-sparkles-web-renderer,.ytd-mealbar-promo-renderer," +
            "tp-yt-paper-dialog.ytd-popup-container," +
            // YouTube TV ad overlays
            "ytlr-ad-placement-renderer,.ytlr-ad-action-interstitial-renderer," +
            ".ytlr-companion-slot,ytlr-mealbar-promo-renderer" +
            "{display:none!important;visibility:hidden!important;}';" +
            "document.head.appendChild(s);})()", null);

        // JS: skip button + fast-forward video ads + mute while ad plays
        view.evaluateJavascript(
            "(function(){" +
            "if(window._btvSkip) return; window._btvSkip=true;" +
            "setInterval(function(){" +
            // Click any skip button
            "  var sk=document.querySelector(" +
            "    '.ytp-ad-skip-button,.ytp-skip-ad-button," +
            "     .ytp-ad-skip-button-modern,ytlr-skip-ad-renderer button');" +
            "  if(sk){sk.click();return;}" +
            // If ad is showing, jump video to end
            "  if(document.querySelector('.ad-showing,.ad-interrupting')){" +
            "    var v=document.querySelector('video');" +
            "    if(v&&v.duration>0&&!isNaN(v.duration)){" +
            "      v.currentTime=v.duration;v.muted=false;}" +
            "  }" +
            // YouTube TV: dismiss interstitial ads
            "  var btn=document.querySelector('[data-dismiss-interstitial]');" +
            "  if(btn) btn.click();" +
            "},250);})()", null);
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
        // Desktop Chrome UA → YouTube serves 1080p/4K instead of capping at 720p
        s.setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        // Hint VP9/AV1 support for HD streams
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        webView.addJavascriptInterface(new BraveTVInterface(), "BraveTV");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (isAdUrl(req.getUrl().toString())) {
                    blockedCount++;
                    handler.post(() -> {
                        if (btnBlockCount != null)
                            btnBlockCount.setText("🚫 " + blockedCount);
                        if (homeBlockCount != null)
                            homeBlockCount.setText("🚫 " + blockedCount + " bloqueados");
                    });
                    return new WebResourceResponse("text/plain", "utf-8",
                        new java.io.ByteArrayInputStream("".getBytes()));
                }
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req,
                                            WebResourceResponse resp) {
                super.onReceivedHttpError(view, req, resp);
                if (resp != null && resp.getStatusCode() == 403) {
                    String url = req.getUrl().toString();
                    if (url.contains("youtube.com") && !isAdUrl(url) && currentMode != NAV_INCOGNITO)
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

                injectAdBlocker(view);

                // Force highest available video quality (1080p → 4K)
                view.evaluateJavascript(
                    "(function(){" +
                    "var QUALITIES=['hd2160','hd1440','hd1080','hd720'];" +
                    "function forceHD(){" +
                    // Standard YouTube web player
                    "  var p=document.getElementById('movie_player');" +
                    "  if(p){" +
                    "    try{if(p.setPlaybackQualityRange)p.setPlaybackQualityRange('hd1080','hd2160');}catch(e){}" +
                    "    try{if(p.setPlaybackQuality)p.setPlaybackQuality('hd1080');}catch(e){}" +
                    "    try{" +
                    "      var avail=p.getAvailableQualityLevels&&p.getAvailableQualityLevels();" +
                    "      if(avail&&avail.length>0){" +
                    "        for(var q=0;q<QUALITIES.length;q++){" +
                    "          if(avail.indexOf(QUALITIES[q])>=0){" +
                    "            p.setPlaybackQuality(QUALITIES[q]);break;}" +
                    "        }" +
                    "      }" +
                    "    }catch(e){}" +
                    "  }" +
                    // YouTube TV interface player
                    "  try{" +
                    "    var yta=document.querySelector('ytlr-app')||document.querySelector('yt-app');" +
                    "    if(yta&&window.yt&&window.yt.player){" +
                    "      var pp=window.yt.player.Application.get();" +
                    "      if(pp&&pp.setPlaybackQualityRange)pp.setPlaybackQualityRange('hd1080','hd2160');" +
                    "    }" +
                    "  }catch(e){}" +
                    "}" +
                    "forceHD();" +
                    "setInterval(forceHD,4000);" +
                    "})()", null);

                // Region cookie
                if (autoVpnEnabled && currentMode != NAV_INCOGNITO) {
                    String gl = AUTO_REGIONS[regionIndex];
                    view.evaluateJavascript(
                        "document.cookie='PREF=f6=8&gl=" + gl + "; domain=.youtube.com; path=/';", null);
                }

                // Geo-block detector
                if (currentMode != NAV_INCOGNITO) {
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

                // Fullscreen / auto-hide navbar — covers YouTube web + YouTube TV
                view.evaluateJavascript(
                    "(function(){var _fs=false;" +
                    "function isFS(){" +
                    "  if(!!document.fullscreenElement||!!document.webkitFullscreenElement) return true;" +
                    "  var v=document.querySelector('video');" +
                    "  if(!v||v.paused||v.currentTime<1) return false;" +
                    // YouTube web fullscreen button active
                    "  if(document.querySelector('.ytp-fullscreen')) return true;" +
                    // YouTube TV: video fills the whole viewport
                    "  var r=v.getBoundingClientRect();" +
                    "  return r.width>=window.innerWidth*0.95&&r.height>=window.innerHeight*0.85;" +
                    "}" +
                    "setInterval(function(){" +
                    "  var fs=isFS();" +
                    "  if(fs!==_fs){_fs=fs;" +
                    "    if(typeof BraveTV!=='undefined')BraveTV.onFullscreen(fs);}" +
                    "},1200);})()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                view.loadUrl(currentMode != NAV_INCOGNITO ? applyRegion(url) : url);
                // Re-inject ad blocker after SPA navigation (YouTube TV doesn't fire onPageFinished)
                handler.postDelayed(() -> injectAdBlocker(view), 1200);
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
        findViewById(R.id.cardBrave).setOnClickListener(v -> launchMode(NAV_BRAVE));
        findViewById(R.id.cardTor).setOnClickListener(v -> launchMode(NAV_INCOGNITO));
        findViewById(R.id.cardYtTv).setOnClickListener(v -> launchMode(NAV_YT_TV));
        findViewById(R.id.cardYtWeb).setOnClickListener(v -> launchMode(NAV_YT_WEB));

        findViewById(R.id.btnYoutube).setOnClickListener(v ->
            loadUrl(currentMode == NAV_YT_WEB
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
        String hint;
        String title;
        switch (currentMode) {
            case NAV_BRAVE:     hint = "Buscar en Brave Search...";   title = "🔍 Brave Search";   break;
            case NAV_INCOGNITO: hint = "Buscar en DuckDuckGo...";     title = "🔒 DuckDuckGo";     break;
            default:            hint = "Buscar en YouTube...";        title = "🔍 Buscar YouTube";  break;
        }

        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Buscar", (d, w) -> doSearch(input.getText().toString()))
            .setNegativeButton("Cancelar", null)
            .create();

        input.setOnEditorActionListener((v, actionId, event) -> {
            doSearch(input.getText().toString());
            dialog.dismiss();
            return true;
        });

        dialog.show();
        input.requestFocus();
    }

    private void doSearch(String query) {
        query = query == null ? "" : query.trim();
        if (query.isEmpty()) return;
        String url;
        switch (currentMode) {
            case NAV_BRAVE:
                url = "https://search.brave.com/search?q=" + Uri.encode(query);
                break;
            case NAV_INCOGNITO:
                url = "https://duckduckgo.com/?q=" + Uri.encode(query);
                break;
            default:
                url = applyRegion("https://www.youtube.com/results?search_query=" + Uri.encode(query));
                break;
        }
        loadUrl(url);
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
        // Pointer mode: D-pad moves cursor, OK simulates click
        if (pointerMode && webView.getVisibility() == View.VISIBLE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveCursorTo(cursor.x, cursor.y - CURSOR_STEP); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveCursorTo(cursor.x, cursor.y + CURSOR_STEP); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveCursorTo(cursor.x - CURSOR_STEP, cursor.y); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveCursorTo(cursor.x + CURSOR_STEP, cursor.y); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    simulateClick(cursor.x, cursor.y); return true;
            }
        }
        // D-pad scroll in WebView (normal mode)
        if (!pointerMode && webView.getVisibility() == View.VISIBLE && webView.hasFocus()) {
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
