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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String BLOCKLIST_URL =
        "https://raw.githubusercontent.com/jdans01/BraveTV/main/blocklist.txt";
    private static final String PREF_BLOCKLIST      = "remote_blocklist";
    private static final String PREF_BLOCKLIST_TIME = "remote_blocklist_time";
    // Refresh every 6 hours
    private static final long   BLOCKLIST_TTL_MS    = 6 * 60 * 60 * 1000L;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

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
    private android.widget.ImageButton btnCursorToggle;
    private Button btnHideNav;
    private final PointF cursor = new PointF(640, 360);
    private static final int CURSOR_STEP = 32;

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
    // Mutable sets — seeded from built-in list, overwritten by remote blocklist
    private final Set<String> adHosts    = new HashSet<>();
    private final Set<String> adPatterns = new HashSet<>();

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        // Google ads / IMA SDK — bloquear IMA impide que el player cargue el anuncio de video
        "imasdk.googleapis.com","ima.googleapis.com",
        "ad.youtube.com","ads.youtube.com",
        "googleadservices.com","www.googleadservices.com",
        "googlesyndication.com","pagead2.googlesyndication.com",
        "tpc.googlesyndication.com","adservice.google.com",
        "adservice.google.com.mx","adservice.google.co",
        "doubleclick.net","ad.doubleclick.net","stats.g.doubleclick.net",
        "cm.g.doubleclick.net","securepubads.g.doubleclick.net",
        "pubads.g.doubleclick.net","static.doubleclick.net",
        "survey.g.doubleclick.net","2mdn.net",
        "googletagmanager.com","googletagservices.com",
        "analytics.google.com","google-analytics.com",
        "connect.facebook.net","graph.facebook.com",
        "outbrain.com","taboola.com","moatads.com",
        "amazon-adsystem.com","adsafeprotected.com","adnxs.com",
        "sb.scorecardresearch.com","pixel.quantserve.com",
        "ade.googlesyndication.com","fundingchoicesmessages.google.com"
    ));
    private static final Set<String> AD_URL_PATTERNS = new HashSet<>(Arrays.asList(
        // YouTube ad video streams: ctier=L marca anuncio pre-roll/mid-roll
        "ctier=L","ctier%3DL",
        // YouTube ad tracking endpoints
        "youtube.com/api/stats/ads","youtube.com/pagead","youtube.com/ptracking",
        "youtube.com/get_video_info","youtube.com/api/stats/qoe",
        "youtube.com/api/stats/atr","youtube.com/youtubei/v1/log_event",
        "/pagead/","/ads/","/adview","ad_type=","doubleclick","/ptracking",
        "ad_video_id=","&ad_cpn=","&oad=1","&adformat=","&adurl=",
        // IMA SDK script paths
        "/ima/","imasdk","imasdk",
        // Consent / funding-choice dialogs
        "fundingchoicesmessages","consent.youtube"
    ));

    // ── Seed mutable sets from built-in + cached remote list ─────────────────
    private void initBlocklist() {
        adHosts.addAll(AD_HOSTS);
        adPatterns.addAll(AD_URL_PATTERNS);
        // Apply cached remote list immediately (no network wait)
        String cached = prefs.getString(PREF_BLOCKLIST, "");
        if (!cached.isEmpty()) applyRemoteBlocklist(cached);
        // Fetch fresh copy in background if TTL expired
        long lastFetch = prefs.getLong(PREF_BLOCKLIST_TIME, 0);
        if (System.currentTimeMillis() - lastFetch > BLOCKLIST_TTL_MS) {
            fetchRemoteBlocklist();
        }
    }

    private void fetchRemoteBlocklist() {
        bgExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(BLOCKLIST_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "BraveTVBrowser/1.0");
                if (conn.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                conn.disconnect();
                String content = sb.toString().trim();
                if (content.isEmpty()) return;
                prefs.edit()
                    .putString(PREF_BLOCKLIST, content)
                    .putLong(PREF_BLOCKLIST_TIME, System.currentTimeMillis())
                    .apply();
                handler.post(() -> applyRemoteBlocklist(content));
            } catch (Exception ignored) {}
        });
    }

    private void applyRemoteBlocklist(String content) {
        Set<String> hosts    = new HashSet<>();
        Set<String> patterns = new HashSet<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("HOST:"))    hosts.add(line.substring(5).trim());
            else if (line.startsWith("PATTERN:")) patterns.add(line.substring(8).trim());
        }
        if (!hosts.isEmpty())    { adHosts.clear();    adHosts.addAll(hosts); }
        if (!patterns.isEmpty()) { adPatterns.clear(); adPatterns.addAll(patterns); }
    }

    private boolean isAdUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                if (adHosts.contains(host)) return true;
                for (String h : adHosts)
                    if (host.endsWith("." + h)) return true;
            }
            for (String p : adPatterns) if (url.contains(p)) return true;
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
        cursorDot       = findViewById(R.id.cursorDot);
        btnCursorToggle = findViewById(R.id.btnCursor);
        btnCursorToggle.setOnClickListener(v -> togglePointerMode());
        btnHideNav = findViewById(R.id.btnHideNav);
        if (btnHideNav != null) btnHideNav.setOnClickListener(v -> setNavBarHidden(true));

        prefs = getSharedPreferences("bravetv", 0); // Context.MODE_PRIVATE = 0
        autoVpnEnabled = prefs.getBoolean("vpn", true);
        regionIndex    = prefs.getInt("regionIdx", 0);
        initBlocklist(); // seed built-in + load cached remote list

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
        // Highlight button when active
        if (btnCursorToggle != null) {
            btnCursorToggle.setAlpha(pointerMode ? 1f : 0.5f);
        }
        if (pointerMode) {
            webView.post(() -> {
                cursor.set(webView.getWidth() / 2f, webView.getHeight() / 2f);
                moveCursorTo(cursor.x, cursor.y);
                cursorDot.setVisibility(View.VISIBLE);
            });
        } else {
            if (cursorDot != null) cursorDot.setVisibility(View.GONE);
        }
        Toast.makeText(this,
            pointerMode ? "🖱 Puntero ON — D-pad mueve · OK = clic" : "🎮 D-pad normal",
            Toast.LENGTH_SHORT).show();
    }

    private void moveCursorTo(float x, float y) {
        if (cursorDot == null) return;
        int w = webView.getWidth();
        int h = webView.getHeight();
        // Clamp keeping arrow tip inside screen
        float cx = Math.max(0, Math.min(x, w - dp(28)));
        float cy = Math.max(0, Math.min(y, h - dp(32)));
        cursor.set(cx, cy);
        cursorDot.setX(cx);
        cursorDot.setY(cy);
        cursorDot.setVisibility(View.VISIBLE);
        cursorDot.setAlpha(1f);
    }

    private void simulateClick(float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up   = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        // Pulse cursor to confirm click — stays visible
        cursorDot.animate().scaleX(1.5f).scaleY(1.5f).setDuration(80)
            .withEndAction(() -> cursorDot.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
            .start();
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
        // ── Capa 1: CSS — oculta contenedores de anuncios ────────────────────
        view.evaluateJavascript(
            "(function(){" +
            "var ID='_btv_css2';" +
            "var ex=document.getElementById(ID);if(ex)ex.remove();" +
            "var s=document.createElement('style');s.id=ID;" +
            "s.textContent=[" +
            // YouTube web — anuncios en resultados y sidebar
            "'ytd-display-ad-renderer'," +
            "'ytd-promoted-sparkles-web-renderer'," +
            "'ytd-promoted-video-renderer'," +
            "'ytd-search-pyv-renderer'," +
            "'ytd-banner-promo-renderer'," +
            "'ytd-statement-banner-renderer'," +
            "'ytd-ad-slot-renderer'," +
            "'ytd-in-feed-ad-layout-renderer'," +
            "'ytd-action-companion-ad-renderer'," +
            "'ytd-display-ad-renderer'," +
            // YouTube web — player ads
            "'.ytp-ad-module'," +
            "'.ytp-ad-overlay-container'," +
            "'.ytp-ad-text-overlay'," +
            "'.ytp-ad-image-overlay'," +
            "'.ytp-ad-persistent-progress-bar-container'," +
            "'.ytp-ad-progress-list'," +
            "'.ytp-ad-player-overlay'," +
            "'.ytp-ad-player-overlay-instream-info'," +
            "'.video-ads'," +
            "'#player-ads'," +
            "'#masthead-ad'," +
            // Mealbar / surveys
            "'.ytd-mealbar-promo-renderer'," +
            "'ytm-promoted-sparkles-web-renderer'," +
            // Consent/funding dialogs
            "'tp-yt-paper-dialog.ytd-popup-container'," +
            "'ytd-enforcement-message-view-model'," +
            // YouTube TV (youtube.com/tv)
            "'ytlr-ad-placement-renderer'," +
            "'.ytlr-ad-action-interstitial-renderer'," +
            "'.ytlr-companion-slot'," +
            "'ytlr-mealbar-promo-renderer'," +
            "'ytlr-skip-button-renderer'," +
            // Generic
            "'[id^=\"ad_\"]','[class*=\"-ad-\"]'" +
            "].join(',')+" +
            "'{display:none!important;visibility:hidden!important;max-height:0!important}';" +
            "document.head.appendChild(s);})()", null);

        // ── Capa 2: JS — skip/fast-forward anuncios de video ─────────────────
        view.evaluateJavascript(
            "(function(){" +
            "if(window._btvSkip2) return; window._btvSkip2=true;" +
            // Monkey-patch fetch/XHR para bloquear IMA en tiempo de ejecución
            "var _oFetch=window.fetch;" +
            "window.fetch=function(u,o){" +
            "  if(typeof u==='string'&&(" +
            "    u.indexOf('imasdk')>=0||u.indexOf('ctier=L')>=0||" +
            "    u.indexOf('/pagead/')>=0||u.indexOf('fundingchoices')>=0))" +
            "    return Promise.resolve(new Response('',{status:200}));" +
            "  return _oFetch.apply(this,arguments);};" +
            // Monkey-patch XMLHttpRequest
            "var _oOpen=XMLHttpRequest.prototype.open;" +
            "XMLHttpRequest.prototype.open=function(m,u){" +
            "  if(u&&(u.indexOf('imasdk')>=0||u.indexOf('ctier=L')>=0||" +
            "    u.indexOf('/pagead/')>=0)){" +
            "    this._blocked=true;}" +
            "  return _oOpen.apply(this,arguments);};" +
            "var _oSend=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.send=function(){" +
            "  if(this._blocked){this.abort();return;}" +
            "  return _oSend.apply(this,arguments);};" +
            // Loop: skip botón, fast-forward, dismiss interstitial
            "setInterval(function(){" +
            "  var sk=document.querySelector(" +
            "    '.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern," +
            "     .ytp-ad-skip-button-slot button,ytlr-skip-button-renderer button," +
            "     button[class*=\"skip\"]');" +
            "  if(sk){sk.click();return;}" +
            // Fast-forward si hay anuncio de video
            "  var adOn=document.querySelector(" +
            "    '.ad-showing,.ad-interrupting,.ytp-ad-player-overlay');" +
            "  if(adOn){" +
            "    var v=document.querySelector('video');" +
            "    if(v&&v.duration>0&&!isNaN(v.duration)&&v.duration<600){" +
            "      v.playbackRate=16;v.currentTime=v.duration-0.1;}" +
            "  } else {" +
            "    var v2=document.querySelector('video');" +
            "    if(v2&&v2.playbackRate>1)v2.playbackRate=1;" +
            "  }" +
            // YouTube TV interstitial
            "  var ib=document.querySelector(" +
            "    '[data-dismiss-interstitial],ytlr-skip-button-renderer button," +
            "     .ytlr-ad-action-interstitial-skip-button');" +
            "  if(ib)ib.click();" +
            // Remove ad overlay nodes that CSS didn't catch
            "  var ad=document.querySelector('.ytp-ad-player-overlay,.ytp-ad-module');" +
            "  if(ad&&ad.parentNode)ad.parentNode.removeChild(ad);" +
            "},200);})()", null);
    }

    // ─── D-pad spatial navigation injection ──────────────────────────────────
    private void injectDpadNavigation(WebView view) {
        // Skip on YouTube (all variants) — it has its own D-pad engine; our listener breaks it
        String url = view.getUrl();
        if (url != null && url.contains("youtube.com")) return;

        // Only inject on non-YouTube pages (Brave Search, DuckDuckGo, generic web)
        view.evaluateJavascript(
            "(function(){" +
            "if(window._btvNav) return; window._btvNav=true;" +

            // Highlight focused element
            "var _st=document.createElement('style');_st.id='_btv_nav_css';" +
            "_st.innerHTML=" +
            "':focus{outline:3px solid #C084FC!important;outline-offset:2px!important;" +
            "border-radius:4px!important;box-shadow:0 0 0 6px rgba(192,132,252,0.25)!important;}'" +
            ";document.head.appendChild(_st);" +

            "var SEL='a[href],button:not([disabled]),input:not([disabled])," +
            "select:not([disabled]),[tabindex]:not([tabindex=\"-1\"])," +
            "[role=\"button\"],[role=\"link\"],[role=\"menuitem\"]';" +

            "function getEls(){" +
            "  return Array.from(document.querySelectorAll(SEL)).filter(function(e){" +
            "    var r=e.getBoundingClientRect();" +
            "    return r.width>0&&r.height>0&&r.top<window.innerHeight&&r.bottom>0;" +
            "  });" +
            "}" +

            "function nearest(cur,dir){" +
            "  var els=getEls(),cr=cur.getBoundingClientRect();" +
            "  var cx=cr.left+cr.width/2,cy=cr.top+cr.height/2;" +
            "  var best=null,bestScore=Infinity;" +
            "  for(var i=0;i<els.length;i++){" +
            "    var el=els[i];if(el===cur)continue;" +
            "    var r=el.getBoundingClientRect();" +
            "    var dx=r.left+r.width/2-cx,dy=r.top+r.height/2-cy;" +
            "    var ok=(dir==='up'&&dy<-8)||(dir==='down'&&dy>8)||" +
            "           (dir==='left'&&dx<-8)||(dir==='right'&&dx>8);" +
            "    if(!ok)continue;" +
            "    var pri=(dir==='up'||dir==='down')?Math.abs(dy):Math.abs(dx);" +
            "    var crs=(dir==='up'||dir==='down')?Math.abs(dx):Math.abs(dy);" +
            "    var sc=pri+crs*2;if(sc<bestScore){bestScore=sc;best=el;}" +
            "  }" +
            "  return best;" +
            "}" +

            // Key listener — NO preventDefault so the page's own handlers still run
            "document.addEventListener('keydown',function(e){" +
            "  var dir=null;" +
            "  if(e.keyCode===38)dir='up';" +
            "  else if(e.keyCode===40)dir='down';" +
            "  else if(e.keyCode===37)dir='left';" +
            "  else if(e.keyCode===39)dir='right';" +
            "  if(!dir)return;" +
            "  var cur=document.activeElement;" +
            "  if(!cur||cur===document.body){" +
            "    var els=getEls();if(els.length)els[0].focus();" +
            "    return;" +
            "  }" +
            "  var nxt=nearest(cur,dir);" +
            "  if(nxt)nxt.focus();" +
            "  else if(dir==='down')window.scrollBy(0,200);" +
            "  else if(dir==='up')window.scrollBy(0,-200);" +
            "});" +  // no 'true' capture — let page handle first

            "})()", null);
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
                injectDpadNavigation(view);

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
                handler.postDelayed(() -> { injectAdBlocker(view); injectDpadNavigation(view); }, 1200);
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
            String input = urlBar.getText().toString().trim();
            if (input.isEmpty()) return true;
            // If it looks like a URL, navigate; otherwise search in the current mode
            boolean isUrl = input.startsWith("http://") || input.startsWith("https://")
                || (input.contains(".") && !input.contains(" ") && input.length() > 4);
            if (isUrl) {
                String url = input.startsWith("http") ? input : "https://" + input;
                loadUrl(url);
            } else {
                doSearch(input);
            }
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
        // Only intercept D-pad when WebView has focus AND navbar is hidden.
        // If navbar is visible, let super handle it so Android moves focus between buttons.
        if (!pointerMode && webView.getVisibility() == View.VISIBLE && webView.hasFocus()) {
            // Scroll only — let the page's own JS handle element focus (YouTube, etc.)
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                webView.evaluateJavascript("window.scrollBy(0,220)", null);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                webView.evaluateJavascript("window.scrollBy(0,-220)", null);
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
        bgExecutor.shutdown();
        webView.destroy();
    }
}
