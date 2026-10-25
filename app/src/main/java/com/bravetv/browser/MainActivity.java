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
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int NAV_MOVIES   = 4;
    private static final int NAV_ANIME    = 5;

    private int currentMode = NAV_BRAVE;

    private WebView webView;
    private EditText urlBar;
    private boolean urlBarEditing = false;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout navBar;
    private Button btnVpn;
    private ScrollView homeScreen;
    private TextView homeVpnStatus;
    private TextView homeQuality;
    private TextView homeUpdateStatus;
    private LinearLayout recentsList;
    private View favoritesSection;
    private LinearLayout favoritesList;
    private Button btnFavorite;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean navBarHidden = false;
    // Last time we saw a real tap/click from the user (D-pad OK, pointer click, or a
    // gestured page navigation) — see shouldOverrideUrlLoading's redirect guard.
    private volatile long lastGestureAtMs = 0;
    private static final long GESTURE_GRACE_MS = 4000;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean navBarHiddenBeforeFullscreen = true;
    private final AtomicInteger blockedCount = new AtomicInteger();
    private TextView btnBlockCount;
    private TextView homeBlockCount;
    private View btnRevealNav;
    private TextView btnCursorFloat;
    private LinearLayout benefitsRows;
    private LinearLayout benefitsAdminTools;
    private Button btnBenefitsManage;
    private JSONArray benefitsCatalog = new JSONArray();
    private boolean benefitsAdminMode = false;
    private static final boolean BENEFITS_CENTER_ENABLED = false;
    private static final String BENEFITS_FILE = "benefits_catalog.json";
    private static final int REQ_BENEFITS_IMPORT = 7310;
    private static final int REQ_BENEFITS_EXPORT = 7311;
    private static final String[] BENEFIT_CATEGORY_IDS = {"privacy", "entertainment", "ai", "offers"};
    private static final String[] BENEFIT_CATEGORY_NAMES = {
        "Privacidad", "Entretenimiento", "Inteligencia artificial", "Ofertas"
    };
    private static final String[] BENEFIT_CATEGORY_SUBTITLES = {
        "VPN y seguridad", "Apps y streaming", "IA y productividad", "Productos y accesorios"
    };

    // videoId -> [[startSec,endSec,categoryIndex],...] already resolved this session, so
    // re-watching/looping a video (common on YouTube TV) doesn't re-hit the API.
    private final Map<String, float[][]> sponsorSegmentCache = new HashMap<>();

    // ─── Pointer / cursor mode ────────────────────────────────────────────────
    private boolean pointerMode = false;
    private boolean pointerHoverActive = false;
    private volatile boolean youtubeTvTextEntry = false;
    private View cursorDot;
    private android.widget.ImageButton btnCursorToggle;
    private Button btnHideNav;
    private final PointF cursor = new PointF(640, 360);
    private static final int CURSOR_STEP = 32;
    private static final long CURSOR_IDLE_HIDE_MS = 6000;
    private final Runnable hideIdleCursor = () -> {
        if (!pointerMode || cursorDot == null || cursorDot.getVisibility() != View.VISIBLE) return;
        cursorDot.animate().cancel();
        cursorDot.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (pointerMode) cursorDot.setVisibility(View.GONE);
        }).start();
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

    // ─── Calidad de video ──────────────────────────────────────────────────────
    // Forcing the absolute highest available quality unconditionally (the old behavior)
    // is what was choking playback on weaker/smaller TVs — the decoder has to work much
    // harder for 4K than the screen can even resolve. QUALITY_* lets the user cap the
    // ceiling to something their TV size can actually decode smoothly.
    private static final String[] QUALITY_LEVELS = {"hd2160","hd1440","hd1080","hd720","large","medium"};
    private static final String[] QUALITY_CAPS   = {"auto","hd1080","hd720","large"};
    private static final String[] QUALITY_LABELS = {
        "🤖 Automática — máxima disponible (hasta 4K)",
        "🖥️ TV grande 55\"+ — 1080p",
        "📺 TV mediana 32\"–50\" — 720p",
        "📱 TV chica <32\" — 480p"
    };
    private static final String[] QUALITY_SHORT = {"Máx. 4K", "1080p", "720p", "480p"};
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
    // Exact host-anchored path rules (HOSTPATH:domain.example/path), kept separate from
    // whole-domain rules so a path-level ad rule doesn't disable every resource on a host.
    private volatile Map<String, Set<String>> adHostPaths = new HashMap<>();

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
    // These tokens are only meaningful as YouTube ad signals. Applying them to arbitrary
    // domains caused false positives such as ordinary /ads/ or /ima/ content paths.
    private static final Set<String> YOUTUBE_ONLY_URL_PATTERNS = new HashSet<>(Arrays.asList(
        "ctier=L", "ctier%3DL", "/pagead/", "/ads/", "/adview", "ad_type=",
        "/ptracking", "ad_video_id=", "&ad_cpn=", "&oad=1", "&adformat=", "&adurl=",
        "/ima/", "consent.youtube"
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
        Map<String, Set<String>> hostPaths = new HashMap<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("HOST:")) {
                String host = line.substring(5).trim().toLowerCase(Locale.US);
                if (isValidBlockHost(host)) hosts.add(host);
            } else if (line.startsWith("PATTERN:")) {
                String pattern = line.substring(8).trim();
                if (!pattern.isEmpty() && pattern.length() <= 200) patterns.add(pattern);
            } else if (line.startsWith("HOSTPATH:")) {
                String rule = line.substring(9).trim().toLowerCase(Locale.US);
                int slash = rule.indexOf('/');
                if (slash <= 0 || slash == rule.length() - 1) continue;
                String host = rule.substring(0, slash);
                String path = rule.substring(slash);
                if (!isValidBlockHost(host) || path.length() > 200 || path.contains("*")
                        || path.contains("?")) continue;
                hostPaths.computeIfAbsent(host, ignored -> new HashSet<>()).add(path);
            }
        }
        if (hosts.size() > AD_HOSTS.size())             adHosts = hosts;
        if (patterns.size() > AD_URL_PATTERNS.size())   adPatterns = patterns;
        adHostPaths = hostPaths;
    }

    private static boolean isValidBlockHost(String host) {
        if (host == null || host.length() < 5 || host.length() > 253 || !host.contains("."))
            return false;
        for (String label : host.split("\\.")) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        }
        return true;
    }

    private boolean isAdUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) host = host.toLowerCase(Locale.US);
            String originalHost = host;
            String path = uri.getEncodedPath();
            // Walk up the domain labels (a.b.example.com -> b.example.com -> example.com -> com)
            // doing an O(1) HashSet lookup at each step, instead of the old approach of scanning
            // every one of ~111k blocklist entries per request with host.endsWith("."+h) — that
            // was fine at ~30 curated hosts, but would mean tens of millions of string
            // comparisons per page once the list covers general ad/tracker domains.
            Set<String> hosts = adHosts;
            while (host != null) {
                if (hosts.contains(host)) return true;
                Set<String> pathRules = adHostPaths.get(host);
                if (path != null && pathRules != null) {
                    for (String rulePath : pathRules) {
                        if (path.startsWith(rulePath)) {
                            int end = rulePath.length();
                            if (path.length() == end || !Character.isLetterOrDigit(path.charAt(end)))
                                return true;
                        }
                    }
                }
                int dot = host.indexOf('.');
                host = (dot >= 0 && dot + 1 < host.length()) ? host.substring(dot + 1) : null;
            }
            boolean youtubeRequest = originalHost != null
                && (originalHost.equals("youtube.com") || originalHost.endsWith(".youtube.com")
                    || originalHost.equals("youtu.be"));
            for (String p : adPatterns) {
                if (!url.contains(p)) continue;
                if (YOUTUBE_ONLY_URL_PATTERNS.contains(p) && !youtubeRequest) continue;
                return true;
            }
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

    // Result of the last check, read by the home-screen chip and its click handler —
    // nothing here pops a dialog on its own. The update is only ever downloaded/installed
    // when the person taps the chip themselves (see updateHomeUpdateStatus/onClick below).
    private String pendingUpdateVersion;
    private String pendingUpdateApkUrl;

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
                handler.post(() -> {
                    pendingUpdateVersion = finalLatest;
                    pendingUpdateApkUrl = finalUrl;
                    updateHomeUpdateStatus();
                });
            } catch (Exception ignored) {}
        });
    }

    // The one persistent "place" on the home screen for update/patch status — chip starts
    // hidden (nothing to say until the first check lands), reads "✅ Al día" once checked
    // with nothing newer, or "⬆️ vX.Y disponible" when there's something to install. Only
    // the second state is clickable; tapping it is what makes downloading voluntary rather
    // than something sprung on the person automatically.
    private void updateHomeUpdateStatus() {
        if (homeUpdateStatus == null) return;
        if (pendingUpdateVersion != null) {
            homeUpdateStatus.setText("⬆️ v" + pendingUpdateVersion + " disponible");
            homeUpdateStatus.setBackgroundResource(R.drawable.chip_green);
            homeUpdateStatus.setTextColor(0xFF4ADE80);
            homeUpdateStatus.setFocusable(true);
            homeUpdateStatus.setClickable(true);
            homeUpdateStatus.setVisibility(View.VISIBLE);
        } else if (prefs.getLong(PREF_UPDATE_CHECK_TIME, 0) > 0) {
            homeUpdateStatus.setText("✅ Al día");
            homeUpdateStatus.setBackgroundResource(R.drawable.chip_purple);
            homeUpdateStatus.setTextColor(0xFF8A8AA8);
            homeUpdateStatus.setFocusable(false);
            homeUpdateStatus.setClickable(false);
            homeUpdateStatus.setVisibility(View.VISIBLE);
        } else {
            homeUpdateStatus.setVisibility(View.GONE);
        }
    }

    private void onUpdateChipClick() {
        if (pendingUpdateVersion == null || pendingUpdateApkUrl == null) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle("🦁 Actualizar BraveTV")
            .setMessage("Versión v" + pendingUpdateVersion + " disponible (tenés v"
                + BuildConfig.VERSION_NAME + "). ¿Descargar e instalar ahora?")
            .setPositiveButton("Actualizar", (d, w) ->
                startUpdateDownload(pendingUpdateApkUrl, pendingUpdateVersion))
            .setNegativeButton("Cancelar", null)
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
        openDownloadedFile(apkFile, "application/vnd.android.package-archive");
    }

    private void openDownloadedFile(File file, String mimetype) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.bravetv.browser.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimetype == null || mimetype.isEmpty()
                ? "application/octet-stream" : mimetype);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "❌ No se pudo abrir \"" + file.getName() + "\"", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Descargas disparadas desde páginas de terceros (APKMirror, APKPure, etc.) ─────
    // WebView delega cualquier link de descarga aquí en vez de intentar resolverlo solo.
    // El disclaimer existe porque estos archivos vienen de fuera de Google Play: ni el
    // contenido ni lo que la persona haga con él (instalarlo) pasan por una revisión
    // nuestra, así que la decisión de seguir tiene que ser explícita.
    private void confirmAndDownload(String url, String userAgent, String contentDisposition,
                                     String mimetype) {
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
        new android.app.AlertDialog.Builder(this)
            .setTitle("⬇️ Descargar archivo")
            .setMessage("Vas a descargar \"" + fileName + "\" desde una fuente externa.\n\n" +
                "BraveTV no revisa este contenido: la descarga y lo que hagas con el archivo " +
                "(por ejemplo instalarlo) quedan bajo tu propia responsabilidad.\n\n¿Continuar?")
            .setPositiveButton("Descargar", (d, w) ->
                startExternalDownload(url, userAgent, fileName, mimetype))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void startExternalDownload(String url, String userAgent, String fileName, String mimetype) {
        boolean isApk = fileName.endsWith(".apk")
            || "application/vnd.android.package-archive".equals(mimetype);
        if (isApk && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this,
                "Activá \"Instalar apps desconocidas\" para BraveTV y volvé a intentar",
                Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        Toast.makeText(this, "⬇️ Descargando " + fileName + "…", Toast.LENGTH_SHORT).show();
        String cookie = CookieManager.getInstance().getCookie(url);
        bgExecutor.execute(() -> {
            try {
                File dir = new File(getExternalFilesDir(null), "downloads");
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, fileName);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                int code = conn.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                java.io.InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                out.close();
                conn.disconnect();
                handler.post(() -> openDownloadedFile(outFile, mimetype));
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(MainActivity.this,
                    "❌ No se pudo descargar \"" + fileName + "\"", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ─── Región ───────────────────────────────────────────────────────────────
    private String applyRegion(String url) {
        if (!autoVpnEnabled || url == null) return url;
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return url;
        try {
            Uri uri = Uri.parse(url);
            // YouTube's own redirect chain (/tv → /?app=desktop&...) carries our gl/hl
            // forward — appendQueryParameter doesn't dedupe, so re-applying on every hop of
            // that chain built up "...&gl=US&hl=es&gl=US&hl=es", and shouldOverrideUrlLoading
            // treating that as "the URL changed" forced a second full reload of the page.
            if (uri.getQueryParameter("gl") != null) return url;
            Uri.Builder b = uri.buildUpon();
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
            btnVpn.setText("🌐 REGIÓN OFF");
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
            homeVpnStatus.setText("🌐 REGIÓN AUTO desactivada");
            homeVpnStatus.setTextColor(0xFFAAAAAA);
        } else {
            homeVpnStatus.setText("🌐 REGIÓN AUTO · " + AUTO_LABELS[regionIndex]);
            homeVpnStatus.setTextColor(0xFF2ECC71);
        }
    }

    private void onGeoBlockDetected(String reason) {
        // BraveTVInterface is reachable from any page's own script, not just our injected
        // detector — without this check, any third-party site loaded in the WebView could
        // call BraveTV.onGeoBlock() directly and force-navigate the tab to YouTube.
        String curUrl = webView.getUrl();
        if (curUrl == null || !curUrl.contains("youtube.com")) return;
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
        public void onYouTubeTvTextEntry(boolean active) {
            youtubeTvTextEntry = active;
        }
        @JavascriptInterface
        public void onFullscreen(boolean full) {
            handler.post(() -> {
                // Keep the pointer active over fullscreen players too. BACK exits native
                // fullscreen, while D-pad/OK continue to move and click the visible cursor.
                if (full && pointerMode && cursorDot != null) cursorDot.bringToFront();
                setNavBarHidden(full);
            });
        }
        @JavascriptInterface
        public void onSponsorSkip(String label) {
            handler.post(() -> Toast.makeText(MainActivity.this,
                "⏭️ " + label + " saltado", Toast.LENGTH_SHORT).show());
        }
        // Fired by the location.href poll already running inside injectAdBlocker()'s Capa 2
        // loop — the only reliable signal for an in-session YouTube video switch, since that
        // navigation never reaches shouldOverrideUrlLoading/onPageStarted (see the comment there).
        @JavascriptInterface
        public void onVideoChanged(String videoId) {
            handler.post(() -> fetchSponsorBlockSegments(webView, videoId));
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
                    updateFloatingCursorButton();
                })
                .start();
        } else {
            if (btnRevealNav != null) btnRevealNav.setVisibility(View.GONE);
            updateFloatingCursorButton();
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

    private static boolean isPrivateSearchUrl(String url) {
        if (url == null) return false;
        try {
            String host = Uri.parse(url).getHost();
            return host != null && (host.equalsIgnoreCase("duck.com")
                || host.equalsIgnoreCase("duckduckgo.com")
                || host.toLowerCase(Locale.US).endsWith(".duckduckgo.com"));
        } catch (Exception ignored) { return false; }
    }

    // Remove DuckDuckGo entries left by previous versions as well as preventing new ones.
    private void purgePrivateSearchRecents() {
        try {
            JSONArray current = new JSONArray(prefs.getString(PREF_RECENTS, "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < current.length(); i++) {
                JSONObject entry = current.getJSONObject(i);
                if (!isPrivateSearchUrl(entry.optString("url"))) kept.put(entry);
            }
            if (kept.length() != current.length())
                prefs.edit().putString(PREF_RECENTS, kept.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void saveRecent(String url, String title) {
        if (url == null || title == null || title.isEmpty() || currentMode == NAV_INCOGNITO
                || isPrivateSearchUrl(url)) return;
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
        addUrlCard(recentsList, url, title, null);
    }

    // Shared by Recientes and Favoritos — onRemove non-null (favorites) adds long-press
    // to unsave; recents manage themselves (oldest just falls off MAX_RECENTS) so don't
    // need one.
    private void addUrlCard(LinearLayout container, String url, String title, Runnable onRemove) {
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
        iconView.setText(onRemove != null ? "★" : icon);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        iconView.setTextColor(onRemove != null ? 0xFFFBBF24
            : url.contains("youtube") ? 0xFFEF4444 : 0xFFC084FC);
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

        if (onRemove != null) {
            TextView hint = new TextView(this);
            hint.setText("mantén para quitar");
            hint.setTextColor(0xFF4A4A6A);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            hint.setGravity(android.view.Gravity.CENTER);
            card.addView(hint);
            card.setOnLongClickListener(v -> { onRemove.run(); return true; });
        }

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

        container.addView(card);
    }

    // ─── Favoritos — guardar la página actual para acceso rápido ─────────────
    private static final int MAX_FAVORITES = 8;
    private static final String PREF_FAVORITES = "favorites_json";

    private boolean isCurrentPageFavorite() {
        String url = webView.getUrl();
        if (url == null) return false;
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_FAVORITES, "[]"));
            for (int i = 0; i < arr.length(); i++)
                if (arr.getJSONObject(i).optString("url").equals(url)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void toggleFavorite() {
        if (webView.getVisibility() != View.VISIBLE) {
            Toast.makeText(this, "Abrí una página primero", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = webView.getUrl();
        if (url == null) return;
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_FAVORITES, "[]"));
            int existingIdx = -1;
            for (int i = 0; i < arr.length(); i++)
                if (arr.getJSONObject(i).optString("url").equals(url)) { existingIdx = i; break; }
            if (existingIdx >= 0) {
                removeFavoriteAt(existingIdx);
                Toast.makeText(this, "☆ Quitado de favoritos", Toast.LENGTH_SHORT).show();
                updateFavoriteButton();
                return;
            }
        } catch (Exception ignored) {}
        webView.evaluateJavascript("document.title", result -> {
            String title = (result == null || result.equals("null")) ? url
                : result.replaceAll("^\"|\"$", "").trim();
            if (title.isEmpty()) title = url;
            saveFavorite(url, title);
            Toast.makeText(this, "★ Guardado en favoritos", Toast.LENGTH_SHORT).show();
            updateFavoriteButton();
        });
    }

    private void saveFavorite(String url, String title) {
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_FAVORITES, "[]"));
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("url").equals(url)) filtered.put(o);
            }
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("title", title);
            JSONArray result = new JSONArray();
            result.put(entry);
            for (int i = 0; i < filtered.length() && i < MAX_FAVORITES - 1; i++)
                result.put(filtered.get(i));
            prefs.edit().putString(PREF_FAVORITES, result.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void removeFavoriteAt(int index) {
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_FAVORITES, "[]"));
            JSONArray result = new JSONArray();
            for (int i = 0; i < arr.length(); i++) if (i != index) result.put(arr.get(i));
            prefs.edit().putString(PREF_FAVORITES, result.toString()).apply();
            renderFavorites();
        } catch (Exception ignored) {}
    }

    private void updateFavoriteButton() {
        if (btnFavorite == null) return;
        boolean fav = isCurrentPageFavorite();
        btnFavorite.setText(fav ? "★" : "☆");
        btnFavorite.setTextColor(fav ? 0xFFFBBF24 : 0xFFF1F0FF);
    }

    private void renderFavorites() {
        if (favoritesSection == null || favoritesList == null) return;
        favoritesList.removeAllViews();
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_FAVORITES, "[]"));
            favoritesSection.setVisibility(arr.length() == 0 ? View.GONE : View.VISIBLE);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url", "");
                String title = o.optString("title", url);
                int idx = i;
                addUrlCard(favoritesList, url, title, () -> removeFavoriteAt(idx));
            }
        } catch (Exception ignored) {
            favoritesSection.setVisibility(View.GONE);
        }
    }

    // ─── Centro de beneficios (local JSON catalog) ───────────────────────────
    private File benefitsCatalogFile() { return new File(getFilesDir(), BENEFITS_FILE); }

    private void loadBenefitsCatalog() {
        File file = benefitsCatalogFile();
        try {
            if (!file.exists()) {
                try (java.io.InputStream in = getAssets().open(BENEFITS_FILE);
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
            }
            benefitsCatalog = validateBenefitsJson(readTextFile(file));
        } catch (Exception e) {
            try {
                benefitsCatalog = validateBenefitsJson(readAssetText(BENEFITS_FILE));
                saveBenefitsCatalog();
            } catch (Exception ignored) { benefitsCatalog = new JSONArray(); }
        }
    }

    private String readAssetText(String name) throws Exception {
        try (java.io.InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private String readTextFile(File file) throws Exception {
        try (java.io.InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void saveBenefitsCatalog() throws Exception {
        try (FileOutputStream out = new FileOutputStream(benefitsCatalogFile())) {
            out.write(benefitsCatalog.toString(2).getBytes("UTF-8"));
        }
    }

    private boolean isWebLink(String value) {
        if (value == null) return false;
        try {
            String scheme = Uri.parse(value.trim()).getScheme();
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        } catch (Exception ignored) { return false; }
    }

    private JSONArray validateBenefitsJson(String json) throws Exception {
        JSONArray input = new JSONArray(json);
        if (input.length() > 100) throw new IllegalArgumentException("El catálogo admite hasta 100 tarjetas.");
        JSONArray clean = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject source = input.getJSONObject(i);
            String category = source.optString("category", "");
            String name = source.optString("name", "").trim();
            String description = source.optString("description", "").trim();
            String url = source.optString("url", "").trim();
            boolean affiliate = source.optBoolean("affiliate", false);
            String affiliateUrl = source.optString("affiliateUrl", "").trim();
            if (!Arrays.asList(BENEFIT_CATEGORY_IDS).contains(category)
                    || name.isEmpty() || !isWebLink(url)
                    || (affiliate && !isWebLink(affiliateUrl)))
                throw new IllegalArgumentException("Revisa categoría, nombre y enlaces. Todo enlace afiliado debe ser una URL real.");
            JSONObject item = new JSONObject();
            item.put("category", category);
            item.put("name", name);
            item.put("description", description);
            item.put("url", url);
            item.put("free", source.optBoolean("free", false));
            item.put("subscription", source.optBoolean("subscription", false));
            item.put("purchase", source.optBoolean("purchase", false));
            item.put("affiliate", affiliate);
            item.put("affiliateUrl", affiliate ? affiliateUrl : "");
            clean.put(item);
        }
        return clean;
    }

    private void renderBenefits() {
        if (benefitsRows == null || !BENEFITS_CENTER_ENABLED) return;
        benefitsRows.removeAllViews();
        for (int c = 0; c < BENEFIT_CATEGORY_IDS.length; c++) {
            final String category = BENEFIT_CATEGORY_IDS[c];
            LinearLayout group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(-1, -2);
            groupLp.bottomMargin = dp(20);
            group.setLayoutParams(groupLp);

            TextView heading = new TextView(this);
            heading.setText(BENEFIT_CATEGORY_NAMES[c]);
            heading.setTextColor(0xFFF1F0FF);
            heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            heading.setTypeface(null, android.graphics.Typeface.BOLD);
            group.addView(heading);
            TextView subtitle = new TextView(this);
            subtitle.setText(BENEFIT_CATEGORY_SUBTITLES[c]);
            subtitle.setTextColor(0xFF777792);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
            subLp.topMargin = dp(2);
            group.addView(subtitle, subLp);

            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            LinearLayout cards = new LinearLayout(this);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-2, -2);
            cardsLp.topMargin = dp(10);
            cards.setLayoutParams(cardsLp);
            int count = 0;
            for (int i = 0; i < benefitsCatalog.length(); i++) {
                JSONObject item = benefitsCatalog.optJSONObject(i);
                if (item != null && category.equals(item.optString("category"))) {
                    addBenefitCard(cards, item, i);
                    count++;
                }
            }
            if (count == 0) {
                TextView empty = new TextView(this);
                empty.setText(benefitsAdminMode ? "Sin servicios · usa + Añadir servicio" : "Próximamente");
                empty.setTextColor(0xFF777792);
                empty.setTextSize(13);
                empty.setPadding(dp(8), dp(12), dp(8), dp(12));
                cards.addView(empty);
            }
            scroll.addView(cards);
            group.addView(scroll);
            benefitsRows.addView(group);
        }
    }

    private void addBenefitCard(LinearLayout parent, JSONObject item, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackgroundResource(R.drawable.card_mode);
        card.setContentDescription(item.optString("name") + ", " +
            (benefitsAdminMode ? "editar servicio" : "abrir servicio"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(250), dp(190));
        lp.setMarginEnd(dp(12));
        card.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(item.optString("name"));
        name.setTextColor(0xFFF1F0FF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name);

        TextView description = new TextView(this);
        description.setText(item.optString("description"));
        description.setTextColor(0xFFAAAAC0);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        description.setMaxLines(2);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        descLp.topMargin = dp(7);
        card.addView(description, descLp);

        TextView terms = new TextView(this);
        ArrayList<String> tags = new ArrayList<>();
        if (item.optBoolean("free")) tags.add("Plan gratis");
        if (item.optBoolean("subscription"))
            tags.add(item.optBoolean("free") ? "Suscripción opcional" : "Requiere suscripción");
        if (item.optBoolean("purchase")) tags.add("Compra individual");
        if (tags.isEmpty()) tags.add("Condiciones por verificar");
        tags.add(item.optBoolean("affiliate") ? "Enlace afiliado" : "Sin comisión");
        terms.setText(android.text.TextUtils.join("  ·  ", tags));
        terms.setTextColor(item.optBoolean("affiliate") ? 0xFFFBBF24 : 0xFF8FD6A6);
        terms.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        terms.setMaxLines(2);
        terms.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(terms);

        TextView action = new TextView(this);
        action.setText(benefitsAdminMode ? "Editar servicio" : "ABRIR SERVICIO  ›");
        action.setTextColor(0xFFC084FC);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        action.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, -2);
        actionLp.topMargin = dp(8);
        card.addView(action, actionLp);

        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                .setDuration(140).start();
            v.setElevation(dp(focused ? 12 : 3));
        });
        card.setOnClickListener(v -> {
            if (benefitsAdminMode) showBenefitEditor(index);
            else {
                String target = item.optBoolean("affiliate")
                    ? item.optString("affiliateUrl") : item.optString("url");
                if (isWebLink(target)) launchUrl(NAV_BRAVE, target);
            }
        });
        card.setOnLongClickListener(v -> {
            if (!benefitsAdminMode) return false;
            new android.app.AlertDialog.Builder(this)
                .setTitle("Retirar " + item.optString("name") + " del catálogo")
                .setMessage("Puedes volver a añadirlo después desde Administrar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Retirar", (d, w) -> removeBenefit(index))
                .show();
            return true;
        });
        parent.addView(card);
    }

    private EditText benefitField(String hint, String value, int inputType, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextColor(0xFFF1F0FF);
        field.setHintTextColor(0xFF777792);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setInputType(inputType);
        field.setSingleLine(!multiline);
        field.setMinHeight(dp(multiline ? 68 : 50));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setBackgroundResource(R.drawable.search_input_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(9);
        field.setLayoutParams(lp);
        return field;
    }

    private void showBenefitEditor(int index) {
        final JSONObject original = index >= 0 ? benefitsCatalog.optJSONObject(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(6), dp(4), dp(4));
        EditText name = benefitField("Nombre del servicio", original == null ? "" : original.optString("name"), android.text.InputType.TYPE_CLASS_TEXT, false);
        EditText description = benefitField("Descripción breve", original == null ? "" : original.optString("description"), android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE, true);
        EditText url = benefitField("Enlace oficial", original == null ? "https://" : original.optString("url"), android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, false);
        EditText affiliateUrl = benefitField("Enlace afiliado real (opcional)", original == null ? "" : original.optString("affiliateUrl"), android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, false);
        form.addView(name); form.addView(description); form.addView(url);

        Spinner category = new Spinner(this);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, BENEFIT_CATEGORY_NAMES));
        if (original != null) category.setSelection(Math.max(0, Arrays.asList(BENEFIT_CATEGORY_IDS).indexOf(original.optString("category"))));
        form.addView(category);
        CheckBox free = benefitCheck("Tiene plan gratis", original != null && original.optBoolean("free"));
        CheckBox subscription = benefitCheck("Tiene suscripción / plan de pago", original != null && original.optBoolean("subscription"));
        CheckBox purchase = benefitCheck("Es una compra de producto", original != null && original.optBoolean("purchase"));
        CheckBox affiliate = benefitCheck("El enlace genera comisión", original != null && original.optBoolean("affiliate"));
        form.addView(free); form.addView(subscription); form.addView(purchase); form.addView(affiliate);
        form.addView(affiliateUrl);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(form);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(440)));
        android.app.AlertDialog.Builder editorBuilder = new android.app.AlertDialog.Builder(this)
            .setTitle(original == null ? "Añadir servicio" : "Editar servicio")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null);
        if (original != null) editorBuilder.setNeutralButton("Retirar", null);
        android.app.AlertDialog dialog = editorBuilder.create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String serviceName = name.getText().toString().trim();
            String serviceUrl = url.getText().toString().trim();
            String affiliateLink = affiliateUrl.getText().toString().trim();
            if (serviceName.isEmpty() || !isWebLink(serviceUrl)
                    || (affiliate.isChecked() && !isWebLink(affiliateLink))) {
                Toast.makeText(this, "Agrega nombre y enlaces HTTP/HTTPS válidos; el enlace afiliado es obligatorio si marcas comisión.", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("category", BENEFIT_CATEGORY_IDS[category.getSelectedItemPosition()]);
                item.put("name", serviceName);
                item.put("description", description.getText().toString().trim());
                item.put("url", serviceUrl);
                item.put("free", free.isChecked());
                item.put("subscription", subscription.isChecked());
                item.put("purchase", purchase.isChecked());
                item.put("affiliate", affiliate.isChecked());
                item.put("affiliateUrl", affiliate.isChecked() ? affiliateLink : "");
                if (index < 0) benefitsCatalog.put(item);
                else {
                    JSONArray updated = new JSONArray();
                    for (int i = 0; i < benefitsCatalog.length(); i++) updated.put(i == index ? item : benefitsCatalog.get(i));
                    benefitsCatalog = updated;
                }
                saveBenefitsCatalog();
                renderBenefits();
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "No pude guardar el servicio.", Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFC084FC);
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFFB0AEC2);
        if (original != null) {
            android.widget.Button removeButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
            if (removeButton != null) {
                removeButton.setTextColor(0xFFFF8C8C);
                removeButton.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Retirar " + original.optString("name") + " del catálogo")
                    .setMessage("Podrás volver a añadirlo más adelante.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Retirar", (d, w) -> {
                        removeBenefit(index);
                        dialog.dismiss();
                    }).show());
            }
        }
        name.requestFocus();
    }

    private CheckBox benefitCheck(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(0xFFE1E0EF);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFC084FC));
        box.setChecked(checked);
        return box;
    }

    private void removeBenefit(int index) {
        try {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < benefitsCatalog.length(); i++) if (i != index) updated.put(benefitsCatalog.get(i));
            benefitsCatalog = updated;
            saveBenefitsCatalog();
            renderBenefits();
        } catch (Exception ignored) {}
    }

    private void importBenefitsJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try { startActivityForResult(intent, REQ_BENEFITS_IMPORT); }
        catch (Exception e) { Toast.makeText(this, "No hay explorador de archivos disponible.", Toast.LENGTH_SHORT).show(); }
    }

    private void exportBenefitsJson() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, BENEFITS_FILE);
        try { startActivityForResult(intent, REQ_BENEFITS_EXPORT); }
        catch (Exception e) { Toast.makeText(this, "No hay explorador de archivos disponible.", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            if (requestCode == REQ_BENEFITS_IMPORT) {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in == null) throw new IllegalArgumentException("No se pudo leer el archivo.");
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    JSONArray imported = validateBenefitsJson(out.toString("UTF-8"));
                    benefitsCatalog = imported;
                    saveBenefitsCatalog();
                    renderBenefits();
                    Toast.makeText(this, "Catálogo JSON importado.", Toast.LENGTH_SHORT).show();
                }
            } else if (requestCode == REQ_BENEFITS_EXPORT) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalArgumentException("No se pudo guardar el archivo.");
                    out.write(benefitsCatalog.toString(2).getBytes("UTF-8"));
                    Toast.makeText(this, "Catálogo JSON exportado.", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "Formato JSON inválido." : e.getMessage();
            Toast.makeText(this, "No se pudo procesar el catálogo: " + msg, Toast.LENGTH_LONG).show();
        }
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
        // DPAD focus alone must not open the TV keyboard or start editing the address.
        // Explicit OK/click enables the keyboard in beginUrlBarEditing().
        urlBar.setShowSoftInputOnFocus(false);
        progressBar   = findViewById(R.id.progressBar);
        statusText    = findViewById(R.id.statusText);
        navBar        = findViewById(R.id.navBar);
        btnVpn        = findViewById(R.id.btnVpn);
        homeScreen    = findViewById(R.id.homeScreen);
        homeVpnStatus  = findViewById(R.id.homeVpnStatus);
        homeQuality    = findViewById(R.id.homeQuality);
        if (homeQuality != null) homeQuality.setOnClickListener(v -> showQualityDialog());
        homeUpdateStatus = findViewById(R.id.homeUpdateStatus);
        if (homeUpdateStatus != null) homeUpdateStatus.setOnClickListener(v -> onUpdateChipClick());
        recentsList    = findViewById(R.id.recentsList);
        favoritesSection = findViewById(R.id.favoritesSection);
        favoritesList    = findViewById(R.id.favoritesList);
        benefitsRows = findViewById(R.id.benefitsRows);
        benefitsAdminTools = findViewById(R.id.benefitsAdminTools);
        btnBenefitsManage = findViewById(R.id.btnBenefitsManage);
        findViewById(R.id.btnBenefitsManage).setOnClickListener(v -> {
            benefitsAdminMode = !benefitsAdminMode;
            benefitsAdminTools.setVisibility(benefitsAdminMode ? View.VISIBLE : View.GONE);
            btnBenefitsManage.setText(benefitsAdminMode ? "Terminar" : "Administrar");
            renderBenefits();
        });
        findViewById(R.id.btnBenefitsAdd).setOnClickListener(v -> showBenefitEditor(-1));
        findViewById(R.id.btnBenefitsImport).setOnClickListener(v -> importBenefitsJson());
        findViewById(R.id.btnBenefitsExport).setOnClickListener(v -> exportBenefitsJson());
        loadBenefitsCatalog();
        btnFavorite      = findViewById(R.id.btnFavorite);
        if (btnFavorite != null) btnFavorite.setOnClickListener(v -> toggleFavorite());
        btnBlockCount  = findViewById(R.id.btnBlockCount);
        homeBlockCount = findViewById(R.id.homeBlockCount);
        btnRevealNav   = findViewById(R.id.btnRevealNav);
        btnRevealNav.setOnClickListener(v -> setNavBarHidden(false));
        btnCursorFloat = findViewById(R.id.btnCursorFloat);
        btnCursorFloat.setOnClickListener(v -> togglePointerMode());
        cursorDot       = findViewById(R.id.cursorDot);
        btnCursorToggle = findViewById(R.id.btnCursor);
        btnCursorToggle.setOnClickListener(v -> togglePointerMode());
        btnHideNav = findViewById(R.id.btnHideNav);
        if (btnHideNav != null) btnHideNav.setOnClickListener(v -> setNavBarHidden(true));

        prefs = getSharedPreferences("bravetv", 0); // Context.MODE_PRIVATE = 0
        purgePrivateSearchRecents();
        autoVpnEnabled = prefs.getBoolean("vpn", true);
        regionIndex    = prefs.getInt("regionIdx", 0);
        videoQualityCap = prefs.getString("videoQualityCap", "auto");
        updateHomeQualityLabel();
        updateHomeUpdateStatus();
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
        handler.removeCallbacks(hideIdleCursor);
        // Highlight button when active
        if (btnCursorToggle != null) {
            btnCursorToggle.setAlpha(pointerMode ? 1f : 0.5f);
        }
        if (btnCursorFloat != null) {
            btnCursorFloat.setText(pointerMode ? "Cursor ON" : "Cursor OFF");
            btnCursorFloat.setContentDescription(pointerMode ? "Desactivar cursor" : "Activar cursor");
            btnCursorFloat.setAlpha(pointerMode ? 1f : 0.72f);
        }
        if (pointerMode) {
            webView.post(() -> {
                cursor.set(webView.getWidth() / 2f, webView.getHeight() / 2f);
                moveCursorTo(cursor.x, cursor.y);
                cursorDot.setVisibility(View.VISIBLE);
            });
        } else {
            if (pointerHoverActive) {
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent hoverExit = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_HOVER_EXIT, cursor.x, cursor.y, 0);
                hoverExit.setSource(android.view.InputDevice.SOURCE_MOUSE);
                getWindow().getDecorView().dispatchGenericMotionEvent(hoverExit);
                hoverExit.recycle();
                pointerHoverActive = false;
            }
            if (cursorDot != null) {
                cursorDot.animate().cancel();
                cursorDot.setVisibility(View.GONE);
                cursorDot.setAlpha(1f);
            }
        }
        Toast.makeText(this,
            pointerMode ? "Puntero ON · D-pad mueve · OK clic · MENÚ alterna cursor" : "Cursor OFF · D-pad normal",
            Toast.LENGTH_SHORT).show();
    }

    private void updateFloatingCursorButton() {
        if (btnCursorFloat == null) return;
        boolean browserVisible = homeScreen != null && homeScreen.getVisibility() != View.VISIBLE;
        btnCursorFloat.setVisibility(navBarHidden && browserVisible ? View.VISIBLE : View.GONE);
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
        if (y > maxY) scrollAtCursor(220);
        else if (y < 0) scrollAtCursor(-220);
        cursor.set(cx, cy);
        cursorDot.setX(cx);
        cursorDot.setY(cy);
        cursorDot.setVisibility(View.VISIBLE);
        cursorDot.setAlpha(1f);
        dispatchCursorHover(cx, cy);
        resetCursorIdleTimer();
    }

    /** Scroll the nearest page container under the cursor, falling back to the document. */
    private void scrollAtCursor(int delta) {
        if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) return;
        float x = cursor.x;
        float y = cursor.y;
        int width = webView.getWidth();
        int height = webView.getHeight();
        String js = "(function(px,py,dx,dy,vw,vh){" +
            "var x=px*innerWidth/vw,y=py*innerHeight/vh,seen=[];" +
            "var hit=document.elementsFromPoint?document.elementsFromPoint(x,y):[document.elementFromPoint(x,y)];" +
            "for(var i=0;i<hit.length;i++){for(var e=hit[i];e&&e!==document.body&&e!==document.documentElement;e=e.parentElement){" +
            "if(seen.indexOf(e)>=0)continue;seen.push(e);var s=getComputedStyle(e),max=e.scrollHeight-e.clientHeight;" +
            "if(max>8&&(/auto|scroll|overlay/.test(s.overflowY)||e===document.scrollingElement)){" +
            "var n=Math.max(0,Math.min(max,e.scrollTop+dy));if(n!==e.scrollTop){e.scrollTop=n;return true;}}}}" +
            "var root=document.scrollingElement||document.documentElement,old=root.scrollTop;" +
            "root.scrollTop=Math.max(0,Math.min(root.scrollHeight-root.clientHeight,old+dy));" +
            "if(root.scrollTop===old)window.scrollBy(0,dy);return true;" +
            "})(" + x + "," + y + ",0," + delta + "," + width + "," + height + ")";
        webView.evaluateJavascript(js, null);
    }

    // Send a mouse-style hover to WebView as the D-pad cursor moves. YouTube TV's on-screen
    // keyboard can then react to the cursor position, while the D-pad itself is not also sent
    // as a focus-navigation key (which used to move its highlighted letter independently).
    private void dispatchCursorHover(float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        int action = pointerHoverActive
            ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER;
        MotionEvent hover = MotionEvent.obtain(now, now, action, x, y, 0);
        hover.setSource(android.view.InputDevice.SOURCE_MOUSE);
        getWindow().getDecorView().dispatchGenericMotionEvent(hover);
        if (action == MotionEvent.ACTION_HOVER_ENTER) {
            hover.setAction(MotionEvent.ACTION_HOVER_MOVE);
            getWindow().getDecorView().dispatchGenericMotionEvent(hover);
        }
        hover.recycle();
        pointerHoverActive = true;
    }

    private void resetCursorIdleTimer() {
        if (!pointerMode || cursorDot == null) return;
        handler.removeCallbacks(hideIdleCursor);
        cursorDot.animate().cancel();
        cursorDot.setVisibility(View.VISIBLE);
        cursorDot.setAlpha(1f);
        handler.postDelayed(hideIdleCursor, CURSOR_IDLE_HIDE_MS);
    }

    private void simulateClick(float x, float y) {
        resetCursorIdleTimer();
        lastGestureAtMs = System.currentTimeMillis();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up   = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
        // Dispatch through the window's decor view, not webView directly, so normal Android
        // hit-testing picks whatever is actually on screen at (x,y) — the navBar (elevation
        // 10dp, floats above webView when visible) if the cursor is over it, webView
        // otherwise. Dispatching straight to webView (the old behavior) always clicked
        // through to page content even when the navbar was the thing visibly covering it,
        // so the cursor could move webpage content but never actually select a navbar button.
        // cursor coordinates are already window-relative: webView fills the whole window
        // (FLAG_FULLSCREEN, no status/action bar), so no offset translation is needed.
        View decor = getWindow().getDecorView();
        decor.dispatchTouchEvent(down);
        decor.dispatchTouchEvent(up);
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
        int[] cardIds = {R.id.cardBrave, R.id.cardTor, R.id.cardYtTv, R.id.cardYtWeb,
            R.id.cardMovies, R.id.cardAnime};
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
        if (pointerMode) togglePointerMode();
        setNavBarHidden(false);
        updateFloatingCursorButton();
        renderRecents();
        renderFavorites();
        renderBenefits();
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
        String url;
        switch (mode) {
            case NAV_BRAVE:
                url = "https://brave.com/search/";
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
            case NAV_ANIME:
                url = "https://animeflv.or.at";
                break;
            default:
                url = "https://brave.com/search/";
                break;
        }
        launchUrl(mode, url);
    }

    // Shared tail of launchMode() — pulled out so a mode with more than one possible site
    // (Películas: Cuevana vs Tubi, see showMoviesDialog) can pick the URL itself without
    // duplicating the browser-setup boilerplate.
    private void launchUrl(int mode, String url) {
        // Leaving a private session — wipe whatever it wrote (cookies were already off,
        // but cache/DOM storage aren't) instead of letting it carry over silently.
        if (currentMode == NAV_INCOGNITO && mode != NAV_INCOGNITO) clearBrowsingData();
        currentMode = mode;
        showBrowser();
        configureWebViewForMode(mode);
        applyRegionCookies();
        updateVpnButton();
        loadUrl(url);
        handler.postDelayed(() -> webView.requestFocus(), 350);
        // Entering any browsing mode (YouTube, Brave, Películas, Anime…) goes hands-off by
        // default: hide the navbar and switch to the pointer/cursor so the D-pad drives the
        // page directly instead of the navbar's own button-to-button focus order. BACK, or
        // clicking the cursor-toggle button with the cursor itself, turns it back off.
        handler.postDelayed(() -> {
            setNavBarHidden(true);
            if (!pointerMode) togglePointerMode();
        }, 900);
    }

    private static final String[] MOVIE_SITE_LABELS = {"🎬 Cuevana", "🎞️ Cinecalidad"};
    private static final String[] MOVIE_SITE_URLS = {
        "https://ccuevana3.wtf/inicio", "https://cinecalidad.my/"
    };

    private void showMoviesDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("🎬 Elegir plataforma")
            .setItems(MOVIE_SITE_LABELS, (d, which) -> launchUrl(NAV_MOVIES, MOVIE_SITE_URLS[which]))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // En Cuevana/Cinecalidad y Anime el usuario elige primero película o episodio. El
    // reproductor puede aparecer tarde (o dentro de un iframe), así que lo buscamos durante
    // un rato y centramos su recuadro cuando exista, sin iniciar la reproducción.
    private void centerExternalVideoPlayer(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "if(window._btvCenterPlayer)return;window._btvCenterPlayer=true;" +
            "var tries=0,last=null;" +
            "function findPlayer(){" +
            " var sels='video,.jwplayer,.video-js,#player,.player,[id*=player i],[class*=player i],iframe';" +
            " var els=document.querySelectorAll(sels),best=null,bestScore=-1;" +
            " for(var i=0;i<els.length;i++){" +
            "  var e=els[i],r=e.getBoundingClientRect(),st=getComputedStyle(e);" +
            "  if(r.width<280||r.height<150||st.display==='none'||st.visibility==='hidden')continue;" +
            "  if(r.bottom<0||r.top>innerHeight*2)continue;" +
            "  var src=((e.src||'')+' '+(e.id||'')+' '+(e.className||'')).toLowerCase();" +
            "  if(/doubleclick|googlesyndication|adservice|ads?[_-]/.test(src))continue;" +
            "  var score=Math.min(r.width,innerWidth)*Math.min(r.height,innerHeight);" +
            "  if(e.tagName==='VIDEO')score*=4;" +
            "  if(/player|video|embed|jwplayer/.test(src))score*=2;" +
            "  if(score>bestScore){best=e;bestScore=score;}" +
            " }return best;" +
            "}" +
            "var timer=setInterval(function(){" +
            " tries++;var e=findPlayer();" +
            " if(e&&e!==last){last=e;var r=e.getBoundingClientRect();" +
            "  var y=scrollY+r.top+r.height/2-innerHeight/2;" +
            "  window.scrollTo({top:Math.max(0,y),behavior:'smooth'});" +
            " }if(tries>=180)clearInterval(timer);" +
            "},700);" +
            "})();", null);
    }

    private void focusBraveSearchInput(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "if(document.getElementById('_btv_brave_search'))return;" +
            // brave.com/search is a marketing page whose hero form sits below a large
            // header on TV screens. Put an in-page search control in the safe center,
            // then hand the query to Brave's actual results endpoint.
            "var form=document.createElement('form');form.id='_btv_brave_search';" +
            "form.style.cssText='position:fixed!important;left:50%!important;top:52%!important;' +" +
            "'transform:translate(-50%,-50%)!important;width:min(84vw,1250px)!important;' +" +
            "'height:88px!important;display:flex!important;align-items:stretch!important;' +" +
            "'gap:12px!important;padding:12px!important;box-sizing:border-box!important;' +" +
            "'background:#171725!important;border:2px solid #a855f7!important;border-radius:18px!important;' +" +
            "'z-index:2147483000!important;box-shadow:0 8px 36px #0009!important';" +
            "var input=document.createElement('input');input.type='search';input.placeholder='Buscar en Brave Search';" +
            "input.setAttribute('aria-label','Buscar en Brave Search');" +
            "input.style.cssText='flex:1!important;min-width:0!important;font-size:28px!important;' +" +
            "'padding:0 22px!important;border:0!important;border-radius:10px!important;' +" +
            "'background:#fff!important;color:#171725!important;outline:3px solid #a855f7!important';" +
            "var button=document.createElement('button');button.type='submit';button.textContent='Buscar';" +
            "button.style.cssText='width:150px!important;font-size:24px!important;font-weight:bold!important;' +" +
            "'border:0!important;border-radius:10px!important;background:#6d4aff!important;color:white!important';" +
            "form.appendChild(input);form.appendChild(button);" +
            "form.addEventListener('submit',function(e){e.preventDefault();var q=input.value.trim();" +
            "if(q)location.href='https://search.brave.com/search?q='+encodeURIComponent(q);});" +
            "(document.body||document.documentElement).appendChild(form);" +
            "input.focus({preventScroll:true});" +
            "})();", null);
    }

    private void configureWebViewForMode(int mode) {
        WebSettings s = webView.getSettings();
        if (mode == NAV_INCOGNITO) {
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(false);
        } else if (mode == NAV_YT_WEB) {
            // Desktop Chrome UA → this card is the HD/4K option, and desktop UA also unlocks
            // 1080p/4K selection on youtube.com (YouTube caps Smart-TV
            // UAs at 720p).
            s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(true);
        } else if (mode == NAV_YT_TV) {
            // Smart-TV UA — youtube.com/tv itself keys off this to decide whether to serve
            // the real lean-back TV interface or bounce to the desktop site with a "download
            // the app" nag. A prior change switched this to a desktop UA to unlock 4K (see
            // NAV_YT_WEB above) and applied it here too, which broke that detection: "YouTube
            // TV" started silently loading the desktop grid instead of the TV interface it's
            // labeled as, and took noticeably longer to settle. 720p cap is the trade-off for
            // getting the actual TV interface back — 4K is still available via YouTube Web.
            s.setUserAgentString(
                "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) " +
                "AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1");
            CookieManager.getInstance().setAcceptCookie(true);
        } else {
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
        // localStorage/IndexedDB survive clearCache() — without this, anything a site
        // writes to DOM storage during a private session was still readable afterwards.
        android.webkit.WebStorage.getInstance().deleteAllData();
    }

    // ─── YouTube ad-data stripping (JSON.parse patch) ────────────────────────
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
            // A native-looking spinner (rotating ring, YouTube-red accent) instead of the
            // old pulsing shield emoji — the goal is to read as 'video is loading', the
            // same thing YouTube's own buffering indicator looks like, not as an obvious
            // 'ad was blocked here' announcement sitting on top of the thumbnail.
            "'@keyframes btvSpin{to{transform:rotate(360deg)}}" +
            ".btv-shield{position:fixed;inset:0;z-index:2147483647;display:none;" +
            "align-items:center;justify-content:center;flex-direction:column;gap:16px;" +
            "background-color:#0b0714;" +
            // Default (no thumbnail yet): dark gradient. When a thumbnail is available the
            // tick handler overwrites background-image inline with it plus a dark gradient
            // layered on top — set as a plain background-image, not a filter, so it can't
            // blur/darken the .spinner/.msg children along with it.
            "background-image:radial-gradient(circle at 50% 45%,#1a1033 0%,#0b0714 70%);" +
            "background-size:cover;background-position:center;" +
            "font-family:sans-serif;pointer-events:none}" +
            ".btv-shield.on{display:flex}" +
            ".btv-shield .spinner{width:52px;height:52px;border-radius:50%;" +
            "border:4px solid rgba(255,255,255,0.18);border-top-color:#FF0033;" +
            "animation:btvSpin .85s linear infinite}" +
            ".btv-shield .msg{font-size:13px;letter-spacing:.3px;color:#eee;" +
            "text-shadow:0 1px 6px rgba(0,0,0,0.8)}';" +
            "document.head.appendChild(s);" +
            "var d=document.createElement('div');d.id='_btv_shield';d.className='btv-shield';" +
            // Built via createElement/textContent instead of innerHTML — YouTube enforces a
            // Trusted Types CSP that throws on innerHTML assignment, which was silently
            // breaking shield creation entirely (every caller already null-checks
            // getElementById('_btv_shield'), so the throw never surfaced as a visible error,
            // it just meant the loading shield never appeared).
            "var sp=document.createElement('div');sp.className='spinner';" +
            "var ms=document.createElement('div');ms.className='msg';ms.id='_btv_shield_msg';" +
            "ms.textContent='Cargando video\\u2026';" +
            "d.appendChild(sp);d.appendChild(ms);" +
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
        fetchSponsorBlockSegments(view, videoId);
    }

    // ─── SponsorBlock (https://sponsor.ajay.app) ─────────────────────────────────
    // Public API only — nothing from SponsorBlock's own client/server source is used here
    // (both are GPL/AGPL; this project is MIT, and copying would force relicensing the
    // whole app). We send only a 4-hex-char prefix of the video ID's SHA-256, the same
    // privacy-preserving lookup their official extension uses by default, so sponsor.ajay.app
    // never sees the full video ID — just every video sharing that prefix, filtered locally.
    private static final String SPONSORBLOCK_API = "https://sponsor.ajay.app/api/skipSegments/";
    // The three categories SponsorBlock's own extension treats as safe to auto-skip without
    // asking (as opposed to outro/preview/music_offtopic, which some people want to watch).
    private static final String[] SPONSORBLOCK_CATEGORIES = {"sponsor", "selfpromo", "interaction"};
    private static final String[] SPONSORBLOCK_CATEGORY_LABELS =
        {"Patrocinio", "Auto-promoción", "Recordatorio"};

    private static int sponsorCategoryIndex(String category) {
        for (int i = 0; i < SPONSORBLOCK_CATEGORIES.length; i++)
            if (SPONSORBLOCK_CATEGORIES[i].equals(category)) return i;
        return -1;
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void fetchSponsorBlockSegments(WebView view, String videoId) {
        // onVideoChanged() is a @JavascriptInterface method, so videoId can be anything a
        // page's own script passes in, not just a real YouTube ID — reject early instead of
        // hashing/requesting on arbitrary attacker-controlled input.
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return;
        float[][] cached = sponsorSegmentCache.get(videoId);
        if (cached != null) { pushSponsorSegments(view, cached); return; }
        // Clear whatever the previous video left behind so a SPA switch (no page reload)
        // can't skip the new video using the old one's segment times.
        pushSponsorSegments(view, new float[0][]);
        bgExecutor.execute(() -> {
            try {
                String hash = sha256Hex(videoId);
                if (hash.length() < 4) return;
                StringBuilder cats = new StringBuilder("[");
                for (int i = 0; i < SPONSORBLOCK_CATEGORIES.length; i++) {
                    if (i > 0) cats.append(',');
                    cats.append('"').append(SPONSORBLOCK_CATEGORIES[i]).append('"');
                }
                cats.append(']');
                String apiUrl = SPONSORBLOCK_API + hash.substring(0, 4)
                    + "?categories=" + Uri.encode(cats.toString())
                    + "&actionTypes=" + Uri.encode("[\"skip\"]")
                    + "&service=YouTube";
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept", "application/json");
                int code = conn.getResponseCode();
                // 404 = no segments for any video sharing this hash prefix right now.
                if (code == 404) { sponsorSegmentCache.put(videoId, new float[0][]); return; }
                if (code != 200) return;
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONArray videos = new JSONArray(sb.toString());
                List<float[]> segs = new ArrayList<>();
                for (int i = 0; i < videos.length(); i++) {
                    JSONObject entry = videos.getJSONObject(i);
                    if (!videoId.equals(entry.optString("videoID"))) continue;
                    JSONArray arr = entry.optJSONArray("segments");
                    if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject seg = arr.getJSONObject(j);
                        JSONArray times = seg.optJSONArray("segment");
                        if (times == null || times.length() != 2) continue;
                        int catIdx = sponsorCategoryIndex(seg.optString("category"));
                        if (catIdx < 0) continue;
                        segs.add(new float[]{(float) times.getDouble(0), (float) times.getDouble(1), catIdx});
                    }
                }
                float[][] result = segs.toArray(new float[0][]);
                sponsorSegmentCache.put(videoId, result);
                handler.post(() -> pushSponsorSegments(view, result));
            } catch (Exception ignored) {
            }
        });
    }

    private void pushSponsorSegments(WebView view, float[][] segments) {
        StringBuilder js = new StringBuilder("window._btvSponsorSegments=[");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) js.append(',');
            js.append('[').append(segments[i][0]).append(',').append(segments[i][1])
                .append(',').append((int) segments[i][2]).append(']');
        }
        js.append("];console.log('_btv segments pushed:',window._btvSponsorSegments.length);");
        view.evaluateJavascript(js.toString(), null);
    }

    // JS array literal of SPONSORBLOCK_CATEGORY_LABELS, inlined into the Capa 2 ad-skip
    // loop in injectAdBlocker() so segment-skipping piggybacks on that loop's existing
    // 200ms tick instead of running a separate timer.
    private static String sponsorLabelsJsArray() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SPONSORBLOCK_CATEGORY_LABELS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('\'').append(SPONSORBLOCK_CATEGORY_LABELS[i]).append('\'');
        }
        return sb.append(']').toString();
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
            // Catches switching to a new video no matter HOW the navigation happened —
            // onPageStarted/shouldOverrideUrlLoading on the Android side only fire for
            // real top-level page loads, but YouTube TV mostly moves between videos via
            // its own client-side router (history.pushState), which never triggers those.
            // That's the gap that let the gray loading screen through even after priming
            // the shield from Java: this is what actually catches every video change, by
            // polling location.href itself instead of relying on Android to tell us.
            "  var _vidNow=(location.href.match(/(?:[?&]v=|youtu\\.be\\/)([A-Za-z0-9_-]{11})/)||[])[1];" +
            "  if(_vidNow&&_vidNow!==window._btvCurVid){" +
            "    window._btvCurVid=_vidNow;" +
            "    var sh0=document.getElementById('_btv_shield');" +
            "    if(sh0){" +
            "      sh0.classList.add('on');" +
            "      sh0.style.backgroundImage='linear-gradient(rgba(8,4,16,.6),rgba(8,4,16,.8))," +
            "url(\"https://i.ytimg.com/vi/'+_vidNow+'/hqdefault.jpg\")';" +
            "      var sm0=document.getElementById('_btv_shield_msg');" +
            "      if(sm0)sm0.textContent='Cargando\\u2026';" +
            "    }" +
            "    window._btvPendingPlay=true;window._btvPendingPlayAt=Date.now();" +
            "    if(typeof BraveTV!=='undefined')BraveTV.onVideoChanged(_vidNow);" +
            "  }" +
            // SponsorBlock: segments for the current video, kept updated by
            // fetchSponsorBlockSegments()/pushSponsorSegments() — piggybacks on this same
            // 200ms tick instead of running its own separate timer.
            "  var _sbV=document.querySelector('video');" +
            "  if(_sbV&&!_sbV.paused&&_sbV.duration){" +
            "    var _sbSegs=window._btvSponsorSegments||[];" +
            "    for(var _sbI=0;_sbI<_sbSegs.length;_sbI++){" +
            "      var _sbS=_sbSegs[_sbI];" +
            "      if(_sbV.currentTime>=_sbS[0]&&_sbV.currentTime<_sbS[1]){" +
            "        _sbV.currentTime=_sbS[1];" +
            "        if(typeof BraveTV!=='undefined')" +
            "          BraveTV.onSponsorSkip(" + sponsorLabelsJsArray() + "[_sbS[2]]||'segmento');" +
            "        break;" +
            "      }" +
            "    }" +
            "  }" +
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
            // Always the same neutral 'loading' text — this is meant to read as normal
            // video loading, not announce that an ad was caught, so it blends in instead
            // of standing out as an obvious ad-block screen.
            "    if(show&&vs&&vs.poster){" +
            "      shield.style.backgroundImage='linear-gradient(rgba(8,4,16,.6),rgba(8,4,16,.8)),url(\"'+vs.poster+'\")';" +
            "    } else if(!show){ shield.style.backgroundImage=''; }" +
            "  }" +
            "  if(sk){sk.click();return;}" +
            // No more force-seeking through ads (used to set playbackRate=16 and jump
            // currentTime to the end) — that's what was skipping real content when
            // adMarked false-positived, and even when it correctly caught a real ad, the
            // extreme-speed scrub rendered as a glitchy small box for an instant, which is
            // worse than just letting the ad play normally. Skipping is still immediate
            // whenever YouTube offers an actual skip button (sk, above) or an interstitial
            // dismiss (below, YouTube TV) — those are real, intentional controls. Anything
            // without one just plays at normal speed under the shield until it ends.
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

    /** Track YouTube TV's search field so pointer movement won't also change its keyboard focus. */
    private void injectYouTubeTvTextEntryState(WebView view) {
        if (currentMode != NAV_YT_TV) return;
        view.evaluateJavascript("(function(){" +
            "if(window._btvYTTextEntry)return;window._btvYTTextEntry=true;var last=null;" +
            "function check(){var e=document.activeElement;var on=!!(e&&e.matches&&e.matches('input,textarea,[contenteditable=true],[role=textbox],[role=searchbox]'))||!!(e&&e.isContentEditable);" +
            "if(on!==last){last=on;if(window.BraveTV)BraveTV.onYouTubeTvTextEntry(on);}}" +
            "document.addEventListener('focusin',check,true);document.addEventListener('focusout',function(){setTimeout(check,80);},true);" +
            "check();setInterval(check,600);" +
            "})()", null);
    }

    // ─── WebView setup ────────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // Keep target=_blank / window.open inside the app: pages must not be able to
        // create an unmanaged WebView window that bypasses the ad and redirect filters.
        // Regular links still navigate in the current WebView through WebViewClient.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
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

        // Sitios tipo APKVision esconden el link real detrás de redirecciones con ads —
        // WebView sin DownloadListener simplemente ignora esos links (no pasa nada al
        // tocarlos). Lo registramos para poder interceptar la descarga nosotros y avisarle
        // a la persona que el archivo viene de un tercero antes de bajarlo.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
            confirmAndDownload(url, userAgent, contentDisposition, mimetype));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (isAdUrl(req.getUrl().toString())) {
                    int totalBlocked = blockedCount.incrementAndGet();
                    handler.post(() -> {
                        if (btnBlockCount != null)
                            btnBlockCount.setText("🚫 " + totalBlocked);
                        if (homeBlockCount != null)
                            homeBlockCount.setText("🚫 " + totalBlocked + " bloqueados");
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
                youtubeTvTextEntry = false;
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
                updateFavoriteButton();

                // Save to recents (title via JS)
                view.evaluateJavascript("document.title", result -> {
                    if (result != null) {
                        String title = result.replaceAll("^\"|\"$", "").trim();
                        saveRecent(url, title);
                    }
                });

                injectAdBlocker(view);
                injectDpadNavigation(view);
                injectYouTubeTvTextEntryState(view);
                if (currentMode == NAV_MOVIES || currentMode == NAV_ANIME)
                    centerExternalVideoPlayer(view);
                if (currentMode == NAV_BRAVE && url != null
                        && url.startsWith("https://brave.com/search"))
                    focusBraveSearchInput(view);
                // Page loads sometimes restore/show the toolbar after launchUrl's initial
                // hide request. Keep browser chrome out of the webpage's top search controls.
                if (homeScreen.getVisibility() != View.VISIBLE)
                    handler.postDelayed(() -> setNavBarHidden(true), 450);

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
                String regioned = currentMode != NAV_INCOGNITO ? applyRegion(url) : url;
                // Nothing to change (not a YouTube URL, VPN off, or the region params are
                // already on it — e.g. a mid-chain redirect hop) — let WebView navigate
                // normally instead of forcing a second reload through loadUrl(). This was
                // firing on every hop of YouTube's own /tv → desktop redirect chain, each one
                // re-triggering the shield + a 1200ms ad-blocker re-injection for no reason,
                // which is what made YouTube TV feel stuck/slow to load.
                if (regioned.equals(url)) return false;
                view.loadUrl(regioned);
                // SPA navigation to a new video (YouTube TV switches without a full page
                // load) — onPageStarted won't fire for this, so prime the shield here too.
                if (currentMode == NAV_YT_TV || currentMode == NAV_YT_WEB)
                    showShieldForVideo(view, url);
                // Re-inject ad blocker after SPA navigation (YouTube TV doesn't fire onPageFinished)
                handler.postDelayed(() -> {
                    injectAdBlocker(view); injectDpadNavigation(view); injectYouTubeTvTextEntryState(view);
                }, 1200);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                // WebView popups have no associated BraveTV WebViewClient, so they would
                // escape request filtering and could open ad tabs over the current page.
                // Deny the popup; the original page remains loaded and usable.
                Toast.makeText(MainActivity.this, "🛡 Ventana emergente bloqueada",
                    Toast.LENGTH_SHORT).show();
                return false;
            }

            @Override
            public void onProgressChanged(WebView view, int p) { progressBar.setProgress(p); }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                customViewCallback = cb;
                navBarHiddenBeforeFullscreen = navBarHidden;
                navBar.setVisibility(View.GONE);
                LinearLayout c = findViewById(R.id.fullscreenContainer);
                c.addView(view);
                c.setVisibility(View.VISIBLE);
                navBarHidden = true;
                // Keep the WebView alive behind Chromium's fullscreen surface. Some TV
                // WebView builds pause the media element when its WebView becomes GONE.
                // The fullscreen container covers it, while the pointer remains above both.
                c.bringToFront();
                if (pointerMode && cursorDot != null) {
                    cursorDot.setVisibility(View.VISIBLE);
                    cursorDot.bringToFront();
                }
                updateFloatingCursorButton();
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
                customView = null;
                customViewCallback = null;
                setNavBarHidden(navBarHiddenBeforeFullscreen);
                updateFloatingCursorButton();
                if (pointerMode && cursorDot != null) cursorDot.bringToFront();
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
        findViewById(R.id.cardMovies).setOnClickListener(v -> showMoviesDialog());
        findViewById(R.id.cardAnime).setOnClickListener(v -> launchMode(NAV_ANIME));

        findViewById(R.id.btnYoutube).setOnClickListener(v ->
            loadUrl(currentMode == NAV_YT_WEB
                ? "https://www.youtube.com/" : applyRegion("https://www.youtube.com/tv")));
        findViewById(R.id.btnSearch).setOnClickListener(v -> {
            String text = urlBar.getText().toString().trim();
            if (text.isEmpty()) {
                showSearchDialog();
            } else {
                performAddressAction(text);
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btnHome).setOnClickListener(v -> showHome());

        urlBar.setOnClickListener(v -> beginUrlBarEditing());
        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && urlBarEditing) finishUrlBarEditing(false);
        });

        btnVpn.setOnClickListener(v -> {
            autoVpnEnabled = !autoVpnEnabled;
            prefs.edit().putBoolean("vpn", autoVpnEnabled).apply();
            updateVpnButton();
            applyRegionCookies();
            Toast.makeText(this,
                autoVpnEnabled ? "🟢 REGIÓN AUTO activada" : "🔴 REGIÓN AUTO desactivada",
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
            finishUrlBarEditing(true);
            performAddressAction(urlBar.getText().toString());
            return true;
        });
    }

    private void beginUrlBarEditing() {
        if (urlBarEditing) return;
        urlBarEditing = true;
        urlBar.setShowSoftInputOnFocus(true);
        urlBar.requestFocus();
        urlBar.post(() -> {
            urlBar.selectAll();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(urlBar,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void finishUrlBarEditing(boolean hideKeyboard) {
        if (!urlBarEditing) return;
        urlBarEditing = false;
        urlBar.setShowSoftInputOnFocus(false);
        if (hideKeyboard) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    private void performAddressAction(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) return;
        // Treat plain text as a search even when it contains punctuation; only a complete
        // scheme or a simple domain without spaces is a URL.
        boolean isUrl = input.startsWith("http://") || input.startsWith("https://")
            || (input.contains(".") && !input.contains(" ") && input.length() > 4);
        if (isUrl) loadUrl(input.startsWith("http") ? input : "https://" + input);
        else doSearch(input);
    }

    private void showSearchDialog() {
        final boolean restorePointer = pointerMode;
        if (restorePointer) {
            togglePointerMode();
        }
        String hint;
        String title;
        switch (currentMode) {
            case NAV_BRAVE:     hint = "Buscar en Brave Search...";   title = "🔍 Brave Search";   break;
            case NAV_INCOGNITO: hint = "Buscar en DuckDuckGo...";     title = "🔒 DuckDuckGo";     break;
            case NAV_YT_TV: case NAV_YT_WEB:
                                hint = "Buscar en YouTube...";        title = "🔍 Buscar YouTube";  break;
            default:            hint = "Buscar en Brave Search...";   title = "🔍 Brave Search";   break;
        }

        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setTextColor(android.graphics.Color.WHITE);
        input.setHintTextColor(android.graphics.Color.rgb(151, 145, 177));
        input.setTextSize(18);
        input.setMinHeight(dp(56));
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setShowSoftInputOnFocus(true);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setBackgroundResource(R.drawable.search_input_bg);
        // Without this, the on-screen keyboard showed a return/newline key instead of
        // "Buscar" — IME_ACTION_SEARCH alone isn't enough; the field also has to be
        // explicitly single-line or several TV keyboards default to the newline action.
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Buscar", (d, w) -> doSearch(input.getText().toString()))
            .setNegativeButton("Cancelar", null)
            .create();
        dialog.setOnDismissListener(d -> {
            if (restorePointer) handler.postDelayed(() -> {
                if (!pointerMode && homeScreen.getVisibility() != View.VISIBLE) togglePointerMode();
            }, 700);
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            doSearch(input.getText().toString());
            dialog.dismiss();
            return true;
        });

        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        android.view.Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
            panel.setColor(android.graphics.Color.rgb(18, 18, 30));
            panel.setCornerRadius(dp(18));
            panel.setStroke(dp(1), android.graphics.Color.rgb(75, 65, 103));
            dialogWindow.setBackgroundDrawable(panel);
        }
        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        TextView titleView = titleId == 0 ? null : dialog.findViewById(titleId);
        if (titleView != null) {
            titleView.setTextColor(android.graphics.Color.rgb(241, 240, 255));
            titleView.setTextSize(20);
            titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        }
        android.widget.Button searchButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        android.widget.Button cancelButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
        if (searchButton != null) searchButton.setTextColor(android.graphics.Color.rgb(192, 132, 252));
        if (cancelButton != null) cancelButton.setTextColor(android.graphics.Color.rgb(174, 170, 194));
        input.requestFocus();
        input.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && dialog.isShowing())
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 220);
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
            case NAV_YT_TV:
                url = applyRegion("https://www.youtube.com/tv#/search?query=" + Uri.encode(query));
                break;
            case NAV_YT_WEB:
                url = applyRegion("https://www.youtube.com/results?search_query=" + Uri.encode(query));
                break;
            default:
                url = "https://search.brave.com/search?q=" + Uri.encode(query);
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

        // Remote shortcut: MENU toggles the cursor anywhere in the browser. F6 provides
        // the same shortcut when a keyboard is connected. Consume key-up too so MENU cannot
        // also open a page or Android context menu.
        boolean cursorShortcut = event.getKeyCode() == KeyEvent.KEYCODE_MENU
            || event.getKeyCode() == KeyEvent.KEYCODE_F6;
        if (cursorShortcut && (webView.getVisibility() == View.VISIBLE || customView != null)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                togglePointerMode();
            return true;
        }

        // Pointer mode must own every D-pad key itself, before anything below gets a chance at
        // it. Two different things were stealing it: (1) whatever View still held Android focus
        // right after the OK press that turned pointer mode on — usually the cursor-toggle
        // button itself — would eat the *next* OK as its own click (i.e. toggling pointer back
        // off) once this method fell through to super.dispatchKeyEvent(), since that hands the
        // event to the focused View first; (2) the "reveal hidden navbar on any key" branch right
        // below this one, which swallowed OK whenever the navbar had auto-hidden. Both are fixed
        // by handling the click/move here, unconditionally, before either path runs.
        if (event.getAction() == KeyEvent.ACTION_DOWN && pointerMode
                && (webView.getVisibility() == View.VISIBLE || customView != null)) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (currentMode == NAV_YT_TV && customView == null && !youtubeTvTextEntry)
                        webView.dispatchKeyEvent(event);
                    moveCursorTo(cursor.x, cursor.y - CURSOR_STEP); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (currentMode == NAV_YT_TV && customView == null && !youtubeTvTextEntry)
                        webView.dispatchKeyEvent(event);
                    moveCursorTo(cursor.x, cursor.y + CURSOR_STEP); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveCursorTo(cursor.x - CURSOR_STEP, cursor.y); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveCursorTo(cursor.x + CURSOR_STEP, cursor.y); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    simulateClick(cursor.x, cursor.y);
                    return true;
            }
        }

        // Let YouTube TV finish its directional scroll/focus action while preserving cursor
        // control. Suppress this only while its text field is active, where arrows belong to
        // the on-screen keyboard and must not independently move the highlighted letter.
        if (event.getAction() == KeyEvent.ACTION_UP && pointerMode
                && currentMode == NAV_YT_TV && customView == null && !youtubeTvTextEntry
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)) {
            webView.dispatchKeyEvent(event);
            return true;
        }

        // In normal D-pad mode the address field is reached by focus navigation. Only
        // its explicit OK/Enter opens the IME and selects the full current URL for replacement.
        if (event.getAction() == KeyEvent.ACTION_DOWN && !pointerMode && urlBar != null
                && urlBar.hasFocus() && !urlBarEditing
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            beginUrlBarEditing();
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
        handler.removeCallbacks(hideIdleCursor);
        if (currentMode == NAV_INCOGNITO) clearBrowsingData();
        bgExecutor.shutdown();
        webView.destroy();
    }
}
