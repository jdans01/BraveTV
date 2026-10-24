package com.bravetv.browser;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String BLOCKLIST_URL =
        "https://raw.githubusercontent.com/jdans01/BraveTV/main/blocklist.txt";
    // Cached on disk, not in SharedPreferences — the list now includes a general-purpose
    // ad/tracker list (HaGeZi Light + Pop-Up Ads, ~111k domains, ~2.7MB), and SharedPreferences
    // is backed by a single XML file read/written as a whole; a blob that size has no business
    // living there.
    private static final String BLOCKLIST_FILE      = "blocklist_cache.txt";
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
    private TextView homeQuality;
    private LinearLayout recentsList;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean navBarHidden = false;
    // Last time we saw a real tap/click from the user (D-pad OK, pointer click, or a
    // gestured page navigation) — see shouldOverrideUrlLoading's redirect guard.
    private volatile long lastGestureAtMs = 0;
    private static final long GESTURE_GRACE_MS = 4000;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
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

    // ─── Calidad de video ──────────────────────────────────────────────────────
    // Forcing the absolute highest available quality unconditionally (the old behavior)
    // is what was choking playback on weaker/smaller TVs — the decoder has to work much
    // harder for 4K than the screen can even resolve. QUALITY_* lets the user cap the
    // ceiling to something their TV size can actually decode smoothly.
    private static final String[] QUALITY_LEVELS = {"hd2160","hd1440","hd1080","hd720","large","medium"};
    private static final String[] QUALITY_CAPS   = {"auto","hd1080","hd720","large"};
    private static final String[] QUALITY_LABELS = {
        "🤖 Automática (máxima calidad)",
        "🖥️ TV grande 55\"+ — 1080p",
        "📺 TV mediana 32\"–50\" — 720p",
        "📱 TV chica <32\" — 480p"
    };
    private static final String[] QUALITY_SHORT = {"Auto", "1080p", "720p", "480p"};
    private String videoQualityCap = "auto";

    private String[] qualitiesForCap() {
        if ("auto".equals(videoQualityCap)) return QUALITY_LEVELS;
        for (int i = 0; i < QUALITY_LEVELS.length; i++)
            if (QUALITY_LEVELS[i].equals(videoQualityCap))
                return Arrays.copyOfRange(QUALITY_LEVELS, i, QUALITY_LEVELS.length);
        return QUALITY_LEVELS;
    }

    private void updateHomeQualityLabel() {
        if (homeQuality == null) return;
        int idx = Arrays.asList(QUALITY_CAPS).indexOf(videoQualityCap);
        homeQuality.setText("📺 Calidad: " + QUALITY_SHORT[idx >= 0 ? idx : 0]);
    }

    private void showQualityDialog() {
        int current = Math.max(0, Arrays.asList(QUALITY_CAPS).indexOf(videoQualityCap));
        new android.app.AlertDialog.Builder(this)
            .setTitle("Calidad de video — según el tamaño de tu TV")
            .setSingleChoiceItems(QUALITY_LABELS, current, (d, which) -> {
                videoQualityCap = QUALITY_CAPS[which];
                prefs.edit().putString("videoQualityCap", videoQualityCap).apply();
                updateHomeQualityLabel();
                Toast.makeText(this, "📺 " + QUALITY_LABELS[which], Toast.LENGTH_SHORT).show();
                d.dismiss();
                if (webView.getVisibility() == View.VISIBLE) webView.reload();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ─── Ad block ─────────────────────────────────────────────────────────────
    // Seeded from the built-in list, replaced wholesale by the remote blocklist once it
    // loads. volatile + whole-set swap (never mutated in place after publish) because
    // isAdUrl() is read from shouldInterceptRequest's background thread while a fresh set
    // can be published from bgExecutor at any time — this is the safe-publication pattern,
    // not a synchronized block, since the published set itself is never touched again.
    private volatile Set<String> adHosts    = new HashSet<>();
    private volatile Set<String> adPatterns = new HashSet<>();

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
        adHosts    = new HashSet<>(AD_HOSTS);
        adPatterns = new HashSet<>(AD_URL_PATTERNS);
        // Reading/parsing a ~2.7MB cached list has no business running on the UI thread
        // during onCreate — do the whole cache-load + maybe-refetch sequence in the
        // background and let applyRemoteBlocklist's set-swap publish the result whenever
        // it's ready. The built-in set above already covers requests made before that.
        bgExecutor.execute(() -> {
            File cacheFile = new File(getFilesDir(), BLOCKLIST_FILE);
            if (cacheFile.exists()) {
                try {
                    String cached = readFile(cacheFile);
                    if (!cached.isEmpty()) applyRemoteBlocklist(cached);
                } catch (Exception ignored) {}
            }
            long lastFetch = prefs.getLong(PREF_BLOCKLIST_TIME, 0);
            if (System.currentTimeMillis() - lastFetch > BLOCKLIST_TTL_MS) {
                fetchRemoteBlocklist();
            }
        });
    }

    private static String readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
        fis.close();
        return bos.toString("UTF-8");
    }

    private void fetchRemoteBlocklist() {
        bgExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(BLOCKLIST_URL).openConnection();
                conn.setConnectTimeout(8000);
                // A ~2.7MB list over a slow connection needs more than the old 8s budget.
                conn.setReadTimeout(20000);
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
                FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), BLOCKLIST_FILE));
                fos.write(content.getBytes("UTF-8"));
                fos.close();
                prefs.edit().putLong(PREF_BLOCKLIST_TIME, System.currentTimeMillis()).apply();
                applyRemoteBlocklist(content);
            } catch (Exception ignored) {}
        });
    }

    // Always called from bgExecutor (cache-load path or post-download path) — builds the
    // new sets off the UI thread, then publishes with a single volatile reference swap
    // instead of clear()+addAll() into a live set, which at ~111k entries would otherwise
    // be real main-thread work if called via handler.post like the old ~60-entry version did.
    private void applyRemoteBlocklist(String content) {
        Set<String> hosts    = new HashSet<>(AD_HOSTS);
        Set<String> patterns = new HashSet<>(AD_URL_PATTERNS);
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("HOST:"))    hosts.add(line.substring(5).trim());
            else if (line.startsWith("PATTERN:")) patterns.add(line.substring(8).trim());
        }
        if (hosts.size() > AD_HOSTS.size())             adHosts = hosts;
        if (patterns.size() > AD_URL_PATTERNS.size())   adPatterns = patterns;
    }

    private boolean isAdUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            // Walk up the domain labels (a.b.example.com -> b.example.com -> example.com -> com)
            // doing an O(1) HashSet lookup at each step, instead of the old approach of scanning
            // every one of ~111k blocklist entries per request with host.endsWith("."+h) — that
            // was fine at ~30 curated hosts, but would mean tens of millions of string
            // comparisons per page once the list covers general ad/tracker domains.
            Set<String> hosts = adHosts;
            while (host != null) {
                if (hosts.contains(host)) return true;
                int dot = host.indexOf('.');
                host = (dot >= 0 && dot + 1 < host.length()) ? host.substring(dot + 1) : null;
            }
            for (String p : adPatterns) if (url.contains(p)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ─── In-app update (requires every CI build to share one signature — see
    // RELEASE_KEYSTORE_* secrets in the build workflow; installing over a differently-
    // signed APK is always rejected by Android no matter what this code does) ──────────
    private static final String UPDATE_API_URL =
        "https://api.github.com/repos/jdans01/BraveTV/releases/latest";
    private static final String PREF_UPDATE_CHECK_TIME = "update_check_time";
    private static final long   UPDATE_CHECK_TTL_MS = 12 * 60 * 60 * 1000L;

    // Plain !equals() isn't enough here — the "latest" GitHub release can be OLDER than
    // what's actually installed right after a local/manual build bumps VERSION_NAME ahead
    // of the last tagged release (confirmed on-device: it offered to "update" v2.1 down to
    // the still-tagged v2.0). Numeric per-segment comparison so it only ever prompts
    // forward.
    private static boolean isNewerVersion(String latest, String current) {
        String[] a = latest.split("\\.");
        String[] b = current.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = i < a.length ? parseVersionPart(a[i]) : 0;
            int bv = i < b.length ? parseVersionPart(b[i]) : 0;
            if (av != bv) return av > bv;
        }
        return false;
    }

    private static int parseVersionPart(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    private void checkForUpdate() {
        if (System.currentTimeMillis() - prefs.getLong(PREF_UPDATE_CHECK_TIME, 0) < UPDATE_CHECK_TTL_MS)
            return;
        bgExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(UPDATE_API_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "BraveTVBrowser/" + BuildConfig.VERSION_NAME);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                if (conn.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();
                prefs.edit().putLong(PREF_UPDATE_CHECK_TIME, System.currentTimeMillis()).apply();

                JSONObject release = new JSONObject(sb.toString());
                String tag = release.optString("tag_name", "");
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                if (latest.isEmpty() || !isNewerVersion(latest, BuildConfig.VERSION_NAME)) return;

                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                if (apkUrl == null) return;
                String finalUrl = apkUrl, finalLatest = latest;
                handler.post(() -> showUpdateDialog(finalLatest, finalUrl));
            } catch (Exception ignored) {}
        });
    }

    private void showUpdateDialog(String version, String apkUrl) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("🦁 Nueva versión disponible")
            .setMessage("BraveTV Browser v" + version + " está disponible (tenés v"
                + BuildConfig.VERSION_NAME + "). ¿Actualizar ahora?")
            .setPositiveButton("Actualizar", (d, w) -> startUpdateDownload(apkUrl, version))
            .setNegativeButton("Más tarde", null)
            .show();
    }

    private void startUpdateDownload(String apkUrl, String version) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this,
                "Activá \"Instalar apps desconocidas\" para BraveTV y volvé a intentar",
                Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        Toast.makeText(this, "⬇️ Descargando actualización…", Toast.LENGTH_SHORT).show();
        bgExecutor.execute(() -> {
            try {
                File dir = new File(getExternalFilesDir(null), "updates");
                if (!dir.exists()) dir.mkdirs();
                File apkFile = new File(dir, "BraveTVBrowser-" + version + ".apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                java.io.InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(apkFile);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                out.close();
                conn.disconnect();
                handler.post(() -> installApk(apkFile));
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(MainActivity.this,
                    "❌ No se pudo descargar la actualización", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void installApk(File apkFile) {
        try {
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.bravetv.browser.fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "❌ No se pudo abrir el instalador", Toast.LENGTH_SHORT).show();
        }
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
            handler.post(() -> {
                // Entering fullscreen video while pointer mode is on is how control got lost:
                // the cursor is easy to lose track of over a fullscreen video, D-pad stops
                // reaching the navbar (pointer mode claims it unconditionally), and there was
                // no obvious way back. A mouse pointer isn't useful while watching a video
                // anyway, so just drop pointer mode on entering fullscreen instead of letting
                // the user get stuck in it.
                if (full && pointerMode) togglePointerMode();
                setNavBarHidden(full);
            });
        }
    }

    // ─── Navbar auto-hide ─────────────────────────────────────────────────────
    // Used to only hide for video fullscreen (BraveTV.onFullscreen()). Nothing hid it while just
    // browsing a regular page, and once hidden there was no way back: btnRevealNav sat there
    // "visible" but WebView always had Android focus and ate every D-pad press itself (scroll on
    // UP/DOWN, forwarded into the page on LEFT/RIGHT/CENTER), so the native navbar buttons —
    // lupa, back/forward, cursor toggle, btnRevealNav itself — were never reachable. Confirmed
    // on-device: once past the first page element there was no way back to BraveTV's own chrome.
    private static final long AUTO_HIDE_MS = 6000;
    private final Runnable autoHideNav = () -> { if (!navBarHidden) setNavBarHidden(true); };

    private void scheduleAutoHide() {
        handler.removeCallbacks(autoHideNav);
        if (homeScreen.getVisibility() != View.VISIBLE) handler.postDelayed(autoHideNav, AUTO_HIDE_MS);
    }

    private void setNavBarHidden(boolean hide) {
        navBarHidden = hide;
        if (hide) {
            handler.removeCallbacks(autoHideNav);
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
            scheduleAutoHide();
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
        homeQuality    = findViewById(R.id.homeQuality);
        if (homeQuality != null) homeQuality.setOnClickListener(v -> showQualityDialog());
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
        videoQualityCap = prefs.getString("videoQualityCap", "auto");
        updateHomeQualityLabel();
        initBlocklist(); // seed built-in + load cached remote list
        checkForUpdate();

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
        float maxY = h - dp(32);
        // Clamp keeping arrow tip inside screen
        float cx = Math.max(0, Math.min(x, w - dp(28)));
        float cy = Math.max(0, Math.min(y, maxY));
        // Requested position overshot the top/bottom edge — the cursor has nowhere further
        // to go, so scroll the page instead of just stopping dead. Matches plain D-pad mode,
        // which already scrolls on DPAD_DOWN once nothing more is focusable below.
        if (y > maxY) webView.evaluateJavascript("window.scrollBy(0,220)", null);
        else if (y < 0) webView.evaluateJavascript("window.scrollBy(0,-220)", null);
        cursor.set(cx, cy);
        cursorDot.setX(cx);
        cursorDot.setY(cy);
        cursorDot.setVisibility(View.VISIBLE);
        cursorDot.setAlpha(1f);
    }

    private void simulateClick(float x, float y) {
        lastGestureAtMs = System.currentTimeMillis();
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

    // ─── YouTube ad-data stripping (JSON.parse patch, à la TizenTube) ────────
    // injectAdBlocker's CSS/skip-loop hides or fast-forwards an ad *after* YouTube's
    // player has already been told one exists — which is why a gray placeholder frame
    // shows up for the brief moment before our JS catches up. YouTube TV/Web decide
    // whether to show an ad entirely from fields (adPlacements/playerAds/adSlots) inside
    // the JSON responses their own code parses. Stripping those fields before the
    // player ever reads them means it never schedules an ad in the first place — no
    // placeholder to paper over. Called from onPageStarted (as early as this WebView
    // can inject) instead of onPageFinished so the patch is in place before YouTube's
    // bootstrap script runs its first JSON.parse; only scoped to YouTube modes since
    // blanket-patching JSON.parse on arbitrary sites is unnecessary risk for no benefit.
    private void injectYouTubeAdDataBlock(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "if(window._btvJsonAdBlock) return; window._btvJsonAdBlock=true;" +
            "var _origParse=JSON.parse;" +
            "JSON.parse=function(){" +
            "  var r=_origParse.apply(this,arguments);" +
            "  try{" +
            "    if(r&&typeof r==='object'){" +
            "      if(r.adPlacements) r.adPlacements=[];" +
            "      if(r.playerAds) r.playerAds=false;" +
            "      if(r.adSlots) r.adSlots=[];" +
            "      var ov=r.playerOverlays&&r.playerOverlays.playerOverlayRenderer;" +
            "      if(ov&&ov.timelyActionRenderers){" +
            "        ov.timelyActionRenderers=ov.timelyActionRenderers.filter(function(a){" +
            "          var ty=a&&a.timelyActionRenderer&&a.timelyActionRenderer.type;" +
            "          return ty!=='TIMELY_ACTION_TYPE_SHOPPING';" +
            "        });" +
            "      }" +
            "    }" +
            "  }catch(e){}" +
            "  return r;" +
            "};" +
            "window.JSON.parse=JSON.parse;" +
            // YouTube TV's sandboxed app namespace(s) keep their own JSON reference —
            // patch those too or parsing inside the TV UI shell bypasses the above.
            "if(window._yttv){for(var k in window._yttv){" +
            "  if(window._yttv[k]&&window._yttv[k].JSON&&window._yttv[k].JSON.parse)" +
            "    window._yttv[k].JSON.parse=JSON.parse;" +
            "}}" +
            "})()", null);
    }

    // ─── Escudo visual: crea el DOM/CSS una sola vez por documento ───────────
    // Separado de injectAdBlocker (que corre en onPageFinished, ya tarde) para poder
    // llamarlo también desde onPageStarted — necesita existir ANTES de que la propia
    // pantalla de carga nativa de YouTube aparezca, no después.
    private void ensureShieldDom(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "if(document.getElementById('_btv_shield')) return;" +
            "var s=document.createElement('style');s.id='_btv_shield_css';" +
            "s.textContent=" +
            "'@keyframes btvPulse{0%{transform:scale(1);opacity:.55}" +
            "50%{transform:scale(1.18);opacity:.95}100%{transform:scale(1);opacity:.55}}" +
            ".btv-shield{position:fixed;inset:0;z-index:2147483647;display:none;" +
            "align-items:center;justify-content:center;flex-direction:column;gap:14px;" +
            "background-color:#0b0714;" +
            // Default (no thumbnail yet): purple gradient. When a poster/thumbnail is
            // available the tick handler overwrites background-image inline with it plus a
            // dark gradient layered on top — set as a plain background-image, not a filter,
            // so it can't blur/darken the .ring/.msg children along with it.
            "background-image:radial-gradient(circle at 50% 45%,#1a1033 0%,#0b0714 70%);" +
            "background-size:cover;background-position:center;" +
            "font-family:sans-serif;pointer-events:none}" +
            ".btv-shield.on{display:flex}" +
            ".btv-shield .ring{width:64px;height:64px;border-radius:50%;" +
            "background:radial-gradient(circle,rgba(192,132,252,0.35),transparent 70%);" +
            "animation:btvPulse 1.1s ease-in-out infinite;" +
            "display:flex;align-items:center;justify-content:center;font-size:30px}" +
            ".btv-shield .msg{font-size:15px;letter-spacing:.3px;color:#fff;" +
            "text-shadow:0 1px 6px rgba(0,0,0,0.8)}';" +
            "document.head.appendChild(s);" +
            "var d=document.createElement('div');d.id='_btv_shield';d.className='btv-shield';" +
            "d.innerHTML='<div class=\"ring\">🛡️</div>" +
            "<div class=\"msg\" id=\"_btv_shield_msg\">Anuncio bloqueado</div>';" +
            "document.documentElement.appendChild(d);})()", null);
    }

    // Extracts an 11-char YouTube video ID from a watch URL — v= query param (web,
    // /watch?v=... and TV's #/watch?v=...) or the youtu.be/<id> path form.
    private static String extractYouTubeVideoId(String url) {
        if (url == null) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .matcher(url);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    // Shows the shield immediately with the video's thumbnail, covering the native
    // loading screen from the very start of navigation instead of only reacting once
    // already-stuck. _btvPendingPlay clears itself once Capa 2's loop sees real playback
    // start, or after a 15s safety timeout so a genuinely broken video never gets stuck
    // hidden behind the shield forever.
    private void showShieldForVideo(WebView view, String url) {
        String videoId = extractYouTubeVideoId(url);
        if (videoId == null) return;
        ensureShieldDom(view);
        view.evaluateJavascript(
            "(function(){" +
            "var sh=document.getElementById('_btv_shield');if(!sh)return;" +
            "sh.classList.add('on');" +
            "sh.style.backgroundImage='linear-gradient(rgba(8,4,16,.6),rgba(8,4,16,.8))," +
            "url(\"https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg\")';" +
            "var m=document.getElementById('_btv_shield_msg');" +
            "if(m)m.textContent='Cargando\\u2026';" +
            "window._btvPendingPlay=true;window._btvPendingPlayAt=Date.now();" +
            "})()", null);
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
            // Generic (YouTube or not) — deliberately narrow patterns only: "-ad-"/"ad-"
            // requires the hyphen boundary so it doesn't catch ordinary words that merely
            // contain "ad" (header, load, read…), and the rest are standard, named
            // conventions (Google Publisher Tag's div-gpt-ad-*, AdSense's ins.adsbygoogle,
            // known ad-iframe hosts) rather than guesses.
            "'[id^=\"ad_\"]','[class*=\"-ad-\"]','[id*=\"-ad-\"]'," +
            "'[class^=\"ad-\"]','[id^=\"ad-\"]','[id^=\"div-gpt-ad\"]'," +
            "'ins.adsbygoogle','iframe[src*=\"doubleclick.net\"]'," +
            "'iframe[src*=\"googlesyndication.com\"]'" +
            "].join(',')+" +
            "'{display:none!important;visibility:hidden!important;max-height:0!important}';" +
            "document.head.appendChild(s);})()", null);

        // ── Capa 1b: escudo visual — tapa el placeholder gris nativo del
        // reproductor mientras se detecta/salta un anuncio, en vez de dejarlo
        // asomar durante el fast-forward.
        ensureShieldDom(view);

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
            "var _btvStallTicks=0,_btvLastTime=-1;" +
            "setInterval(function(){" +
            "  var sk=document.querySelector(" +
            "    '.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern," +
            "     .ytp-ad-skip-button-slot button,ytlr-skip-button-renderer button," +
            "     button[class*=\"skip\"]');" +
            // adMarked = an explicit ad signal (skip button present, or a known ad-container
            // class on screen) — the ONLY thing allowed to fast-forward/seek the video below.
            "  var adMarked=!!sk||!!document.querySelector(" +
            "    '.ad-showing,.ad-interrupting,.ytp-ad-player-overlay,ytlr-ad-placement-renderer');" +
            // stalled = the player looks stuck (not paused, no progress, not enough buffer) for
            // several seconds straight — covers the case where a blocked ad request leaves
            // YouTube waiting on a response that'll never come, showing its spinner over gray.
            // Cosmetic ONLY: a plain network hiccup during real content looks identical for the
            // first second or two, and earlier this ran on a 400ms trigger that force-seeked
            // ANY brief buffering straight to the end of the video — i.e. it was skipping
            // real playback, not just ads. A much longer threshold (~3s) and never touching
            // playbackRate/currentTime for this case is what keeps it cosmetic-only.
            "  var vs=document.querySelector('video');" +
            "  var stalled=false;" +
            "  if(vs&&!vs.paused&&vs.readyState<3){" +
            "    if(vs.currentTime===_btvLastTime){_btvStallTicks++;}" +
            "    else{_btvStallTicks=0;_btvLastTime=vs.currentTime;}" +
            "    stalled=_btvStallTicks>=15;" +
            "  }else{_btvStallTicks=0;_btvLastTime=vs?vs.currentTime:-1;}" +
            // _btvPendingPlay: set by showShieldForVideo() the moment navigation to a video
            // starts, so the shield (with that video's thumbnail) is already up before
            // YouTube's own loading screen would be — clears once playback actually begins,
            // or after 15s regardless so a genuinely broken video doesn't hide behind it.
            "  if(window._btvPendingPlay){" +
            "    var playing=vs&&vs.currentTime>0.3&&!vs.paused&&vs.readyState>=3;" +
            "    if(playing||Date.now()-window._btvPendingPlayAt>15000)window._btvPendingPlay=false;" +
            "  }" +
            "  var show=adMarked||stalled||!!window._btvPendingPlay;" +
            // Escudo visible mientras dure el anuncio/atasco — se chequea antes de cualquier
            // 'return' de abajo para que no parpadee. Usa la miniatura del propio video
            // (atributo poster, el que ya pone YouTube) en vez del fondo genérico cuando la
            // tiene, para que se vea como 'cargando' en vez de una pantalla gris sin contexto.
            "  var shield=document.getElementById('_btv_shield');" +
            "  if(shield){" +
            "    shield.classList.toggle('on',show);" +
            "    var msgEl=document.getElementById('_btv_shield_msg');" +
            "    if(show&&vs&&vs.poster){" +
            "      shield.style.backgroundImage='linear-gradient(rgba(8,4,16,.6),rgba(8,4,16,.8)),url(\"'+vs.poster+'\")';" +
            "      if(msgEl)msgEl.textContent=adMarked?'Anuncio bloqueado':'Cargando\\u2026';" +
            "    } else if(!show){ shield.style.backgroundImage=''; }" +
            "  }" +
            "  if(sk){sk.click();return;}" +
            // Fast-forward SOLO ante anuncio confirmado — nunca por un simple atasco.
            "  if(adMarked){" +
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

        // ── Capa 3: botones genéricos de "saltar anuncio" fuera de YouTube ────
        // Cada sitio hace su propia pantalla de "anuncio bloqueado"/interstitial de forma
        // distinta, así que esto es necesariamente heurístico — busca SOLO frases que de
        // verdad signifiquen "saltar anuncio" (no "cerrar"/"continuar" genéricos, que
        // aparecen en banners de cookies, modales y demás UI real y terminarían clickeados
        // por error) para mantener el riesgo de falso positivo bajo.
        view.evaluateJavascript(
            "(function(){" +
            "if(window._btvGenSkip) return; window._btvGenSkip=true;" +
            "var RE=/^(skip ?ad[s]?|saltar anuncio|omitir anuncio|cerrar anuncio|" +
            "pular an[uú]ncio|close ?ad[s]?)$/i;" +
            "setInterval(function(){" +
            "  var els=document.querySelectorAll('button,a,[role=\"button\"],[onclick]');" +
            "  for(var i=0;i<els.length;i++){" +
            "    var t=(els[i].textContent||'').trim();" +
            "    if(!t||!RE.test(t))continue;" +
            "    var r=els[i].getBoundingClientRect();" +
            "    if(r.width>0&&r.height>0){els[i].click();break;}" +
            "  }" +
            "},1000);" +
            "})()", null);
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

            // A card whose poster/thumbnail hasn't loaded yet (lazy-loaded <img>, common on
            // search/catalog pages) collapses its wrapping link to a 0x0 rect until the image
            // arrives — requiring width>0&&height>0 silently dropped every result without an
            // already-loaded image from the focusable set, matching 'only picks results with a
            // picture'. Keep zero-size elements as long as they're actually laid out (not
            // display:none / detached), and only drop genuinely hidden ones.
            "function visible(e){" +
            "  var r=e.getBoundingClientRect();" +
            "  if(r.top>=window.innerHeight||r.bottom<=0) return false;" +
            "  if(r.width===0&&r.height===0&&e.offsetParent===null) return false;" +
            "  var cs=getComputedStyle(e);" +
            "  return cs.visibility!=='hidden'&&cs.display!=='none';" +
            "}" +
            "function getEls(){" +
            "  return Array.from(document.querySelectorAll(SEL)).filter(visible);" +
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
                if (currentMode == NAV_YT_TV || currentMode == NAV_YT_WEB) {
                    injectYouTubeAdDataBlock(view);
                    // Cover with the video's own thumbnail from the first moment of
                    // navigation — otherwise YouTube's native loading screen shows gray
                    // and unbranded for however long it takes before anything else here
                    // would notice and react.
                    showShieldForVideo(view, url);
                }
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

                // Cap video quality at (at most) the user's chosen ceiling — see
                // "📺 Calidad" on the home screen. Forcing the absolute highest quality
                // unconditionally (the old behavior, hardcoded to a floor of 1080p) is what
                // made playback choppy on a smaller/weaker TV: the decoder works far harder
                // for 4K than a 32"-50" screen can even resolve, and a hard floor meant
                // adaptive streaming could never drop down when the network or decoder
                // genuinely couldn't keep up. 'tiny' as the floor here lets it actually adapt;
                // only the ceiling is capped.
                // Unguarded before: onPageFinished can fire more than once for the same page
                // (redirects, iframes), and every extra firing stacked another setInterval that
                // ran forever — the likely cause of the app getting slower the longer it's used.
                // injectAdBlocker's own loop and injectDpadNavigation already guard themselves
                // (window._btvSkip2 / window._btvNav); this one and the two below didn't.
                String[] qCaps = qualitiesForCap();
                StringBuilder qb = new StringBuilder("[");
                for (int qi = 0; qi < qCaps.length; qi++) {
                    if (qi > 0) qb.append(',');
                    qb.append('\'').append(qCaps[qi]).append('\'');
                }
                qb.append(']');
                String qualitiesJs = qb.toString();
                String maxQ = qCaps[0];
                view.evaluateJavascript(
                    "(function(){" +
                    "if(window._btvHD) return; window._btvHD=true;" +
                    "var QUALITIES=" + qualitiesJs + ";" +
                    "function forceHD(){" +
                    // Standard YouTube web player
                    "  var p=document.getElementById('movie_player');" +
                    "  if(p){" +
                    "    try{if(p.setPlaybackQualityRange)p.setPlaybackQualityRange('tiny','" + maxQ + "');}catch(e){}" +
                    "    try{" +
                    "      var avail=p.getAvailableQualityLevels&&p.getAvailableQualityLevels();" +
                    "      var cur=p.getPlaybackQuality&&p.getPlaybackQuality();" +
                    "      if(avail&&avail.length>0&&QUALITIES.indexOf(cur)<0){" +
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
                    "      if(pp&&pp.setPlaybackQualityRange)pp.setPlaybackQualityRange('tiny','" + maxQ + "');" +
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
                        "(function(){" +
                        "if(window._btvGeo) return; window._btvGeo=true;" +
                        "var kw=['country','región','region','not available'," +
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
                    "(function(){" +
                    "if(window._btvFS) return; window._btvFS=true;" +
                    "var _fs=false;" +
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
                String scheme = req.getUrl().getScheme();
                // Ad-heavy sites (Cuevana and similar) routinely redirect through non-http
                // schemes — intent://, market://, whatsapp://, tel:... — for apps this TV
                // doesn't have installed. Handing those to WebView.loadUrl() lets it try to
                // resolve the Intent itself and throw ActivityNotFoundException with nothing on
                // the call stack to catch it, which kills the whole app (reported as "me saca
                // de la aplicación"). Resolve and launch ourselves; swallow anything that fails.
                if (scheme != null && !scheme.equals("http") && !scheme.equals("https")) {
                    try {
                        android.content.Intent intent = android.content.Intent.parseUri(
                            url, android.content.Intent.URI_INTENT_SCHEME);
                        intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
                        intent.setComponent(null);
                        intent.setSelector(null);
                        if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                    } catch (Exception ignored) {}
                    return true;
                }
                if (req.hasGesture()) lastGestureAtMs = System.currentTimeMillis();
                // Redirect/popunder ads (Cuevana and the like) hijack navigation to a totally
                // different domain — a timer, an onload handler, a transparent overlay that
                // "catches" the next touch. Two separate signals, not one blanket rule:
                //  1) the target is a KNOWN ad host (same blocklist resources already use) —
                //     always block it, full stop, timing doesn't matter.
                //  2) otherwise, only block if there's been no real tap (D-pad OK, pointer
                //     click, or a gestured nav) in the last few seconds. A flat "block anything
                //     without hasGesture() on THIS exact hop" was too strict: these sites chain
                //     through async redirects (a timer, an XHR lookup) after a real click before
                //     landing on the actual video, and Chromium's own gesture flag doesn't
                //     survive that async gap even though the click was genuine — that blocked
                //     the real content along with the ads. The grace window tells those apart.
                String curHost = null, newHost = null;
                try { curHost = Uri.parse(view.getUrl()).getHost(); } catch (Exception ignored) {}
                try { newHost = req.getUrl().getHost(); } catch (Exception ignored) {}
                boolean crossDomain = curHost != null && newHost != null
                    && !curHost.equalsIgnoreCase(newHost)
                    && !newHost.endsWith("." + curHost) && !curHost.endsWith("." + newHost);
                boolean recentGesture = req.hasGesture()
                    || (System.currentTimeMillis() - lastGestureAtMs) <= GESTURE_GRACE_MS;
                if (isAdUrl(url) || (crossDomain && !recentGesture)) {
                    Toast.makeText(MainActivity.this, "🛡 Redirección bloqueada", Toast.LENGTH_SHORT).show();
                    return true;
                }
                view.loadUrl(currentMode != NAV_INCOGNITO ? applyRegion(url) : url);
                // SPA navigation to a new video (YouTube TV switches without a full page
                // load) — onPageStarted won't fire for this, so prime the shield here too.
                if (currentMode == NAV_YT_TV || currentMode == NAV_YT_WEB)
                    showShieldForVideo(view, url);
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
                customView = view;
                customViewCallback = cb;
                navBar.setVisibility(View.GONE);
                webView.setVisibility(View.GONE);
                // Same reasoning as BraveTVInterface.onFullscreen(): pointer mode doesn't help
                // while watching video and is how control got lost before — drop it outright
                // rather than just hiding the dot, which left it stranded with nothing able to
                // move it (pointer mode only reacts while webView is VISIBLE, which just
                // became false) or turn it back on.
                if (pointerMode) togglePointerMode();
                LinearLayout c = findViewById(R.id.fullscreenContainer);
                c.addView(view);
                c.setVisibility(View.VISIBLE);
                // Native fullscreen views (YouTube's own player, etc.) generally handle D-pad
                // play/pause/seek themselves once focused — but only if something actually
                // hands them Android focus, which nothing does by default here.
                view.setFocusable(true);
                view.setFocusableInTouchMode(true);
                view.requestFocus();
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
                customView = null;
                customViewCallback = null;
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Whatever ends up handling a key — WebView's own native scroll on DPAD_UP, a focused
        // navbar Button's built-in nextFocusRight/Left walk between buttons, or our onKeyDown
        // fallback below — all of those consume the event inside super.dispatchKeyEvent() before
        // Activity.onKeyDown ever sees it, which is where scheduleAutoHide() used to live. Net
        // effect, confirmed on-device: moving between navbar buttons never reset the auto-hide
        // timer, so it could hide the bar out from under you mid-navigation. Only a catch here,
        // ahead of everything, sees every key reliably.
        if (event.getAction() == KeyEvent.ACTION_DOWN) scheduleAutoHide();

        // Pointer mode must own every D-pad key itself, before anything below gets a chance at
        // it. Two different things were stealing it: (1) whatever View still held Android focus
        // right after the OK press that turned pointer mode on — usually the cursor-toggle
        // button itself — would eat the *next* OK as its own click (i.e. toggling pointer back
        // off) once this method fell through to super.dispatchKeyEvent(), since that hands the
        // event to the focused View first; (2) the "reveal hidden navbar on any key" branch right
        // below this one, which swallowed OK whenever the navbar had auto-hidden. Both are fixed
        // by handling the click/move here, unconditionally, before either path runs.
        if (event.getAction() == KeyEvent.ACTION_DOWN && pointerMode
                && webView.getVisibility() == View.VISIBLE) {
            switch (event.getKeyCode()) {
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
                    if (navBarHidden) setNavBarHidden(false);
                    simulateClick(cursor.x, cursor.y);
                    return true;
            }
        }

        // Same bypass, same fix: reveal-on-any-key used to live in onKeyDown too, so e.g. DOWN
        // never revealed the bar — it went straight into the page (WebView's native scroll, or
        // page-internal focus shift) and onKeyDown never ran. Catch it here instead, for every
        // key except BACK (that one gets its own first-press-reveals handling below, with
        // slightly different semantics around the home screen).
        if (event.getAction() == KeyEvent.ACTION_DOWN && navBarHidden
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            setNavBarHidden(false);
            return true;
        }

        // WebView has its own default arrow-key handling (native page scroll) and consumes
        // DPAD_UP via the view hierarchy before Activity.onKeyDown's fallback ever runs —
        // confirmed on-device: a Log.d placed in onKeyDown's UP branch never once fired for a
        // real key press, only for keys (LEFT/RIGHT/CENTER/BACK/SEARCH) WebView doesn't claim
        // natively. Has to be caught one level higher, here, before super hands it to the view
        // tree, or the native navbar (lupa/back/forward/cursor/▲) is never reachable by remote.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && !pointerMode && webView.getVisibility() == View.VISIBLE && webView.hasFocus()) {
            // navBarHidden is already false here — the reveal check above caught that case.
            View first = findViewById(R.id.btnSearch);
            if (first != null) first.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Native fullscreen video (onShowCustomView) replaces the WebView with its own
            // view and nothing else routes BACK to exiting it — without this the remote has
            // no way out of fullscreen at all once a video is playing full-screen.
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                return true;
            }
            // Universal escape hatch: whatever state pointer mode leaves things in — cursor
            // obscured, navbar unreachable because pointer mode claims every D-pad key — BACK
            // always gets you out of it first, consuming this press rather than doing its
            // normal page-back/home job. A second BACK does that normally.
            if (pointerMode) {
                togglePointerMode();
                return true;
            }
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
        // Pointer mode is handled entirely in dispatchKeyEvent() now — see the comment there for
        // why it has to be caught ahead of this method.
        // Only intercept D-pad when WebView has focus AND navbar is hidden.
        // If navbar is visible, let super handle it so Android moves focus between buttons.
        if (!pointerMode && webView.getVisibility() == View.VISIBLE && webView.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                webView.evaluateJavascript("window.scrollBy(0,220)", null);
                return true;
            }
            // DPAD_UP is handled in dispatchKeyEvent() — WebView consumes it there before this
            // method ever runs (see that override for why).
            // LEFT/RIGHT/CENTER: Activity's default View.FocusFinder only walks native Android
            // views, so once the navbar is hidden there's nothing else for it to land on and
            // the key just dies here — confirmed on-device (stuck past the first focused page
            // element, Buscar/Duck.ai/☰ all unreachable). The page's own injectDpadNavigation()
            // listener is what actually walks focus between page elements, so these three still
            // need to be forwarded into it as a synthetic DOM event, same as before. keyCode on
            // a KeyboardEventInit is spec-inert in Chromium (read-only, meant to come from the
            // real native event) — defineProperty is the only way to actually set it post-construct.
            int jsKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? 37
                : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 39 : -1;
            if (jsKey != -1) {
                webView.evaluateJavascript(
                    "(function(){var e=new KeyboardEvent('keydown',{bubbles:true,cancelable:true});" +
                    "Object.defineProperty(e,'keyCode',{get:function(){return " + jsKey + ";}});" +
                    "Object.defineProperty(e,'which',{get:function(){return " + jsKey + ";}});" +
                    "document.dispatchEvent(e);})()", null);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                lastGestureAtMs = System.currentTimeMillis();
                webView.evaluateJavascript(
                    "(function(){var el=document.activeElement;" +
                    "if(el&&el!==document.body){el.click();}else{" +
                    "var a=document.querySelectorAll('a[href],button');" +
                    "if(a.length)a[0].click();}})()", null);
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
